'use strict';

require('dotenv').config();
const { getToken, publishToTopic } = require('./publisher');

const {
  EM_TOKEN_URL,
  EM_CLIENT_ID,
  EM_CLIENT_SECRET,
  EM_BASE_URL,
  TENANT_ID = 'tenant001',
  HMAC_SECRET,
} = process.env;

// Topic contract: s4a/{tenant_id}/discovery/{event_type}
const TOPIC_COMPLETED = `s4a/${TENANT_ID}/discovery/completed`;

/**
 * Canonical discovery.completed payload — exact shape from Task 4.
 * CPI iFlow adds scannedAtCet (UTC → Europe/Berlin) via enrichment step.
 */
function buildValidPayload() {
  return {
    systemId: 'CUST-PRD-001',
    customerName: 'Acme Corp',
    s4Version: '2023',
    scannedAt: new Date().toISOString(),
    scopeItems: [
      { code: 'J45', name: 'Procurement of Direct Materials', isActive: true,  customFields: 4  },
      { code: 'B09', name: 'Sell from Stock',                 isActive: true,  customFields: 0  },
      { code: '2TX', name: 'Central Procurement',             isActive: false, customFields: 12 },
    ],
  };
}

/**
 * Deliberately malformed payload — missing required fields.
 * The iFlow schema validation must reject this and route to DLQ.
 * Publisher intentionally sends garbage per the task constraints.
 */
function buildInvalidPayload() {
  return {
    systemId: 'CUST-PRD-001',
    // customerName missing — required string
    s4Version: '',            // blank — required non-blank string
    scannedAt: 'not-a-date',  // invalid ISO-8601
    scopeItems: 'wrong-type', // must be an array
  };
}

const MODES = {
  valid: {
    description: 'Valid payload → iFlow enriches and forwards to sink',
    payload: buildValidPayload(),
  },
  invalid: {
    description: 'Invalid payload → iFlow rejects, routes to DLQ',
    payload: buildInvalidPayload(),
  },
  'bad-sink': {
    description: 'Valid payload but sink will return 5xx → 3 retries → DLQ',
    payload: { ...buildValidPayload(), _forceSinkError: true },
  },
};

async function main() {
  const mode = (process.argv[2] || '--mode=valid').replace('--mode=', '').replace('--mode', '');
  const selected = MODES[mode] || MODES.valid;

  console.log(`[publisher] mode=${mode} — ${selected.description}`);
  console.log(`[publisher] topic=${TOPIC_COMPLETED}`);

  if (!EM_TOKEN_URL || !EM_CLIENT_ID || !EM_CLIENT_SECRET || !EM_BASE_URL) {
    console.error('[publisher] Missing Event Mesh credentials. Copy .env.example → .env and fill in values.');
    process.exit(1);
  }
  if (!HMAC_SECRET) {
    console.error('[publisher] HMAC_SECRET is required. Set it in .env.');
    process.exit(1);
  }

  const token = await getToken(EM_TOKEN_URL, EM_CLIENT_ID, EM_CLIENT_SECRET);
  console.log('[publisher] OAuth token obtained');

  const result = await publishToTopic(EM_BASE_URL, token, TOPIC_COMPLETED, selected.payload, HMAC_SECRET);

  console.log(`[publisher] Published → HTTP ${result.status}`);
  console.log(`[publisher] X-S4A-Signature: ${result.signature}`);
  console.log('[publisher] Payload:', JSON.stringify(selected.payload, null, 2));
}

main().catch((err) => {
  console.error('[publisher] Fatal:', err.message);
  if (err.response) {
    console.error('[publisher] Response:', err.response.status, err.response.data);
  }
  process.exit(1);
});
