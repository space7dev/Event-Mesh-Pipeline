# S4A Discovery Event Pipeline

End-to-end event pipeline: ABAP extractor → Event Mesh → CPI enrichment → sink.

> **Status — screenshots pending**
>
> All the code is done and works locally. The only missing piece is the live screenshots,
> which need a real Event Mesh instance to produce.
>
> SAP removed Event Mesh from BTP Trial back in early 2023, so I went through the SAP
> Store to get a commercial account. That turned into a longer process than expected —
> they needed company registration documents, and our Florida registration currently
> shows as inactive while we're reactivating it with the state. The ticket is still open
> with SAP support (ticket #1000610815) and the documents have been forwarded to their
> provisioning team.
>
> If you're able to share access to a BTP subaccount that already has Event Mesh
> entitled, I can have the iFlow deployed and all three screenshots captured within
> about 30 minutes.

```
publisher  ──[AMQP/REST]──▶  Event Mesh  ──[webhook push]──▶  CPI iFlow  ──[HTTP POST]──▶  consumer
                                 │                                  │
                           s4a/{tenant}/                     verifyHmac
                           discovery/completed                validateSchema
                                                             enrichPayload (UTC→CET)
                                                                  │ 5xx / invalid
                                                                  ▼
                                                           3× exponential backoff
                                                           (1s, 2s, 4s)
                                                                  │ still failing
                                                                  ▼
                                                   s4a/{tenant}/discovery/failed  (DLQ)
```

**Topic naming contract**: `s4a/{tenant_id}/discovery/{event_type}`

---

## Repository layout

```
.
├── publisher/               Node.js event publisher
│   ├── package.json
│   ├── .env.example
│   └── src/
│       ├── index.js         CLI entry-point (--mode valid|invalid|bad-sink)
│       ├── publisher.js     OAuth token fetch + Event Mesh REST publish
│       └── hmac.js          HMAC-SHA256 signing (X-S4A-Signature header)
│
├── consumer/                Express webhook sink
│   ├── package.json
│   ├── .env.example
│   └── src/
│       └── server.js        POST /events/discovery   (primary)
│                            POST /events/discovery/failed  (DLQ sink)
│                            GET  /events             (inspect log)
│
├── iflow/                   CPI iFlow source (importable via Integration Suite)
│   ├── META-INF/MANIFEST.MF
│   └── src/main/resources/
│       ├── scenarioflows/integrationflow/
│       │   └── DiscoveryEventFlow.iflw   BPMN 2.0 iFlow definition
│       └── script/
│           ├── verifyHmac.groovy         Step 1 — HMAC-SHA256 verification
│           ├── validateSchema.groovy     Step 2 — JSON schema gate
│           ├── enrichPayload.groovy      Step 3 — UTC → Europe/Berlin conversion
│           ├── retryWithBackoff.groovy   Step 4 — exponential backoff controller
│           └── publishToDlq.groovy       Step 5 — DLQ payload builder
│
├── DiscoveryEventFlow.zip   Ready-to-import iFlow package (git-tracked binary)
├── DECISIONS.md             Answers to the 4 architecture questions
└── README.md                This file
```

---

## Prerequisites

| Tool | Version / Notes |
|------|---------|
| Node.js | ≥ 18 |
| SAP BTP **commercial** account | Event Mesh is not available on BTP Trial (retired Feb 2023). Free Tier enterprise account works — but the "Subscribe" button is replaced by "Contact Us" for Event Mesh; a sales-approved entitlement is required. |
| SAP Event Mesh | Service instance on the commercial account, plan `default` |
| SAP Integration Suite | Trial or commercial (CPI runtime active) |

---

## 1 — Event Mesh setup

> **Note:** Event Mesh requires a commercial BTP account. If the service does not appear
> in Service Marketplace, or only shows "Contact Us" instead of "Create", the subaccount
> does not have the entitlement. Go to **BTP cockpit → Entitlements → Configure Entitlements**,
> add **Event Mesh → default**, save, then return to Service Marketplace.

1. In BTP cockpit → Service Marketplace → **Event Mesh** → create instance (plan: `default`).
2. Create a **service key** and download the JSON. Note these fields:
   - `messaging[].broker.type === "saprestmsgbroker"` → copy `uri`, `oa2.clientid`, `oa2.clientsecret`, `oa2.tokenendpoint`
3. In the Event Mesh application UI → **Message Clients** → open your client.
4. Create two **queues**:
   - `s4a/tenant001/discovery/completed`
   - `s4a/tenant001/discovery/failed`  *(DLQ)*
5. Create a **topic subscription** on each queue matching the queue name (wildcards: `s4a/tenant001/discovery/*` covers both).
6. Create a **webhook** on the `completed` queue pointing at:
   `https://<your-cpi-tenant>.cfapps.<region>.hana.ondemand.com/http/discovery/events`
   (this is the CPI iFlow HTTP sender URL — configure after deploying the iFlow).

---

## 2 — CPI iFlow setup

### Import

1. Integration Suite → **Design** → your package → **Import** → upload `DiscoveryEventFlow.zip`.
2. Open the iFlow and configure **externalized parameters**:
   | Parameter | Value |
   |-----------|-------|
   | `sink_url` | Base URL of your consumer service |
3. In **Security Material** → add:
   | Type | Name | Value |
   |------|------|-------|
   | Secure Parameter | `s4a_hmac_secret` | Same value as publisher `HMAC_SECRET` |
   | User Credentials | `s4a_sink_credentials` | Consumer basic-auth user/pass |
   | OAuth2 Client Credentials | `s4a_eventmesh_credentials` | Event Mesh client-id / secret |
4. **Deploy** the iFlow.

### iFlow flow summary

```
[EM webhook] → verifyHmac → validateSchema → enrichPayload → [HTTP POST sink]
                                                                    │ 5xx
                                                       retryWithBackoff (×3, 1s/2s/4s)
                                                                    │ exhausted
                                                              publishToDlq
                                                                    │
                                                    [EM publish → discovery/failed]
```

HMAC / schema failures skip retries and go directly to DLQ (permanent failures, not transient).

---

## 3 — Publisher setup

```bash
cd publisher
npm install
cp .env.example .env
# Fill in .env with Event Mesh credentials
```

### Run modes

```bash
# 1. Valid event — happy path through iFlow → arrives at consumer with scanTimestampCet
npm run publish:valid

# 2. Invalid event — missing required fields → iFlow rejects, routes to DLQ
npm run publish:invalid

# 3. Force sink failure — valid payload but consumer returns 500
#    → iFlow retries 3× (1s, 2s, 4s) → publishes to DLQ
npm run publish:bad-sink
```

**Payload shape** (exact Task 4 shape):

```json
{
  "systemId": "CUST-PRD-001",
  "customerName": "Acme Corp",
  "s4Version": "2023",
  "scannedAt": "2025-11-01T10:00:00Z",
  "scopeItems": [
    { "code": "J45", "name": "Procurement of Direct Materials", "isActive": true,  "customFields": 4  },
    { "code": "B09", "name": "Sell from Stock",                 "isActive": true,  "customFields": 0  },
    { "code": "2TX", "name": "Central Procurement",             "isActive": false, "customFields": 12 }
  ]
}
```

The publisher adds `X-S4A-Signature: sha256=<hex>` to every request.

---

## 4 — Consumer setup

```bash
cd consumer
npm install
cp .env.example .env

# Normal mode (returns 200 — happy path)
npm start

# Failure mode (returns 500 — triggers retry + DLQ path)
npm run start:fail
```

Inspect received events:
```bash
curl http://localhost:3001/events | jq .
```

Enriched payload arriving at the sink will contain the added field:
```json
{
  "systemId": "CUST-PRD-001",
  "customerName": "Acme Corp",
  "s4Version": "2023",
  "scannedAt": "2025-11-01T10:00:00Z",
  "scopeItems": [ "..." ],
  "scannedAtCet": "2025-11-01T11:00:00+01:00",
  "enrichedBy": "cpi-discovery-flow",
  "enrichedAt": "2025-11-01T10:00:02Z"
}
```

`scannedAt` `2025-11-01T10:00:00Z` → `scannedAtCet` `2025-11-01T11:00:00+01:00` because November is CET (UTC+1), not CEST.

---

## 5 — Demo script (3 required screenshots)

> **Blocker:** Steps below require a live Event Mesh instance and a deployed CPI iFlow.
> All publisher and consumer code is ready. Screenshots are pending access to a
> commercial BTP account. See the status note at the top of this file.

### Screenshot 1 — 1 success

```bash
# Terminal A: start consumer in normal mode
cd consumer && npm start

# Terminal B: publish valid event
cd publisher && npm run publish:valid
```

Expected: consumer logs `scanTimestampCet` present; CPI monitoring shows green.

### Screenshot 2 — 1 schema-invalid → DLQ

```bash
cd publisher && npm run publish:invalid
```

Expected: CPI monitoring shows red (SCHEMA_INVALID exception); DLQ queue depth +1.

### Screenshot 3 — 1 forcing 500 → 3 retries → DLQ

```bash
# Terminal A: start consumer in failure mode
cd consumer && npm run start:fail

# Terminal B: publish event (valid payload, sink returns 500)
cd publisher && npm run publish:bad-sink
```

Expected: CPI monitoring shows 3 retry steps with delays (1s, 2s, 4s), then DLQ message; consumer logs show 4 × 500 responses (initial + 3 retries).

---

## HMAC signing — how it works

The publisher calls `crypto.createHmac('sha256', HMAC_SECRET)` over the raw JSON body and adds the result as:

```
X-S4A-Signature: sha256=<hex-digest>
```

The iFlow's first step (`verifyHmac.groovy`) recomputes the HMAC using the shared secret from CPI Secure Parameter store and uses `MessageDigest.isEqual()` (time-constant) for comparison. Any mismatch throws an exception that routes to DLQ.

---

## Hard constraint checklist

| Constraint | Where it is implemented |
|-----------|------------------------|
| Topic naming `s4a/{tenant_id}/discovery/{event_type}` | `publisher/src/index.js` L16; `publishToDlq.groovy` |
| Payload shape matches Task 4 | `publisher/src/index.js` `buildValidPayload()` — `systemId`, `customerName`, `s4Version`, `scannedAt`, `scopeItems` |
| Schema validation in CPI, not publisher | `validateSchema.groovy` — publisher's invalid mode sends malformed data that this script catches |
| Enrichment field is `scannedAtCet` (UTC → Europe/Berlin) | `enrichPayload.groovy`; consumer checks for its presence |
| Exponential backoff in iFlow (not external) | `retryWithBackoff.groovy` + `SubProcess_RetryDLQ` in `.iflw` |
| HMAC in `X-S4A-Signature`, verified in iFlow | `hmac.js` (sign); `verifyHmac.groovy` (verify) |
