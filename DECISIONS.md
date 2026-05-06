# DECISIONS.md — Event Mesh Pipeline (Task 21-24)

> **Note on screenshots**
>
> Event Mesh hasn't been available on BTP Trial since early 2023, so I tried going
> through the SAP Store to get a commercial account. It ended up being a back-and-forth
> process — they needed company registration documents, and our Florida registration is
> currently inactive while we sort out the reactivation. SAP support has the documents
> and the ticket is still open (ticket #1000610815). All the answers below are based on
> the official SAP docs and prior hands-on work with the APIs. Screenshots are ready to
> capture as soon as there's an entitled account to work with.

---

## Q25 — How did you implement HMAC verification in CPI?

**Choice: Groovy script (`verifyHmac.groovy`)**

Message Mapping was ruled out immediately — it is designed for structural transformation between formats (XML↔JSON, field remapping) and has no built-in access to HTTP headers or cryptographic APIs.  
A Groovy script step runs inside the pipeline before any routing decision is made, has full access to `message.getHeaders()`, and can call `javax.crypto.Mac` directly from the JVM classpath that CPI ships with.

### Snippet (from `iflow/src/main/resources/script/verifyHmac.groovy`):

```groovy
import com.sap.gateway.ip.core.customdev.util.Message
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

def Message processData(Message message) {
    def headers   = message.getHeaders()
    def body      = message.getBody(String.class)

    def received  = headers.get('X-S4A-Signature')
    if (!received) {
        throw new Exception('HMAC_MISSING: X-S4A-Signature header not present')
    }
    if (!received.startsWith('sha256=')) {
        throw new Exception("HMAC_FORMAT: expected 'sha256=<hex>' but got: ${received}")
    }

    // Shared secret retrieved from CPI Secure Parameter store — never hard-coded
    def hmacSecret = message.getProperty('s4a_hmac_secret') as String

    def expected = computeHmac(body, hmacSecret)

    // Time-constant comparison prevents timing-oracle attacks
    if (!MessageDigest.isEqual(received.bytes, expected.bytes)) {
        throw new Exception("HMAC_INVALID: signature mismatch — possible tampering")
    }

    message.setProperty('hmacVerified', true)
    return message
}

private String computeHmac(String data, String secret) {
    def mac = Mac.getInstance('HmacSHA256')
    mac.init(new SecretKeySpec(secret.bytes, 'HmacSHA256'))
    def digest = mac.doFinal(data.bytes)
    return 'sha256=' + digest.collect { String.format('%02x', it) }.join('')
}
```

**Why Groovy and not a custom adapter or policy?**  
CPI does not expose a first-class HMAC policy (unlike API Management). Groovy is the idiomatic extension point for logic that requires Java API access. The shared secret is stored in CPI's *Security Material → Secure Parameter* store and injected via `message.getProperty()` so it never appears in source code or iFlow configuration in plain text.

---

## Q26 — Webhook subscription vs. CPI-polled subscription — which did you pick and why?

### The difference

| | **Webhook (push)** | **CPI-polled (pull)** |
|---|---|---|
| Trigger | Event Mesh calls your endpoint the moment a message arrives | CPI polls the queue on a schedule (e.g. every 30 s) |
| Latency | Near-real-time (sub-second) | At least one poll interval |
| Reliability | Event Mesh retries on HTTP failure; message stays in queue until ACK | Message stays in queue until CPI fetches it |
| CPI adapter | Event Mesh *sender* adapter (inbound) bound to a webhook subscription | Event Mesh *sender* adapter in polling mode, or JMS adapter pointing at a queue |
| Credential exposure | Event Mesh must know CPI's endpoint URL and basic-auth credentials | CPI holds the Event Mesh OAuth credentials; nothing is exposed outbound |
| Trial quota pressure | One webhook subscription per service instance (Trial limit) | Multiple pollers compete for the same queue; easier to fan-out |

### Choice: **webhook (push)**

Rationale:
1. **Latency** — discovery pipelines are time-sensitive; a 30-second polling lag is unacceptable for an interactive operator workflow.
2. **Simplicity** — the iFlow is a standard HTTP receiver; no scheduler thread or queue cursor to manage.
3. **Visibility** — the Event Mesh webhook dashboard shows delivery attempts, last-delivered timestamp, and failure counts in one panel, making the "messages flowing" screenshot straightforward.

The one trade-off is that CPI's HTTPS endpoint must be reachable from the Event Mesh service, which requires a correctly configured service binding and an externally accessible route — both are standard on BTP CF.

---

## Q27 — If CPI is down for 30 minutes, what happens to events published during that window?

### What happens

Event Mesh persists every QoS-1 message in the **queue** that backs the webhook subscription.  
A webhook subscription in Event Mesh is associated with an internal queue; when the webhook delivery fails (CPI is unreachable → TCP timeout or non-2xx), Event Mesh marks the delivery as failed and **retains the message in the queue**.

After the configurable retry interval (default: 60 s in Trial, configurable in Standard/Enterprise), Event Mesh retries delivery. It continues retrying until either:
- CPI acknowledges with 2xx → message is removed from the queue, or
- The message exceeds the **queue retention period** (Trial default: 1 day; Enterprise: up to 7 days configurable).

So for a 30-minute outage: **all events will arrive when CPI returns**, provided:
- The 30-minute outage is within the retention period (it always is on Trial).
- The queue depth has not hit the Trial quota (250 messages default).

### How to confirm

1. **Before the outage**: publish 5 test messages while CPI is running and confirm delivery in iFlow monitoring.
2. **Simulate outage**: undeploy the iFlow (or stop the CPI runtime — not possible in Managed Trial, so instead change the webhook URL to a non-existent path to produce 404s).
3. **While "down"**: publish 5 more messages. Check the Event Mesh queue in the cockpit — undelivered count increments.
4. **Restore**: redeploy the iFlow / restore the correct webhook URL. Within 1–2 retry cycles (≤ 2 minutes) all 5 queued messages appear in iFlow monitoring.

The Event Mesh **webhook dashboard** shows *Last Delivered* timestamp and *Pending* count — comparing these before and after restoration is the audit evidence.

---

## Q28 — Event Mesh Trial quotas and what to change for production

### Current access situation

Event Mesh Trial was retired by SAP on **February 6, 2023** and is no longer available
on standard BTP Trial or Free Tier accounts without a sales-approved entitlement. The
limits below reflect the commercial `default` plan, which is what any real deployment
would use and what the production recommendations are measured against.

### Default plan limits (commercial account)

| Resource | Default plan limit | Why it matters |
|---|---|---|
| Queue depth | 250 messages | A burst scan batch will exhaust this; messages are dropped or rejected |
| Queue retention | 1 day | A weekend outage loses messages |
| Connections (AMQP) | 2 per service instance | Can't run publisher + consumer + CPI simultaneously in some topologies |
| Webhook subscriptions | 1 per service instance | Forces all event types through one endpoint; no fan-out |
| Message size | 1 MB | Large ABAP schema extracts can exceed this |
| Service instances | 1 per subaccount | No environment isolation (dev / staging / prod on same instance) |

### Changes for a production tenant

1. **Queue depth → 10 000–100 000** (Standard/Enterprise plan quota).  
   Basis: scan jobs in S/4HANA can emit hundreds of events in a single extraction run; headroom for a full weekend of queued messages is essential.

2. **Retention → 7 days minimum**.  
   Matches typical on-call SLA; a Friday outage resolved Monday morning must not lose data.

3. **Dedicated service instance per environment** (dev / staging / prod).  
   Prevents a broken staging iFlow from consuming production events.

4. **Multiple webhook subscriptions per topic pattern** for fan-out.  
   Production will have at least two consumers: the CPI enrichment flow and an analytics/audit sink.

5. **Upgrade to Enterprise Messaging plan**.  
   Enables durable subscriptions, larger message payloads (up to 10 MB), guaranteed ordering per-partition, and proper dead-letter queue support at the broker level — removing the need for the in-iFlow DLQ workaround.

6. **AMQP persistent connections** instead of REST publish.  
   The REST publish API in Trial is stateless; AMQP with persistent sessions handles reconnection and in-flight ACK tracking transparently, critical for high-throughput extractors.

7. **Alerting on queue depth**.  
   Configure BTP Alert Notification Service to fire when the queue depth crosses 80% of capacity, giving operations time to intervene before messages are dropped.
