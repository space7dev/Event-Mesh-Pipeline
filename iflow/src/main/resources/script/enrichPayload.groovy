import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonOutput
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Step 3 — Payload enrichment.
 *
 * Converts scannedAt (ISO-8601 UTC) → scannedAtCet (Europe/Berlin).
 * Europe/Berlin automatically handles CET (UTC+1) / CEST (UTC+2) transitions.
 *
 * The enriched JSON replaces the message body so the HTTP sender adapter
 * forwards the augmented payload to the sink.
 */
def Message processData(Message message) {
    def payload = message.getProperty('parsedPayload') as Map

    def utcStr   = payload.scannedAt as String
    def utcDt    = ZonedDateTime.parse(utcStr, DateTimeFormatter.ISO_DATE_TIME)
    def berlinDt = utcDt.withZoneSameInstant(ZoneId.of('Europe/Berlin'))
    def cetStr   = berlinDt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    payload.scannedAtCet = cetStr
    payload.enrichedBy   = 'cpi-discovery-flow'
    payload.enrichedAt   = ZonedDateTime.now(ZoneId.of('UTC'))
                                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    message.setBody(JsonOutput.toJson(payload))
    message.setHeader('Content-Type', 'application/json')
    return message
}
