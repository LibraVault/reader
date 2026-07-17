// Tests for server/proxy.js
//
// Uses node:test (Node 22 built-in). The proxy.js module is imported as a
// side-effect — it boots its Express app on the port from process.env.PORT.
// We set PORT to a fixed test port and stub global fetch before importing.

import { test, before } from "node:test";
import assert from "node:assert/strict";

// Required env vars — must be set BEFORE importing proxy.js so its
// startup-time `process.exit(1)` guards don't fire.
process.env.PORT = "13579";
process.env.BTCPAY_API_KEY = "test-key";
process.env.STORE_ID = "TestStoreIdXYZ";
process.env.PROXY_SHARED_SECRET = "test-secret-that-is-at-least-32-chars-long";
process.env.BTCPAY_URL = "https://btcpay.test.invalid";

// Stub global fetch — proxy.js calls fetch() to reach BTCPay upstream.
// Save the real fetch first so the tests themselves can call it.
const realFetch = globalThis.fetch;
const fetchCalls = [];
globalThis.fetch = async (url, init) => {
    fetchCalls.push({ url, init });
    return new Response(JSON.stringify({ id: "stub-id", checkoutLink: "https://stub/", status: "New" }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
    });
};

// Import for side-effect: triggers app.listen(13579).
await import("../proxy.js");

// Give the listen callback a tick to fire.
await new Promise(r => setTimeout(r, 100));

const baseUrl = `http://127.0.0.1:13579`;

function bearer() {
    return { Authorization: `Bearer ${process.env.PROXY_SHARED_SECRET}` };
}

test("rejects request without Authorization header", async () => {
    const res = await realFetch(`${baseUrl}/donate/invoice`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ amountUsd: 5 }),
    });
    assert.equal(res.status, 401);
});

test("rejects request with wrong Authorization token", async () => {
    const res = await realFetch(`${baseUrl}/donate/invoice`, {
        method: "POST",
        headers: { Authorization: "Bearer wrong-secret-that-is-32-chars-long!!" },
        body: JSON.stringify({ amountUsd: 5 }),
    });
    assert.equal(res.status, 401);
});

test("rejects request with non-Bearer scheme", async () => {
    const res = await realFetch(`${baseUrl}/donate/invoice`, {
        method: "POST",
        headers: { Authorization: `Basic ${process.env.PROXY_SHARED_SECRET}` },
        body: JSON.stringify({ amountUsd: 5 }),
    });
    assert.equal(res.status, 401);
});

test("POST /donate/invoice rejects oversized body", async () => {
    const big = JSON.stringify({ amountUsd: 5, padding: "x".repeat(5000) });
    const res = await realFetch(`${baseUrl}/donate/invoice`, {
        method: "POST",
        headers: { "Content-Type": "application/json", ...bearer() },
        body: big,
    });
    assert.equal(res.status, 413);
});

test("POST /donate/invoice accepts valid auth + body", async () => {
    const res = await realFetch(`${baseUrl}/donate/invoice`, {
        method: "POST",
        headers: { "Content-Type": "application/json", ...bearer() },
        body: JSON.stringify({ amountUsd: 5 }),
    });
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.id, "stub-id");
});

test("POST /donate/invoice rejects non-integer amount", async () => {
    const res = await realFetch(`${baseUrl}/donate/invoice`, {
        method: "POST",
        headers: { "Content-Type": "application/json", ...bearer() },
        body: JSON.stringify({ amountUsd: 5.5 }),
    });
    assert.equal(res.status, 400);
});

test("POST /donate/invoice rejects out-of-range amount", async () => {
    for (const amount of [0, -1, 1001, 99999]) {
        const res = await realFetch(`${baseUrl}/donate/invoice`, {
            method: "POST",
            headers: { "Content-Type": "application/json", ...bearer() },
            body: JSON.stringify({ amountUsd: amount }),
        });
        assert.equal(res.status, 400, `amount ${amount} should be rejected`);
    }
});

test("GET /donate/invoice/:id rejects non-hex invoice id", async () => {
    const res = await realFetch(`${baseUrl}/donate/invoice/not-hex-zzz`, {
        headers: bearer(),
    });
    assert.equal(res.status, 400);
});

test("GET /donate/invoice/:id accepts hex invoice id", async () => {
    const hex = "abcdef0123456789".repeat(2); // 32 hex chars
    const res = await realFetch(`${baseUrl}/donate/invoice/${hex}`, {
        headers: bearer(),
    });
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.status, "New");
});

test("GET /donate/invoice/:id/payment/:coin rejects invalid coin", async () => {
    const hex = "abcdef0123456789".repeat(2);
    const res = await realFetch(`${baseUrl}/donate/invoice/${hex}/payment/DOGE`, {
        headers: bearer(),
    });
    assert.equal(res.status, 400);
});

test("GET /donate/invoice/:id/payment/BTC accepts BTC", async () => {
    const hex = "abcdef0123456789".repeat(2);
    const res = await realFetch(`${baseUrl}/donate/invoice/${hex}/payment/BTC`, {
        headers: bearer(),
    });
    assert.equal(res.status, 200);
});

test("GET /donate/settled returns 404 (endpoint removed in WS2)", async () => {
    const res = await realFetch(`${baseUrl}/donate/settled`, {
        headers: bearer(),
    });
    assert.equal(res.status, 404);
});

test("Unknown route returns 404", async () => {
    const res = await realFetch(`${baseUrl}/something/else`, {
        headers: bearer(),
    });
    assert.equal(res.status, 404);
});