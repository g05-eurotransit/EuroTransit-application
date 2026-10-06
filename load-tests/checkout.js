import http from 'k6/http';
import { sleep } from 'k6';
import execution from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { validateFixtures, terminalOutcome, integer } from './config.mjs';

const scenario = __ENV.SCENARIO || 'baseline';
if (!['baseline', 'ramp', 'spike', 'contention', 'request-replay'].includes(scenario)) throw new Error('Unsupported SCENARIO');
const attempts = integer(__ENV.MAX_ATTEMPTS, 20, 1, 10000, 'MAX_ATTEMPTS');
const vus = integer(__ENV.MAX_VUS, 2, 1, 4, 'MAX_VUS');
const pollDeadline = integer(__ENV.POLL_TIMEOUT_SECONDS, 30, 1, 300, 'POLL_TIMEOUT_SECONDS');
const runId = __ENV.RUN_ID;
if (!runId || !/^[a-zA-Z0-9_-]{1,64}$/.test(runId)) throw new Error('RUN_ID must be a unique safe identifier');
const publicUrl = __ENV.PUBLIC_URL;
const fixtures = validateFixtures(JSON.parse(open(__ENV.FIXTURES_FILE)), scenario === 'contention' ? 1 : attempts);
const failures = new Counter('checkout_assertion_failures');
const accepted = new Counter('checkout_accepted');
const confirmed = new Counter('checkout_confirmed');
const acceptance = new Trend('checkout_acceptance_ms', true);
const observed = new Trend('checkout_observed_confirmation_ms', true);
const refusal = new Counter('checkout_overload_refusals');
const maxDuration = `${attempts * (pollDeadline + 30) + 90}s`;
const outputDir = __ENV.OUTPUT_DIR || '/results';
export const options = {
  insecureSkipTLSVerify: __ENV.ALLOW_INSECURE_TLS === 'true',
  scenarios: {
    checkout: { executor: 'shared-iterations', vus: scenario === 'baseline' ? 1 : vus, iterations: attempts, maxDuration, exec: 'checkout' },
  },
  thresholds: { checkout_assertion_failures: ['count==0'] },
};
function emit(type, fields) { console.log(`ET_RESULT ${JSON.stringify({ type, runId, ...fields })}`); }
function fail(reason) { failures.add(1); emit('assertion_failure', { reason }); }
let cachedToken;
let expiresAt = 0;
function headers() {
  if (!cachedToken || Date.now() >= expiresAt) {
    const r = http.post(__ENV.TOKEN_URL, { grant_type: 'client_credentials', client_id: __ENV.CLIENT_ID, client_secret: __ENV.CLIENT_SECRET }, { timeout: '10s', tags: { operation: 'auth' } });
    if (r.status !== 200) { fail('authentication_failed'); throw new Error('Authentication failed'); }
    cachedToken = r.json('access_token');
    expiresAt = Date.now() + Math.max(1, Number(r.json('expires_in') || 30) - 10) * 1000;
    if (!cachedToken) throw new Error('Missing access token');
  }
  return { Authorization: `Bearer ${cachedToken}`, 'Content-Type': 'application/json' };
}
export function setup() {
  failures.add(0);
  emit('run_started', { scenario, maxAttempts: attempts, maxVus: vus });
  const h = headers();
  const offers = http.get(`${publicUrl}/api/catalog/offers`, { headers: h, timeout: '10s' });
  if (offers.status !== 200) throw new Error('Catalog preflight failed');
  const uniqueDates = new Set();
  for (const f of fixtures.slice(0, scenario === 'contention' ? 1 : attempts)) {
    if (!offers.json().some(o => o.offerId === f.offerId && o.routeId === f.routeId && Number(o.price) === Number(f.unitPrice))) throw new Error('Fixture offer or price does not match Catalog');
    const key = `${f.routeId}/${f.travelDate}`;
    if (!uniqueDates.has(key)) {
      const r = http.get(`${publicUrl}/api/orders/seat-availability?routeId=${encodeURIComponent(f.routeId)}&travelDate=${f.travelDate}`, { headers: h, timeout: '10s' });
      if (r.status !== 200) throw new Error('Fixture availability unavailable');
      const requested = fixtures.filter(v => v.routeId === f.routeId && v.travelDate === f.travelDate);
      if (!requested.every(v => r.json('seats').some(s => s.seatId === v.seatId && s.available))) throw new Error('Fixture already occupied');
      uniqueDates.add(key);
    }
  }
}
export function catalog() {
  const r = http.get(`${publicUrl}/api/catalog/routes`, { timeout: '10s', tags: { operation: 'catalog' } });
  if (r.status !== 200) fail('catalog_unavailable');

}
export function checkout() {
  catalog();
  const i = execution.scenario.iterationInTest;
  const fixture = fixtures[scenario === 'contention' ? 0 : i];
  const key = `${runId}-${i}`;
  const correlationId = key;
  const h = { ...headers(), 'Idempotency-Key': key, 'X-Correlation-Id': correlationId };
  const body = JSON.stringify({ routeId: fixture.routeId, offerId: fixture.offerId, travelDate: fixture.travelDate, seats: [fixture.seatId], totalAmount: Number(fixture.unitPrice), paymentMethodToken: __ENV.PAYMENT_METHOD_TOKEN || 'tok_visa' });
  const started = Date.now();
  const r = http.post(`${publicUrl}/api/orders`, body, { headers: h, timeout: '10s', tags: { operation: 'checkout_accept' } });
  const phase = ['ramp', 'spike'].includes(scenario) && i >= attempts * (scenario === 'ramp' ? 2 / 3 : 1 / 2) ? 'recovery' : 'load';
  const result = { scenario, phase, iteration: i, fixture, idempotencyKey: key, correlationId, httpStatus: r.status, acceptanceMs: r.timings.duration, outcome: 'technical_failure' };
  acceptance.add(result.acceptanceMs);
  if (r.status === 429 && ['ramp', 'spike'].includes(scenario)) {
    if (r.json('code') !== 'CHECKOUT_OVERLOADED' || !r.headers['Retry-After']) fail('uncontrolled_overload_response');
    refusal.add(1); result.outcome = 'overload_refusal';
  }
  else if (r.status !== 202) fail('checkout_not_accepted');
  else {
    accepted.add(1);
    result.orderId = r.json('orderId');
    if (!result.orderId) fail('missing_order_id');
    else {
      if (scenario === 'request-replay') {
        const replay = http.post(`${publicUrl}/api/orders`, body, { headers: h, timeout: '10s' });
        if (replay.status !== 202 || replay.json('orderId') !== result.orderId) fail('replay_identity_mismatch');
        const changed = JSON.parse(body); changed.totalAmount += 1;
        const conflict = http.post(`${publicUrl}/api/orders`, JSON.stringify(changed), { headers: h, timeout: '10s' });
        if (conflict.status !== 409) fail('replay_conflict_missing');
      }
      result.outcome = 'timeout';
      while (Date.now() - started < pollDeadline * 1000) {
        const poll = http.get(`${publicUrl}/api/orders/${result.orderId}`, { headers: headers(), timeout: '5s', tags: { operation: 'order_poll' } });
        if (poll.status !== 200) { fail('order_poll_failed'); break; }
        result.status = poll.json('status'); result.failureCode = poll.json('failureCode');
        const outcome = terminalOutcome(result.status, result.failureCode, scenario === 'contention');
        if (outcome) {
          result.outcome = outcome;
          result.observedConfirmationMs = Date.now() - started;
          if (outcome === 'confirmed') { confirmed.add(1); observed.add(result.observedConfirmationMs); }
          break;
        }
        catalog();
        sleep(0.25);
      }
      if (!['confirmed', 'expected_contention'].includes(result.outcome)) fail(result.outcome);
    }
  }
  emit('order_result', result);
  // Ramp/spike stay bounded by finite iterations and four VUs. Their pacing affects admission,
  // not the number of fixtures, and includes a low-rate recovery tail.
  if (scenario === 'ramp') sleep(i < attempts / 4 || phase === 'recovery' ? 2 : i < attempts / 2 ? 1 : 0);
  if (scenario === 'spike') sleep(i < attempts / 4 || i >= attempts / 2 ? 2 : 0);
}
export function handleSummary(data) {
  emit('run_summary', { scenario, metrics: data.metrics });
  const passed = Object.values(data.metrics).every(m => !m.thresholds || Object.values(m.thresholds).every(t => t.ok));
  emit('run_finished', { passed });
  return { [`${outputDir}/summary.json`]: JSON.stringify({ runId, scenario, passed, metrics: data.metrics }, null, 2) };
}
