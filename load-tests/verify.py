#!/usr/bin/env python3
"""Verify run-scoped SELECT-only evidence. Missing evidence never passes."""
import argparse
import json
from pathlib import Path
import sys
from decimal import Decimal, InvalidOperation


def verify(ledger, evidence):
    errors = []
    run_ids = {r['runId'] for r in ledger}
    if len(run_ids) != 1 or evidence.get('runId') not in run_ids:
        return ['run_id_mismatch']
    if evidence.get('extraction') != 'COMPLETE':
        return ['evidence_incomplete']
    order_ids = {r.get('orderId') for r in ledger if r.get('orderId')}
    orders = {str(o['id']): o for o in evidence.get('orders', [])}
    if order_ids != set(orders):
        errors.append('order_scope_mismatch')
    if not ledger or not any(r.get('outcome') == 'confirmed' for r in ledger):
        errors.append('no_confirmed_checkout')
    if any(r.get('outcome') not in ('confirmed', 'expected_contention', 'overload_refusal') for r in ledger):
        errors.append('unexpected_checkout_outcome')
    payments = evidence.get('payments', [])
    reservations = evidence.get('reservations', [])
    inventory = evidence.get('inventory', [])
    if any(str(r.get('order_id')) not in order_ids for r in payments + reservations):
        errors.append('effect_scope_mismatch')
    provider = evidence.get('providerEffects', {})
    provider_rows = provider.get('authorizations', [])
    if provider.get('extraction') != 'COMPLETE':
        errors.append('provider_effects_unverified')
    if any(str(p.get('order_id')) not in order_ids for p in provider_rows):
        errors.append('provider_scope_mismatch')
    owned = set()
    for row in ledger:
        oid = row.get('orderId')
        if not oid:
            continue
        order = orders.get(oid)
        if not order:
            continue
        if row.get('status') != order['status']:
            errors.append(f'order_status_mismatch:{oid}')
        if row.get('outcome') == 'confirmed' and order['status'] != 'CONFIRMED':
            errors.append(f'confirmed_order_changed:{oid}')
        if row.get('outcome') == 'expected_contention' and order['status'] != 'FAILED':
            errors.append(f'contention_outcome_changed:{oid}')
        if order['status'] not in ('CONFIRMED', 'FAILED', 'CANCELLED'):
            errors.append(f'unresolved_order:{oid}')
        auths = [p for p in payments if str(p['order_id']) == oid and p['status'] == 'AUTHORIZED']
        effects = [p for p in provider_rows if str(p.get('order_id')) == oid and p.get('status') == 'SUCCEEDED']
        if len(effects) > 1:
            errors.append(f'duplicate_provider_effect:{oid}')
        if {(p.get('provider_reference_hash') or p.get('provider_reference')) for p in effects} != {(p.get('provider_reference_hash') or p.get('provider_reference')) for p in auths} or any(not (p.get('provider_reference_hash') or p.get('provider_reference')) for p in effects + auths):
            errors.append(f'provider_effect_mismatch:{oid}')
        if len(auths) > 1:
            errors.append(f'duplicate_authorization:{oid}')
        rs = [r for r in reservations if str(r['order_id']) == oid]
        if len(rs) > 1:
            errors.append(f'duplicate_reservation:{oid}')
        f = row['fixture']
        if order.get('route_id') != f['routeId'] or order.get('travel_date') != f['travelDate'] or order.get('seats') != f['seatId']:
            errors.append(f'order_fixture_mismatch:{oid}')
        try:
            expected = Decimal(str(f['unitPrice']))
            if Decimal(str(order.get('total_amount'))) != expected:
                errors.append(f'order_amount_mismatch:{oid}')
            if order.get('currency') != f.get('currency', 'EUR'):
                errors.append(f'order_currency_missing:{oid}')
            for p in auths + effects:
                if Decimal(str(p.get('amount'))) != expected or p.get('currency', '').upper() != order.get('currency', '').upper():
                    errors.append(f'payment_amount_or_currency_mismatch:{oid}')
        except (InvalidOperation, KeyError):
            errors.append(f'payment_amount_unverified:{oid}')
        if order['status'] == 'CONFIRMED':
            seat_key = (f['routeId'], f['travelDate'], f['seatId'])
            if seat_key in owned:
                errors.append(f'oversell:{seat_key}')
            owned.add(seat_key)
            if len(auths) != 1:
                errors.append(f'missing_authorization:{oid}')
            if len(rs) != 1 or rs[0]['status'] != 'CONFIRMED':
                errors.append(f'missing_confirmed_reservation:{oid}')
            else:
                if rs[0].get('route_id') != f['routeId'] or rs[0].get('travel_date') != f['travelDate']:
                    errors.append(f'reservation_fixture_mismatch:{oid}')
                seats = [s for s in inventory if (s['route_id'], s['travel_date'], s['seat_id']) == seat_key]
                if len(seats) != 1 or seats[0]['status'] != 'SOLD' or str(seats[0]['reservation_id']) != str(rs[0]['reservation_id']):
                    errors.append(f'seat_owner_mismatch:{oid}')
        if row.get('outcome') == 'expected_contention' and auths:
            errors.append(f'contention_loser_charged:{oid}')
    return errors


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--ledger', required=True)
    parser.add_argument('--evidence', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    try:
        rows = [json.loads(line) for line in Path(args.ledger).read_text().splitlines() if line.strip()]
        errors = verify(rows, json.loads(Path(args.evidence).read_text()))
    except (OSError, ValueError, KeyError, TypeError) as exc:
        errors = ['invalid_or_missing_evidence:' + type(exc).__name__]
    result = {'verification': 'VERIFIED' if not errors else 'NOT_VERIFIED', 'passed': not errors, 'violations': errors}
    Path(args.output).write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))
    return bool(errors)

if __name__ == '__main__':
    sys.exit(main())
