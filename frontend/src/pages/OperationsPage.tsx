import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { Button, Form, Modal, Spinner } from "react-bootstrap";
import { ApiError, newId } from "../api/client";
import { formatInstant } from "../utils/dates";
import { shortRef } from "../utils/format";
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

/** Red for a broken invariant or an open question about money, amber for a delivery that ran out. */
const KIND_TONE: Record<ReconciliationKind, string> = {
  DELIVERY_EXHAUSTED: "et-kind--warning",
  INVARIANT_VIOLATION: "et-kind--danger",
  PAYMENT_REVERSAL_FAILURE: "et-kind--danger",
  // Not accent: nothing is being paid here, and gold means money everywhere else in the app.
  SEAT_RETURN_FAILURE: "",
  PAYMENT_OUTCOME_UNKNOWN: "et-kind--danger",
};

const KIND_LABEL: Record<ReconciliationKind, string> = {
  DELIVERY_EXHAUSTED: "Delivery exhausted",
  INVARIANT_VIOLATION: "Invariant violation",
  PAYMENT_REVERSAL_FAILURE: "Payment reversal failed",
  SEAT_RETURN_FAILURE: "Seat return failed",
  PAYMENT_OUTCOME_UNKNOWN: "Payment outcome unknown",
};

/** The customer's clock, in the console. At zero it becomes a state, and the state picks the button. */
function HoldDeadline({ expiresAt, now }: { expiresAt: string; now: number }) {
  const remaining = Math.floor((new Date(expiresAt).getTime() - now) / 1000);
  if (remaining <= 0) {
    return <span className="badge bg-danger">hold expired</span>;
  }
  const minutes = Math.floor(remaining / 60);
  const seconds = remaining % 60;
  return (
    <span className={`et-hold-pill${remaining < 120 ? " et-hold-pill--late" : ""}`}>
      <i className="bi bi-clock-history" aria-hidden="true" />
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
  const [result, setResult] = useState<
    { id: number; variant: string; text: string; trace?: string } | null
  >(null);
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
  const announce = useCallback((variant: "success" | "danger", text: string, trace?: string) => {
    const id = ++noticeSeq.current;
    window.clearTimeout(noticeTimer.current);
    setResult({ id, variant, text, trace });
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
    const ref = `#${shortRef(incident.orderId)}`;
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
      // The code and the correlation id: the two strings that end up in the ticket.
      const trace = [
        e instanceof ApiError ? e.code : undefined,
        e instanceof ApiError && e.correlationId ? `correlation ${e.correlationId}` : undefined,
      ]
        .filter(Boolean)
        .join(" · ");
      announce("danger", `${ACTION_LABEL[action]} on ${ref} refused: ${text}`, trace || undefined);
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
      <div className="d-flex flex-wrap justify-content-between align-items-end mb-4 gap-3">
        <div>
          <h1 className="fw-bold mb-1">Reconciliation</h1>
          <div className="text-secondary-emphasis small">
            {incidents.length} open {incidents.length === 1 ? "incident" : "incidents"} · most
            urgent first · refreshes itself every {POLL_MS / 1000}s
          </div>
        </div>
        <Button variant="outline-primary" onClick={() => void load()} disabled={loading}>
          {loading ? (
            <Spinner animation="border" size="sm" />
          ) : (
            <>
              <i className="bi bi-arrow-repeat me-2" aria-hidden="true" />
              Refresh
            </>
          )}
        </Button>
      </div>

      {error && (
        <div className="et-notice mb-3" role="alert">
          <i className="bi bi-exclamation-triangle fs-5" aria-hidden="true" />
          <span>{error}</span>
        </div>
      )}
      {result && (
        <div
          className={`et-notice mb-3${result.variant === "success" ? " et-notice--ok" : ""}`}
          role={result.variant === "danger" ? "alert" : "status"}
        >
          <i
            className={`bi ${result.variant === "success" ? "bi-check-circle" : "bi-exclamation-triangle"} fs-5`}
            aria-hidden="true"
          />
          <span>
            {result.text}
            {result.trace && <span className="et-notice__trace et-mono d-block">{result.trace}</span>}
          </span>
        </div>
      )}

      {loading && incidents.length === 0 && (
        <div className="text-secondary-emphasis d-flex align-items-center gap-2">
          <Spinner animation="border" size="sm" /> Loading…
        </div>
      )}

      {!loading && !error && incidents.length === 0 && (
        <div className="et-surface">
          <div className="et-edge">
            <div className="et-edge__ic">
              <i className="bi bi-check-circle" aria-hidden="true" />
            </div>
            <h2>No open incidents</h2>
            <p>The pipeline is holding on its own. This list checks itself every {POLL_MS / 1000} seconds.</p>
          </div>
        </div>
      )}

      {incidents.length > 0 && (
        <div className="et-surface">
          <div className="et-ops-scroll">
            <div className="et-ops et-ops--head">
              <span className="et-lab">Order</span>
              <span className="et-lab">Incident</span>
              <span className="et-lab">Where it broke</span>
              <span className="et-lab text-center">Tries</span>
              <span className="et-lab">Seat hold</span>
              <span className="et-lab">Opened</span>
              <span className="et-lab text-end">Action</span>
            </div>

            {rows.map(({ incident, decision }) => {
              const liveHold =
                !!incident.reservationExpiresAt && currentStatus(incident) === "PAYMENT_PENDING";
              const holdExpired =
                liveHold && new Date(incident.reservationExpiresAt!).getTime() <= now;
              const busy = working.has(incident.orderId);
              // The rule on the left is urgency, not kind: the clock decides the action.
              const tone = holdExpired ? " et-ops--expired" : liveHold ? " et-ops--urgent" : "";
              return (
                <div key={incident.id} className={`et-ops${tone}${busy ? " et-ops--busy" : ""}`}>
                  <Link
                    to={`/orders/${incident.orderId}`}
                    className="et-ops__ref et-mono"
                    title={incident.orderId}
                  >
                    #{shortRef(incident.orderId)}
                  </Link>

                  <span className={`et-kind ${KIND_TONE[incident.kind]}`}>
                    <i aria-hidden="true" />
                    <span>{KIND_LABEL[incident.kind] ?? incident.kind}</span>
                  </span>

                  {/* One transition: where it failed, and where the order is now. */}
                  <div>
                    <div className="et-ops__flow">
                      <span className="et-ops__from">{incident.orderState}</span>
                      <span aria-hidden="true">→</span>
                      <span>{incident.orderStatus ?? "—"}</span>
                    </div>
                    <div className="et-ops__class et-mono">{incident.failureClass}</div>
                  </div>

                  <span className="et-ops__count">{incident.attemptCount}</span>

                  <span>
                    {liveHold ? (
                      <HoldDeadline expiresAt={incident.reservationExpiresAt!} now={now} />
                    ) : (
                      <span className="text-secondary-emphasis">—</span>
                    )}
                  </span>

                  <span className="et-ops__when">{formatInstant(incident.createdAt)}</span>

                  <span className="et-ops__act">
                    {busy ? (
                      <span className="et-ops__reason d-flex align-items-center gap-2">
                        <Spinner animation="border" size="sm" />
                        Waiting for the pipeline…
                      </span>
                    ) : decision.action ? (
                      <Button
                        size="sm"
                        variant={ACTION_VARIANT[decision.action]}
                        className="fw-semibold"
                        title={decision.hint}
                        disabled={busyId !== null}
                        onClick={() => start(incident, decision.action)}
                      >
                        {ACTION_LABEL[decision.action]}
                        {needsEvidence(decision.action) ? "…" : ""}
                      </Button>
                    ) : (
                      <span className="et-ops__reason">{decision.reason}</span>
                    )}
                  </span>
                </div>
              );
            })}
          </div>
        </div>
      )}

      <Modal show={prompt !== null} onHide={() => setPrompt(null)} centered>
        <Modal.Header closeButton>
          <Modal.Title className="fs-5">
            {prompt ? `${ACTION_LABEL[prompt.action]} order #${shortRef(prompt.incident.orderId)}` : ""}
          </Modal.Title>
        </Modal.Header>
        <Modal.Body>
          <div className="et-notice mb-3">
            <i className="bi bi-exclamation-triangle fs-5" aria-hidden="true" />
            <span>
              <b>{prompt?.action === "settle" ? "Check the provider first." : "Investigate first."}</b>
              {prompt?.action === "settle"
                ? "You attest the provider holds no money for this order. The order will then fail, and the incident closes with your evidence."
                : "This closes an investigated invariant violation with your evidence. The order itself is not changed."}
            </span>
          </div>
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
