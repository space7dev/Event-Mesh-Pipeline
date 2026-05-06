'use strict';

const axios = require('axios');
const { sign } = require('./hmac');

/**
 * Fetches an OAuth2 client-credentials token from Event Mesh UAA.
 */
async function getToken(tokenUrl, clientId, clientSecret) {
  const params = new URLSearchParams({
    grant_type: 'client_credentials',
    client_id: clientId,
    client_secret: clientSecret,
  });
  const res = await axios.post(tokenUrl, params.toString(), {
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  });
  return res.data.access_token;
}

/**
 * Publishes a message to an Event Mesh topic via the REST API.
 *
 * Topic naming contract: s4a/{tenant_id}/discovery/{event_type}
 * Event Mesh REST endpoint: POST /messagingrest/v1/topics/{topic}/messages
 *
 * The topic segment is URL-encoded because '/' is not allowed raw in a URL path
 * segment — SAP EM accepts the percent-encoded form %2F.
 */
async function publishToTopic(baseUrl, token, topic, payload, hmacSecret) {
  const body = JSON.stringify(payload);
  const signature = sign(body, hmacSecret);

  // Encode '/' inside the topic name so it sits in a single path segment
  const encodedTopic = encodeURIComponent(topic);
  const url = `${baseUrl}/messagingrest/v1/topics/${encodedTopic}/messages`;

  const res = await axios.post(url, body, {
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
      'x-qos': '1',                // QoS-1 → at-least-once delivery
      'X-S4A-Signature': signature, // HMAC verification in iFlow
    },
  });

  return { status: res.status, signature, topic };
}

module.exports = { getToken, publishToTopic };
