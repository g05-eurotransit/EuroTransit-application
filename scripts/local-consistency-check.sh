#!/usr/bin/env bash
# Cross-database money-path consistency check for the local compose stack.
#
# For every order created at or after SINCE (UTC, e.g. 2026-10-08T14:00:00) it reads Orders,
# Inventory and Payments and reports the violations a crash, a shutdown or a replay could leave:
#
#   - an order not in a terminal state (CONFIRMED, FAILED, CANCELLED, REFUNDED)
#   - more than one payment authorization for an order (a duplicate payment effect)
#   - a FAILED or CANCELLED order whose payment is still AUTHORIZED (money taken, no ticket)
#   - a CONFIRMED order without exactly one order-confirmed outbox row, a CONFIRMED reservation
#     and an AUTHORIZED payment
#   - an open reconciliation record
#
# A duplicate reservation cannot be stored (uk_reservations_order_id), so it is not checked here.
#
# Usage: scripts/local-consistency-check.sh SINCE
# Exit status: 0 when nothing is violated, 1 otherwise.
set -euo pipefail

since="${1:?usage: $0 SINCE (UTC timestamp, e.g. 2026-10-08T14:00:00)}"
container="${POSTGRES_CONTAINER:-eurotransit-postgres}"
user="${POSTGRES_USER:-eurotransit}"

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required to join the three databases." >&2
  exit 127
fi

query() {
  docker exec "$container" psql -U "$user" -d "$1" -v ON_ERROR_STOP=1 -AtF $'\t' -c "$2"
}

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

query orders_db "select id, status from orders where created_at >= '$since'" > "$workdir/orders"
query orders_db "select aggregate_id, count(*) from outbox_events where type = 'order-confirmed' group by 1" > "$workdir/confirmed_rows"
query orders_db "select order_id, kind from reconciliation_records where status = 'OPEN'" > "$workdir/open_records"
query inventory_db "select order_id, status from reservations" > "$workdir/reservations"
query payments_db "select order_id, status from payment_authorizations" > "$workdir/authorizations"

python3 - "$workdir" "$since" <<'EOF'
import sys
from collections import defaultdict

workdir, since = sys.argv[1], sys.argv[2]

def rows(name):
    with open(f"{workdir}/{name}") as f:
        return [line.rstrip("\n").split("\t") for line in f if line.strip()]

orders = dict(rows("orders"))
confirmed_rows = {order_id: int(count) for order_id, count in rows("confirmed_rows")}
open_records = defaultdict(list)
for order_id, kind in rows("open_records"):
    open_records[order_id].append(kind)
reservations = dict(rows("reservations"))
authorizations = defaultdict(list)
for order_id, status in rows("authorizations"):
    authorizations[order_id].append(status)

terminal = {"CONFIRMED", "FAILED", "CANCELLED", "REFUNDED"}
violations = []
for order_id, status in sorted(orders.items()):
    auths = authorizations.get(order_id, [])
    if status not in terminal:
        violations.append(f"{order_id} not converged: {status}")
    if len(auths) > 1:
        violations.append(f"{order_id} has {len(auths)} payment authorizations: {auths}")
    if status in {"FAILED", "CANCELLED"} and "AUTHORIZED" in auths:
        violations.append(f"{order_id} is {status} but its payment is still AUTHORIZED")
    if status == "CONFIRMED":
        if confirmed_rows.get(order_id, 0) != 1:
            violations.append(f"{order_id} CONFIRMED with {confirmed_rows.get(order_id, 0)} order-confirmed rows")
        if reservations.get(order_id) != "CONFIRMED":
            violations.append(f"{order_id} CONFIRMED but its reservation is {reservations.get(order_id)}")
        if auths != ["AUTHORIZED"]:
            violations.append(f"{order_id} CONFIRMED but its payment is {auths}")
    if order_id in open_records:
        violations.append(f"{order_id} has open reconciliation records: {open_records[order_id]}")

by_status = defaultdict(int)
for status in orders.values():
    by_status[status] += 1
summary = ", ".join(f"{s} {n}" for s, n in sorted(by_status.items())) or "none"
print(f"orders since {since}: {len(orders)} ({summary})")
for v in violations:
    print(f"VIOLATION {v}")
print("OK: no violations" if not violations else f"{len(violations)} violation(s)")
sys.exit(1 if violations else 0)
EOF
