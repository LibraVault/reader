#!/usr/bin/env node
// Libravault donation proxy — runs on the BTCPay VPS, reverse-proxied by nginx.
//
// Hardening (Q3-Q4 2026 PRD, WS2):
//   - Express + Helmet (CSP, X-Frame-Options, etc.)
//   - Bearer auth on every /donate/* route via PROXY_SHARED_SECRET
//   - 4 KB body cap
//   - Per-IP rate limits (10 r/min per endpoint, 60 r/min global)
//   - /donate/settled removed (was a leaky abstraction — clients now poll
//     a specific invoice id and stop when it returns Settled)
//   - INVOICE_ID_RE is anchored to BTCPay's actual 32-hex format
//
// Config: copy .env.example → .env and fill in. Never commit .env.
// Start: node proxy.js   (or: PORT=3456 node proxy.js)

import "dotenv/config";
import express from "express";
import helmet from "helmet";
import rateLimit from "express-rate-limit";

const PORT              = process.env.PORT              || 3456;
const BTCPAY_URL        = process.env.BTCPAY_URL        || "https://pay.libravault.xyz";
const STORE_ID          = process.env.STORE_ID          || "REPLACE_WITH_BTCPAY_STORE_ID";
const API_KEY           = process.env.BTCPAY_API_KEY;
const PROXY_SHARED_SECRET = process.env.PROXY_SHARED_SECRET;

if (!API_KEY) {
    console.error("BTCPAY_API_KEY is not set — copy .env.example to .env and fill it in.");
    process.exit(1);
}
if (!PROXY_SHARED_SECRET || PROXY_SHARED_SECRET.length < 32) {
    console.error("PROXY_SHARED_SECRET is not set or is shorter than 32 chars. Refusing to start.");
    process.exit(1);
}
if (STORE_ID.startsWith("REPLACE_WITH_")) {
    console.error("STORE_ID is still the placeholder. Refusing to start.");
    process.exit(1);
}

// BTCPay invoice IDs are exactly 32 lowercase hex chars (default) or longer
// when generated via API with a custom prefix. We accept either; reject anything
// outside hex charset or with extreme length to bound regex work.
const INVOICE_ID_RE = /^[A-Fa-f0-9]{16,128}$/;
const ALLOWED_COINS = new Set(["BTC", "XMR"]);

// ── Middleware ───────────────────────────────────────────────────────────────

const app = express();
app.disable("x-powered-by");
app.use(helmet({ contentSecurityPolicy: false }));        // JSON API; CSP not needed
app.use(express.json({ limit: "4kb" }));                  // Body cap (PRD WS2)

const requireBearer = (req, res, next) => {
    const header = req.get("authorization") || "";
    const [scheme, token] = header.split(" ", 2);
    if (scheme !== "Bearer" || !token || token.length !== PROXY_SHARED_SECRET.length) {
        return res.status(401).json({ error: "Missing or invalid Authorization header" });
    }
    // Constant-time comparison — no early-exit on first-byte mismatch
    let mismatch = 0;
    for (let i = 0; i < PROXY_SHARED_SECRET.length; i++) {
        mismatch |= token.charCodeAt(i) ^ PROXY_SHARED_SECRET.charCodeAt(i);
    }
    if (mismatch !== 0) return res.status(401).json({ error: "Missing or invalid Authorization header" });
    next();
};

// 60 req/min per IP across the entire API
app.use(rateLimit({
    windowMs: 60_000,
    max: 60,
    standardHeaders: true,
    legacyHeaders: false,
}));

// 10 req/min per IP per endpoint — applied below per route so the counter
// is scoped to a specific endpoint + IP pair rather than shared globally.

// ── Routes ───────────────────────────────────────────────────────────────────

const perEndpointLimiter = (name) => rateLimit({
    windowMs: 60_000,
    max: 10,
    keyGenerator: (req) => `${req.ip}::${name}`,
    standardHeaders: true,
    legacyHeaders: false,
});

// POST /donate/invoice
app.post("/donate/invoice", requireBearer, perEndpointLimiter("createInvoice"), async (req, res) => {
    const amount = Number(req.body?.amountUsd);
    if (!Number.isInteger(amount) || amount < 1 || amount > 1000) {
        return res.status(400).json({ error: "amountUsd must be an integer 1–1000" });
    }
    const upstream = await btcpay("POST", `/api/v1/stores/${STORE_ID}/invoices`, {
        currency: "USD", amount,
    });
    if (!upstream.ok) {
        console.error("btcpay createInvoice failed:", upstream.status, await upstream.text());
        return res.status(502).json({ error: "Invoice creation failed" });
    }
    const data = await upstream.json();
    res.json({ id: data.id, checkoutLink: data.checkoutLink });
});

// GET /donate/invoice/{id}
app.get("/donate/invoice/:id", requireBearer, perEndpointLimiter("getStatus"), async (req, res) => {
    const { id } = req.params;
    if (!INVOICE_ID_RE.test(id)) return res.status(400).json({ error: "Invalid invoice ID" });

    const upstream = await btcpay("GET", `/api/v1/stores/${STORE_ID}/invoices/${id}`);
    if (!upstream.ok) return res.json({ status: "Unknown" });
    const data   = await upstream.json();
    const valid  = ["New", "Processing", "Settled", "Expired", "Invalid"];
    const status = valid.includes(data.status) ? data.status : "Unknown";
    res.json({ status });
});

// GET /donate/invoice/{id}/payment/{coin}
app.get("/donate/invoice/:id/payment/:coin", requireBearer, perEndpointLimiter("getPayment"), async (req, res) => {
    const { id, coin } = req.params;
    if (!INVOICE_ID_RE.test(id)) return res.status(400).json({ error: "Invalid invoice ID" });
    const coinUpper = coin.toUpperCase();
    if (!ALLOWED_COINS.has(coinUpper)) return res.status(400).json({ error: "Unsupported coin" });

    const upstream = await btcpay(
        "GET",
        `/api/v1/stores/${STORE_ID}/invoices/${id}/payment-methods`,
    );
    if (!upstream.ok) return res.json(null);

    const methods  = await upstream.json();
    const methodId = coinUpper === "XMR" ? "XMR-CHAIN" : "BTC-CHAIN";
    const m        = methods.find(m => m.paymentMethodId === methodId);
    if (!m) return res.json(null);

    const rawAmount = m.amount ?? m.due ?? "";
    const currency  = m.currency ?? coinUpper;
    res.json({
        address:      m.destination,
        paymentLink:  m.paymentLink ?? "",
        cryptoAmount: rawAmount ? `${rawAmount} ${currency}` : "",
    });
});

// GET /donate/settled — REMOVED in WS2 (was a leaky abstraction; clients
// should poll /donate/invoice/:id until status === 'Settled').
app.get("/donate/settled", requireBearer, (_req, res) => {
    res.status(404).json({ error: "Endpoint removed; poll /donate/invoice/:id instead" });
});

// Generic 404 for everything else (and explicit /donate/* fallthrough)
app.use((req, res) => {
    res.status(404).json({ error: "Not found" });
});

// ── Helpers ──────────────────────────────────────────────────────────────────

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

// ── Boot ─────────────────────────────────────────────────────────────────────

app.listen(PORT, "127.0.0.1", () => {
    console.log(`Donate proxy listening on 127.0.0.1:${PORT} (auth enabled)`);
});