import com.sap.gateway.ip.core.customdev.util.Message
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

/**
 * Step 1 — HMAC-SHA256 verification.
 *
 * Replicates the publisher's sign() function exactly:
 *   signature = "sha256=" + HEX( HMAC-SHA256(rawBody, sharedSecret) )
 *
 * The shared secret is stored as a Secure Parameter named "s4a_hmac_secret"
 * in CPI's Security Material store — never hard-coded.
 *
 * Throws an exception (caught by the error sub-process → DLQ) if:
 *   - X-S4A-Signature header is absent
 *   - signature prefix is not "sha256="
 *   - computed digest does not time-constant-equal the provided digest
 */
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

    // Retrieve shared secret from CPI Secure Parameter store
    def hmacSecret = message.getProperty('s4a_hmac_secret') as String
    if (!hmacSecret) {
        throw new Exception('HMAC_CONFIG: s4a_hmac_secret secure parameter not configured')
    }

    def expected = computeHmac(body, hmacSecret)

    // Time-constant comparison — prevents timing attacks
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
