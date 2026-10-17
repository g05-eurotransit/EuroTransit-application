import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { Alert, Badge, Button, Form, Modal, Spinner, Table } from "react-bootstrap";
import { ApiError, newId } from "../api/client";
import {
  abandonOrder,
  allowedAction,
  currentStatus,
  listOpenIncidents,
  needsEvidence,
  redriveOrder,
  refusalMessage,
  resolveIncident,
  settleIncident,
  type IncidentAction,
  type ReconciliationIncident,
  type ReconciliationKind,
} from "../api/reconciliation";

/**
 * The operator console over open reconciliation incidents, one action per row
 * (docs/operations/orders-recovery-runbook.md, docs/design/api-design.md).
 */

/**
 * How long an order's actions stay disabled after a command. Every command answers `202 Accepted`,
 * so the reload that follows it lands while the pipeline is still working; this covers that gap.
 */
const PIPELINE_GRACE_MS = 2500;

/**
 * How often the list refreshes itself. Incidents open while the page just sits here, and the
 * seat-hold clock is already running when they do.
 */
const POLL_MS = 30000;

/** Successes expire; refusals do not — they carry the reason and the correlation id. */
const SUCCESS_NOTICE_MS = 8000;

const ACTION_LABEL: Record<IncidentAction, string> = {
  redrive: "Redrive",
  abandon: "Abandon",
  settle: "Settle",
  resolve: "Resolve",
};

/** Red for the two that end an order; the primary colour for the two that retry or close. */
const ACTION_VARIANT: Record<IncidentAction, string> = {
  redrive: "primary",
  abandon: "outline-danger",
  settle: "outline-danger",
  resolve: "outline-primary",
};

const KIND_LABEL: Record<ReconciliationKind, string> = {
  DELIVERY_EXHAUSTED: "Delivery exhausted",
  INVARIANT_VIOLATION: "Invariant violation",
  PAYMENT_REVERSAL_FAILURE: "Payment reversal failed",
  SEAT_RETURN_FAILURE: "Seat return failed",
  PAYMENT_OUTCOME_UNKNOWN: "Payment outcome unknown",
};

const KIND_VARIANT: Record<ReconciliationKind, string> = {
  DELIVERY_EXHAUSTED: "warning",
  INVARIANT_VIOLATION: "danger",
  PAYMENT_REVERSAL_FAILURE: "danger",
  SEAT_RETURN_FAILURE: "accent",
  PAYMENT_OUTCOME_UNKNOWN: "danger",
};

function formatInstant(iso: string): string {
  return new Date(iso).toLocaleString("it-IT", {
    day: "2-digit",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
  });
}

/** Minutes:seconds while the hold runs, a flag once it has lapsed. */
function HoldDeadline({ expiresAt, now }: { expiresAt: string; now: number }) {
  const remaining = Math.floor((new Date(expiresAt).getTime() - now) / 1000);
  if (remaining <= 0) {
    return (
      <Badge bg="danger" className="fs-6">
        hold expired
      </Badge>
    );
  }
  const minutes = Math.floor(remaining / 60);
  const seconds = remaining % 60;
  return (
    <span className={`fw-bold ${remaining < 120 ? "text-danger" : "text-dark"}`}>
      {minutes}:{seconds.toString().padStart(2, "0")}
    </span>
  );
}

interface EvidencePrompt {
  incident: ReconciliationIncident;
  action: Extract<IncidentAction, "settle" | "resolve">;
}

