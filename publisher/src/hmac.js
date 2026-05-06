'use strict';

const crypto = require('crypto');

/**
 * Signs a string body with HMAC-SHA256 using the shared secret.
 * Header name: X-S4A-Signature
 * Format:      sha256=<hex-digest>
 *
 * CPI iFlow replicates this exact computation in verifyHmac.groovy.
 */
function sign(body, secret) {
  const hmac = crypto.createHmac('sha256', secret);
  hmac.update(typeof body === 'string' ? body : JSON.stringify(body));
  return `sha256=${hmac.digest('hex')}`;
}

module.exports = { sign };
