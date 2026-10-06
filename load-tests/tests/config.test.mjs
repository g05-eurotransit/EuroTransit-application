import { test } from 'node:test';
import assert from 'node:assert/strict';
import { validateFixtures, terminalOutcome, integer } from '../config.mjs';
const f = { routeId: 'r', offerId: 'o', travelDate: '2099-01-01', seatId: '1A', unitPrice: '24.90' };
test('capacity never wraps and collisions fail before traffic', () => {
 assert.throws(() => validateFixtures({version:1,fixtures:[f]},2), /Insufficient/);
 assert.throws(() => validateFixtures({version:1,fixtures:[f,f]},2), /Duplicate/);
 assert.equal(validateFixtures({version:1,fixtures:[f]},1).length,1);
});
test('FAILED is not baseline success; sold out is expected only in contention', () => {
 assert.equal(terminalOutcome('FAILED','SEATS_UNAVAILABLE',false),'terminal_failure');
 assert.equal(terminalOutcome('FAILED','SEATS_UNAVAILABLE',true),'expected_contention');
 assert.equal(terminalOutcome('ACCEPTED',null,false),null);
});
test('VU resource ceiling enforced',()=>{assert.equal(integer(undefined,2,1,4,'VUs'),2);assert.throws(()=>integer('5',2,1,4,'VUs'));});

test('invalid calendar dates rejected',()=>assert.throws(()=>validateFixtures({version:1,fixtures:[{...f,travelDate:'2099-02-30'}]},1)));
