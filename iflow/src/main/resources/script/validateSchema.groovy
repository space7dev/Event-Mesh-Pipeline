import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonSlurper

/**
 * Step 2 — JSON schema validation (in iFlow, not in publisher per task constraint).
 *
 * Required fields for a valid discovery.completed event (Task 4 payload shape):
 *   - systemId      (String, non-blank)           e.g. "CUST-PRD-001"
 *   - customerName  (String, non-blank)            e.g. "Acme Corp"
 *   - s4Version     (String, non-blank)            e.g. "2023"
 *   - scannedAt     (String, ISO-8601 timestamp)   e.g. "2025-11-01T10:00:00Z"
 *   - scopeItems    (Array, may be empty)
 *     each item:
 *       - code         (String, non-blank)
 *       - name         (String, non-blank)
 *       - isActive     (Boolean)
 *       - customFields (Integer, >= 0)
 *
 * On validation failure the exception is caught by the error sub-process,
 * which publishes a discovery.failed event to the DLQ topic.
 * The publisher can intentionally send garbage — this step is the gatekeeper.
 */
def Message processData(Message message) {
    def body = message.getBody(String.class)

    def payload
    try {
        payload = new JsonSlurper().parseText(body)
    } catch (Exception e) {
        throw new Exception("SCHEMA_PARSE: body is not valid JSON — ${e.message}")
    }

    def errors = []

    // Top-level string fields — must be present and non-blank
    ['systemId', 'customerName', 's4Version'].each { field ->
        if (!payload[field]?.toString()?.trim()) {
            errors << "${field} is required and must be non-blank"
        }
    }

    // scannedAt — required, must look like ISO-8601
    if (!payload.scannedAt) {
        errors << 'scannedAt is required'
    } else if (!(payload.scannedAt =~ /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}/)) {
        errors << "scannedAt must be ISO-8601, got: '${payload.scannedAt}'"
    }

    // scopeItems — must be an array
    if (payload.scopeItems == null || !(payload.scopeItems instanceof List)) {
        errors << 'scopeItems must be an array'
    } else {
        payload.scopeItems.eachWithIndex { item, i ->
            if (!item.code?.toString()?.trim()) {
                errors << "scopeItems[${i}].code is required and must be non-blank"
            }
            if (!item.name?.toString()?.trim()) {
                errors << "scopeItems[${i}].name is required and must be non-blank"
            }
            if (!(item.isActive instanceof Boolean)) {
                errors << "scopeItems[${i}].isActive must be a boolean"
            }
            if (!(item.customFields instanceof Number) || item.customFields < 0) {
                errors << "scopeItems[${i}].customFields must be a non-negative integer"
            }
        }
    }

    if (errors) {
        throw new Exception("SCHEMA_INVALID: ${errors.join('; ')}")
    }

    // Stash parsed payload and key identifier for downstream steps
    message.setProperty('parsedPayload', payload)
    message.setProperty('systemId', payload.systemId as String)
    return message
}
