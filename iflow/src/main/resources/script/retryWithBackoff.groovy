import com.sap.gateway.ip.core.customdev.util.Message

/**
 * Exponential-backoff retry controller — called at the start of the
 * Error Sub-Process that catches HTTP 5xx from the sink.
 *
 * Retry schedule (visible in iFlow BPMN via property-driven wait steps):
 *   Attempt 1 → sleep 1 000 ms
 *   Attempt 2 → sleep 2 000 ms
 *   Attempt 3 → sleep 4 000 ms
 *   After attempt 3 → route to DLQ publisher step
 *
 * Design note: Thread.sleep() is acceptable inside a CPI Groovy step because
 * CPI worker threads are isolated per message instance.  For production scale
 * prefer a Timer Intermediate Event or an external retry queue, but Trial quota
 * limits make in-process sleep the practical choice here.
 *
 * The iFlow routes to this script via an Exception Sub-Process connected to
 * the HTTP Request step.  The router after this script checks retryCount:
 *   retryCount < 3  → loop back to HTTP Request
 *   retryCount >= 3 → proceed to publishToDlq step
 */
def Message processData(Message message) {
    def props       = message.getProperties()
    int retryCount  = (props.get('retryCount') ?: '0').toInteger()

    if (retryCount >= 3) {
        // Signal downstream router to take the DLQ branch
        message.setProperty('maxRetriesExceeded', 'true')
        return message
    }

    // Compute delay: 1s → 2s → 4s  (2^retryCount * 1000 ms)
    long delayMs = (long) Math.pow(2, retryCount) * 1000L
    message.setProperty('retryDelayMs', delayMs as String)
    message.setProperty('retryCount',   (retryCount + 1) as String)

    def originalError = props.get('CamelExceptionCaught')?.message ?: 'unknown'
    message.setProperty('lastErrorMessage', originalError)

    println "[retryWithBackoff] attempt=${retryCount + 1}/3  delayMs=${delayMs}  error=${originalError}"
    Thread.sleep(delayMs)

    message.setProperty('maxRetriesExceeded', 'false')
    return message
}