export function OperationsPage() {
  const [incidents, setIncidents] = useState<ReconciliationIncident[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<{ id: number; variant: string; text: string } | null>(null);
  const noticeSeq = useRef(0);
  const noticeTimer = useRef<number | undefined>(undefined);
  useEffect(() => () => window.clearTimeout(noticeTimer.current), []);
  const [busyId, setBusyId] = useState<string | null>(null);
  // Keyed by order, not by incident: a command routinely closes one incident and opens another on
  // the same order, and that new one must not be actionable until the pipeline has settled.
  const [pendingOrders, setPendingOrders] = useState<ReadonlySet<string>>(new Set());
  const graceTimers = useRef<number[]>([]);
  useEffect(() => () => graceTimers.current.forEach((id) => clearTimeout(id)), []);
  const [prompt, setPrompt] = useState<EvidencePrompt | null>(null);
  const [evidence, setEvidence] = useState("");

  // One clock for the whole table: the hold deadline decides redrive against abandon, so it has to
  // stay live, and a single tick keeps every row consistent.
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);

  /** Shows one notice, replacing any other. */
  const announce = useCallback((variant: "success" | "danger", text: string) => {
    const id = ++noticeSeq.current;
    window.clearTimeout(noticeTimer.current);
    setResult({ id, variant, text });
    if (variant === "success") {
      noticeTimer.current = window.setTimeout(
        // Guarded on the id so a slow timer cannot clear a newer notice.
        () => setResult((current) => (current?.id === id ? null : current)),
        SUCCESS_NOTICE_MS,
      );
    }
  }, []);

  // `quiet` is a background refresh: same request, but it leaves the Refresh button alone instead
  // of flashing its spinner every half minute. A failure still surfaces, and clears on the next one.
  const load = useCallback((signal?: AbortSignal, quiet = false) => {
    if (!quiet) setLoading(true);
    return listOpenIncidents(100, signal)
      .then((open) => {
        setIncidents(open);
        setError(null);
      })
      .catch((e) => {
        if (e instanceof ApiError && e.kind === "network" && signal?.aborted) return;
        if (e instanceof ApiError && e.kind === "forbidden") {
          setError("This account does not hold the operations role.");
          return;
        }
        setError(e instanceof Error ? e.message : "Failed to load open incidents");
      })
      .finally(() => {
        if (!quiet) setLoading(false);
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    void load(ac.signal);
    return () => ac.abort();
  }, [load]);

  // Paused while a dialog is open or a command is in flight: rows must not move under the cursor,
  // and the reloads around a command already cover that moment.
  useEffect(() => {
    if (prompt !== null || busyId !== null) return;
    const id = setInterval(() => void load(undefined, true), POLL_MS);
    return () => clearInterval(id);
  }, [load, prompt, busyId]);

  /**
   * A fresh idempotency key per click, never one per incident: the key identifies this attempt, so
   * replaying it would replay the first attempt's answer instead of acting again.
   */
  async function run(incident: ReconciliationIncident, action: IncidentAction, evidenceReference = "") {
    setBusyId(incident.id);
    window.clearTimeout(noticeTimer.current);
    setResult(null);
    const key = newId();
    const correlationId = newId();
    const ref = `#${incident.orderId.slice(0, 8)}`;
    try {
      switch (action) {
        case "redrive": {
          const res = await redriveOrder(incident.orderId, key, correlationId);
          announce("success", `${ref} redriven from ${res.orderStatus}, attempt ${res.attemptCount}.`);
          break;
        }
        case "abandon": {
          const res = await abandonOrder(incident.orderId, key, correlationId);
          announce("success", `${ref} abandoned, now ${res.orderStatus}.`);
          break;
        }
        case "settle": {
          const res = await settleIncident(incident.orderId, incident.id, evidenceReference, key, correlationId);
          announce("success", `Payment outcome on ${ref} settled; the order is ${res.orderStatus}.`);
          break;
        }
        case "resolve": {
          const res = await resolveIncident(incident.orderId, incident.id, evidenceReference, key, correlationId);
          announce("success", `${ref} resolved at ${formatInstant(res.resolvedAt)}.`);
          break;
        }
      }
    } catch (e) {
      const text =
        e instanceof ApiError
          ? refusalMessage(e.code, e.message)
          : e instanceof Error
            ? e.message
            : "The action failed";
      const correlation = e instanceof ApiError && e.correlationId ? ` (correlation ${e.correlationId})` : "";
      announce("danger", `${ACTION_LABEL[action]} on ${ref} refused: ${text}${correlation}`);
    } finally {
      setBusyId(null);
      // Twice, either way: now for what the command changed outright, then again once the pipeline
      // has had a moment. A refusal reloads too — it usually means the row moved under us.
      setPendingOrders((prev) => new Set(prev).add(incident.orderId));
      await load();
      const timer = window.setTimeout(() => {
        void load().finally(() =>
          setPendingOrders((prev) => {
            const next = new Set(prev);
            next.delete(incident.orderId);
            return next;
          }),
        );
      }, PIPELINE_GRACE_MS);
      graceTimers.current.push(timer);
    }
  }

  function start(incident: ReconciliationIncident, action: IncidentAction) {
    if (needsEvidence(action)) {
      setEvidence("");
      setPrompt({ incident, action: action as EvidencePrompt["action"] });
      return;
    }
    void run(incident, action);
  }

  function confirmEvidence() {
    if (!prompt || evidence.trim() === "") return;
    const { incident, action } = prompt;
    setPrompt(null);
    void run(incident, action, evidence.trim());
  }

  const working = useMemo(() => {
    const busyOrder = incidents.find((i) => i.id === busyId)?.orderId;
    return busyOrder ? new Set(pendingOrders).add(busyOrder) : pendingOrders;
  }, [incidents, busyId, pendingOrders]);

  const rows = useMemo(
    () =>
      incidents.map((incident) => {
        const holdExpired =
          !!incident.reservationExpiresAt && new Date(incident.reservationExpiresAt).getTime() <= now;
        return { incident, decision: allowedAction(incident, holdExpired) };
      }),
    [incidents, now],
  );

  return (
    <div>
      <div className="d-flex flex-wrap justify-content-between align-items-end mb-4 pb-3 border-bottom gap-3">
        <div>
          <h1 className="fw-bold mb-1">Reconciliation</h1>
        </div>
        <Button variant="outline-primary" onClick={() => void load()} disabled={loading}>
          {loading ? <Spinner animation="border" size="sm" /> : "Refresh"}
        </Button>
      </div>

      {error && (
        <Alert variant="danger" role="alert">
          {error}
        </Alert>
      )}
      {result && (
        <Alert
          variant={result.variant}
          role={result.variant === "danger" ? "alert" : "status"}
          dismissible
          onClose={() => setResult(null)}
        >
          {result.text}
        </Alert>
      )}

      {loading && incidents.length === 0 && (
        <div className="text-secondary-emphasis d-flex align-items-center gap-2">
          <Spinner animation="border" size="sm" /> Loading…
        </div>
      )}

      {!loading && !error && incidents.length === 0 && (
        <p className="text-secondary-emphasis text-center py-5 mb-0">
          No open incidents.
        </p>
      )}

      {incidents.length > 0 && (
        <div className="table-responsive">
          <Table hover className="align-middle">
            <thead>
              <tr className="text-secondary-emphasis">
                <th>Order</th>
                <th>Incident</th>
                <th>Failed in</th>
                <th>Now</th>
                <th>Failure</th>
                <th className="text-center">Attempts</th>
                <th>Seat hold</th>
                <th>Opened</th>
                <th className="text-end">Action</th>
              </tr>
            </thead>
            <tbody>
              {rows.map(({ incident, decision }) => (
                <tr key={incident.id}>
                  <td>
                    <Link to={`/orders/${incident.orderId}`} className="fw-bold" title={incident.orderId}>
                      #{incident.orderId.slice(0, 8)}
                    </Link>
                  </td>
                  <td>
                    <Badge bg={KIND_VARIANT[incident.kind]} className="fs-6">
                      {KIND_LABEL[incident.kind] ?? incident.kind}
                    </Badge>
                  </td>
                  <td className="text-secondary-emphasis small">{incident.orderState}</td>
                  <td className="fw-semibold text-dark small">{incident.orderStatus ?? "—"}</td>
                  <td className="text-secondary-emphasis small">{incident.failureClass}</td>
                  <td className="text-center">{incident.attemptCount}</td>
                  <td>
                    {incident.reservationExpiresAt && currentStatus(incident) === "PAYMENT_PENDING" ? (
                      <HoldDeadline expiresAt={incident.reservationExpiresAt} now={now} />
                    ) : (
                      <span className="text-secondary-emphasis">—</span>
                    )}
                  </td>
                  <td className="text-secondary-emphasis small">{formatInstant(incident.createdAt)}</td>
                  <td className="text-end">
                    {decision.action ? (
                      <Button
                        size="sm"
                        variant={ACTION_VARIANT[decision.action]}
                        className="fw-semibold"
                        title={working.has(incident.orderId) ? "Waiting for the pipeline to finish." : decision.hint}
                        disabled={busyId !== null || working.has(incident.orderId)}
                        onClick={() => start(incident, decision.action)}
                      >
                        {working.has(incident.orderId) ? (
                          <>
                            <Spinner animation="border" size="sm" />
                            {/* A spinner alone leaves the button with no accessible name. */}
                            <span className="visually-hidden">Working…</span>
                          </>
                        ) : (
                          ACTION_LABEL[decision.action]
                        )}
                      </Button>
                    ) : (
                      <span className="text-secondary-emphasis small">{decision.reason}</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </Table>
        </div>
      )}

      <Modal show={prompt !== null} onHide={() => setPrompt(null)} centered>
        <Modal.Header closeButton>
          <Modal.Title className="fs-5">
            {prompt ? `${ACTION_LABEL[prompt.action]} order #${prompt.incident.orderId.slice(0, 8)}` : ""}
          </Modal.Title>
        </Modal.Header>
        <Modal.Body>
          <p className="text-secondary-emphasis">
            {prompt?.action === "settle"
              ? "You attest the provider holds no money for this order. The order will then fail."
              : "Closes an investigated invariant violation. The order is not changed."}
          </p>
          <Form
            onSubmit={(e) => {
              e.preventDefault();
              confirmEvidence();
            }}
          >
            <Form.Group controlId="evidence-reference">
              <Form.Label className="fw-semibold">Evidence reference</Form.Label>
              <Form.Control
                autoFocus
                value={evidence}
                onChange={(e) => setEvidence(e.target.value)}
                placeholder="Provider reference or audit ticket"
              />
            </Form.Group>
          </Form>
        </Modal.Body>
        <Modal.Footer>
          <Button variant="outline-secondary" onClick={() => setPrompt(null)}>
            Cancel
          </Button>
          <Button variant="danger" className="fw-semibold" disabled={evidence.trim() === ""} onClick={confirmEvidence}>
            {prompt ? ACTION_LABEL[prompt.action] : ""}
          </Button>
        </Modal.Footer>
      </Modal>
    </div>
  );
}
