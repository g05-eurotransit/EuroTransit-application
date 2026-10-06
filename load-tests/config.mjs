export function validateFixtures(document, required, now = new Date()) {
  if (document.version !== 1 || !Array.isArray(document.fixtures)) throw new Error('Invalid fixture manifest version');
  const seen = new Set();
  for (const f of document.fixtures) {
    if (!f.routeId || !f.offerId || !f.seatId || !/^\d{4}-\d{2}-\d{2}$/.test(f.travelDate) || !Number.isFinite(Number(f.unitPrice)) || Number(f.unitPrice) <= 0) throw new Error('Invalid fixture');
    if (new Date(f.travelDate).toISOString().slice(0, 10) !== f.travelDate) throw new Error('Invalid calendar date');
    if (f.travelDate <= now.toISOString().slice(0, 10)) throw new Error('Fixtures must use future travel dates');
    if (f.currency !== undefined && !/^[A-Z]{3}$/.test(f.currency)) throw new Error('Invalid fixture currency');
    const key = JSON.stringify([f.routeId, f.travelDate, f.seatId]);
    if (seen.has(key)) throw new Error('Duplicate fixture');
    seen.add(key);
  }
  if (document.fixtures.length < required) throw new Error('Insufficient fixtures; allocation never wraps');
  return document.fixtures;
}
export function terminalOutcome(status, failureCode, contention) {
  if (status === 'CONFIRMED') return 'confirmed';
  if (status === 'FAILED') return contention && failureCode === 'SEATS_UNAVAILABLE' ? 'expected_contention' : 'terminal_failure';
  return null;
}
export function integer(value, fallback, min, max, name) {
  const n = Number(value === undefined || value === '' ? fallback : value);
  if (!Number.isInteger(n) || n < min || n > max) throw new Error(`Invalid ${name}`);
  return n;
}
