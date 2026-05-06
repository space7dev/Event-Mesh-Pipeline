'use strict';

require('dotenv').config();
const express = require('express');

const app = express();
app.use(express.json({ limit: '1mb' }));

const PORT = process.env.PORT || 3001;

// Set FORCE_ERROR=true (or run `npm run start:fail`) to simulate a 5xx sink,
// which triggers the iFlow's exponential-backoff retry path and eventual DLQ.
const FORCE_ERROR = process.env.FORCE_ERROR === 'true';

const received = [];

/**
 * Primary event sink.
 * CPI iFlow POSTs the enriched payload here after:
 *   1. HMAC verification
 *   2. Schema validation
 *   3. scanTimestampCet enrichment
 */
app.post('/events/discovery', (req, res) => {
  const payload = req.body;
  const timestamp = new Date().toISOString();

  if (FORCE_ERROR) {
    console.warn(`[consumer] ${timestamp} — FORCE_ERROR active, returning 500`);
    return res.status(500).json({ error: 'Simulated sink failure for DLQ demo' });
  }

  // Validate that CPI enrichment added the required field
  if (!payload.scannedAtCet) {
    console.warn(`[consumer] ${timestamp} — scannedAtCet MISSING in payload`);
    return res.status(422).json({ error: 'Missing scannedAtCet — iFlow enrichment did not run' });
  }

  received.push({ timestamp, payload });
  console.log(`[consumer] ${timestamp} — Event received OK`);
  console.log(`[consumer] systemId:     ${payload.systemId}`);
  console.log(`[consumer] customerName: ${payload.customerName}`);
  console.log(`[consumer] scannedAt:    ${payload.scannedAt}`);
  console.log(`[consumer] scannedAtCet: ${payload.scannedAtCet}`);
  console.log(`[consumer] scopeItems:   ${payload.scopeItems?.length ?? 0} item(s)`);
  console.log(`[consumer] Full payload:\n${JSON.stringify(payload, null, 2)}`);

  res.status(200).json({ status: 'accepted', systemId: payload.systemId });
});

/**
 * DLQ sink — receives discovery.failed events forwarded by the iFlow's
 * error sub-process after exhausting all 3 retries.
 */
app.post('/events/discovery/failed', (req, res) => {
  const payload = req.body;
  console.error(`[consumer-dlq] Dead-letter event received:`, JSON.stringify(payload, null, 2));
  res.status(200).json({ status: 'dlq-accepted' });
});

/**
 * Inspect log — lets you see all received events in the browser / curl.
 */
app.get('/events', (req, res) => {
  res.json({ count: received.length, events: received });
});

app.get('/health', (_req, res) => res.json({ status: 'ok', forceError: FORCE_ERROR }));

app.listen(PORT, () => {
  console.log(`[consumer] Listening on :${PORT}  forceError=${FORCE_ERROR}`);
  console.log(`[consumer] POST /events/discovery      — primary sink`);
  console.log(`[consumer] POST /events/discovery/failed — DLQ sink`);
  console.log(`[consumer] GET  /events                — inspect log`);
});
