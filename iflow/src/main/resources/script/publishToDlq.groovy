import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonOutput
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Publishes a 'discovery.failed' event to the DLQ topic after all retries
 * are exhausted (or after a schema/HMAC validation failure).
 *
 * DLQ topic naming follows the same contract:
 *   s4a/{tenant_id}/discovery/failed
 *
 * The CPI Event Mesh sender adapter is pointed at this topic.
 * This script only builds the DLQ payload; the actual publish happens via
 * the subsequent Event Mesh outbound adapter step in the iFlow.
 */
def Message processData(Message message) {
    def props       = message.getProperties()
    // systemId is set by validateSchema.groovy on the happy path;
    // on HMAC failure it won't be set yet, so fall back to 'unknown'
    def systemId    = props.get('systemId')  ?: 'unknown'
    def errorReason = props.get('lastErrorMessage') ?: props.get('CamelExceptionCaught')?.message ?: 'unknown'
    def retryCount  = props.get('retryCount') ?: '0'

    // tenantId comes from the iFlow externalized parameter (same value used in topic path)
    def tenantId    = props.get('tenantId')  ?: System.getenv('TENANT_ID') ?: 'unknown'

    def dlqPayload = [
        eventType        : 'discovery.failed',
        systemId         : systemId,
        failedAt         : ZonedDateTime.now(ZoneId.of('UTC'))
                                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        reason           : errorReason,
        retriesAttempted : retryCount.toInteger(),
        originalBody     : message.getBody(String.class),
    ]

    // DLQ topic: s4a/{tenant_id}/discovery/failed
    def dlqTopic = "s4a/${tenantId}/discovery/failed"
    message.setProperty('dlqTopic', dlqTopic)

    message.setBody(JsonOutput.toJson(dlqPayload))
    message.setHeader('Content-Type', 'application/json')

    println "[publishToDlq] systemId=${systemId} topic=${dlqTopic} retries=${retryCount} reason=${errorReason}"
    return message
}
