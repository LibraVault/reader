#!/usr/bin/env node
// Donate proxy — runs on the BTCPay VPS, reverse-proxied by nginx.
// Config: copy .env.example → .env and fill in BTCPAY_API_KEY.
// Start: node proxy.js   (or: PORT=3456 node proxy.js)

import "dotenv/config";
import http from "node:http";

const PORT       = process.env.PORT       || 3456;
const BTCPAY_URL = process.env.BTCPAY_URL || "https://pay.libravault.xyz";
const STORE_ID   = process.env.STORE_ID   || "Fr4b3j8B2CHMHsh2a4QT5QSKgNxT41r2ymz3F4RrcWxe";
const API_KEY    = process.env.BTCPAY_API_KEY;

if (!API_KEY) {
    console.error("BTCPAY_API_KEY is not set — copy .env.example to .env and fill it in.");
    process.exit(1);
}

const INVOICE_ID_RE = /^[A-Za-z0-9]{8,64}$/;
const ALLOWED_COINS = new Set(["BTC", "XMR"]);

// ---------------------------------------------------------------------------

const server = http.createServer(async (req, res) => {
    const url    = new URL(req.url, `http://localhost`);
    const path   = url.pathname.replace(/\/$/, "");
    const method = req.method;

    try {
        // POST /donate/invoice
        if (method === "POST" && path === "/donate/invoice") {
            const body = await readJson(req);
            await handleCreateInvoice(body, res);
            return;
        }

        // GET /donate/invoice/{id}
        const statusMatch = path.match(/^\/donate\/invoice\/([^/]+)$/);
        if (method === "GET" && statusMatch) {
            await handleGetStatus(statusMatch[1], res);
            return;
        }

        // GET /donate/invoice/{id}/payment/{coin}
        const paymentMatch = path.match(/^\/donate\/invoice\/([^/]+)\/payment\/([^/]+)$/);
        if (method === "GET" && paymentMatch) {
            await handleGetPayment(paymentMatch[1], paymentMatch[2], res);
            return;
        }

        // GET /donate/settled
        if (method === "GET" && path === "/donate/settled") {
            await handleSettledCheck(res);
            return;
        }

        send(res, 404, "Not found");
    } catch (e) {
        console.error(e);
        send(res, 500, "Internal error");
    }
});

server.listen(PORT, "127.0.0.1", () => {
    console.log(`Donate proxy listening on 127.0.0.1:${PORT}`);
});

// ---------------------------------------------------------------------------

async function handleCreateInvoice(body, res) {
    const amount = Number(body?.amountUsd);
    if (!Number.isInteger(amount) || amount < 1 || amount > 1000) {
        return send(res, 400, "amountUsd must be an integer 1–1000");
    }
    const resp = await btcpay("POST", `/api/v1/stores/${STORE_ID}/invoices`, {
        currency: "USD", amount,
    });
    if (!resp.ok) return send(res, 502, "Invoice creation failed");
    const data = await resp.json();
    sendJson(res, { id: data.id, checkoutLink: data.checkoutLink });
}

async function handleGetStatus(invoiceId, res) {
    if (!INVOICE_ID_RE.test(invoiceId)) return send(res, 400, "Invalid invoice ID");
    const resp = await btcpay("GET", `/api/v1/stores/${STORE_ID}/invoices/${invoiceId}`);
    if (!resp.ok) return sendJson(res, { status: "Unknown" });
    const data   = await resp.json();
    const valid  = ["New", "Processing", "Settled", "Expired", "Invalid"];
    const status = valid.includes(data.status) ? data.status : "Unknown";
    sendJson(res, { status });
}

async function handleGetPayment(invoiceId, coin, res) {
    if (!INVOICE_ID_RE.test(invoiceId)) return send(res, 400, "Invalid invoice ID");
    const coinUpper = coin.toUpperCase();
    if (!ALLOWED_COINS.has(coinUpper))  return send(res, 400, "Unsupported coin");

    const resp = await btcpay(
        "GET",
        `/api/v1/stores/${STORE_ID}/invoices/${invoiceId}/payment-methods`,
    );
    if (!resp.ok) return sendJson(res, null);

    const methods  = await resp.json();
    const methodId = coinUpper === "XMR" ? "XMR-CHAIN" : "BTC-CHAIN";
    const m        = methods.find(m => m.paymentMethodId === methodId);
    if (!m) return sendJson(res, null);

    const rawAmount = m.amount ?? m.due ?? "";
    const currency  = m.currency ?? coinUpper;
    sendJson(res, {
        address:      m.destination,
        paymentLink:  m.paymentLink ?? "",
        cryptoAmount: rawAmount ? `${rawAmount} ${currency}` : "",
    });
}

async function handleSettledCheck(res) {
    const resp = await btcpay(
        "GET",
        `/api/v1/stores/${STORE_ID}/invoices?status=Settled&take=1`,
    );
    if (!resp.ok) return sendJson(res, { settled: false });
    const data = await resp.json();
    sendJson(res, { settled: Array.isArray(data) && data.length > 0 });
}

// ---------------------------------------------------------------------------

function btcpay(method, path, body) {
    return fetch(`${BTCPAY_URL}${path}`, {
        method,
        headers: {
            Authorization:  `token ${API_KEY}`,
            "Content-Type": "application/json",
        },
        body: body ? JSON.stringify(body) : undefined,
    });
}

function readJson(req) {
    return new Promise((resolve, reject) => {
        let raw = "";
        req.on("data", chunk => { raw += chunk; });
        req.on("end", () => {
            try { resolve(JSON.parse(raw)); } catch { resolve({}); }
        });
        req.on("error", reject);
    });
}

function sendJson(res, data) {
    const body = JSON.stringify(data);
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(body);
}

function send(res, status, text) {
    res.writeHead(status, { "Content-Type": "text/plain" });
    res.end(text);
}
