import { ProgressBar, Spinner } from "react-bootstrap";
import type { OrderStatus } from "../../api/orders";

export const HAPPY_PATH: { status: OrderStatus; label: string; icon: string }[] = [
  { status: "ACCEPTED", label: "Order placed", icon: "bi-receipt" },
  { status: "RESERVING", label: "Seats reserved", icon: "bi-person-check" },
  { status: "PAYMENT_PENDING", label: "Payment", icon: "bi-credit-card" },
  { status: "CONFIRMING_INVENTORY", label: "Confirming", icon: "bi-arrow-repeat" },
  { status: "CONFIRMED", label: "Confirmed", icon: "bi-check-circle" },
];

/**
 * The step a status is drawn on. A customer-hold order waiting for its payment (ADR-016) sits on the
 * "Payment" step: the seats are held and the next thing to happen is the customer paying.
 */
export function stepIndexOf(status: OrderStatus): number {
  const shown = status === "HELD_AWAITING_PAYMENT" ? "PAYMENT_PENDING" : status;
  return HAPPY_PATH.findIndex((s) => s.status === shown);
}

type StepState = "done" | "active" | "pending";

function stepCircleClass(state: StepState): string {
  if (state === "done") return "bg-primary text-white border-primary";
  if (state === "active") return "bg-white text-primary border-primary";
  return "bg-white text-secondary-emphasis border-secondary-subtle";
}

function stepStates(status: OrderStatus): StepState[] {
  if (status === "COMPENSATING") return ["done", "done", "done", "done", "active"];
  const currentIndex = stepIndexOf(status);
  return HAPPY_PATH.map((_, i) => {
    if (status === "CONFIRMED") return "done";
    if (i < currentIndex) return "done";
    if (i === currentIndex) return "active";
    return "pending";
  });
}

const STEP_SIZE = 48;

function progressPercent(status: OrderStatus): number {
  if (status === "COMPENSATING") {
    const idx = HAPPY_PATH.findIndex((s) => s.status === "CONFIRMING_INVENTORY");
    return (idx / (HAPPY_PATH.length - 1)) * 100;
  }
  const idx = stepIndexOf(status);
  return (idx / (HAPPY_PATH.length - 1)) * 100;
}

export function OrderStepper({ status, lastKnownStepIndex }: { status: OrderStatus; lastKnownStepIndex: number }) {
  // The stepper answers "what is happening to my order now". On a finished one, nothing is.
  if (status === "CONFIRMED" || status === "REFUNDED") return null;

  if (status === "FAILED" || status === "CANCELLED") {
    if (lastKnownStepIndex === 0) {
      return (
        <div className="d-flex align-items-center gap-3 px-3 py-3 border-bottom">
          <div className="position-relative flex-shrink-0" style={{ width: STEP_SIZE * 3, height: STEP_SIZE }}>
            <ProgressBar
              now={100}
              variant="danger"
              style={{ position: "absolute", top: STEP_SIZE / 2 - 2, left: STEP_SIZE / 2, right: STEP_SIZE / 2, height: 4 }}
            />
            <div className="d-flex justify-content-between position-relative h-100">
              <div
                className="rounded-circle bg-primary text-white d-flex align-items-center justify-content-center fs-5"
                style={{ width: STEP_SIZE, height: STEP_SIZE }}
              >
                <i className="bi bi-check-lg" aria-hidden="true" />
              </div>
              <div
                className="rounded-circle bg-danger text-white d-flex align-items-center justify-content-center fs-5"
                style={{ width: STEP_SIZE, height: STEP_SIZE }}
              >
                <i className={`bi ${status === "FAILED" ? "bi-x-lg" : "bi-slash-lg"}`} aria-hidden="true" />
              </div>
            </div>
          </div>
          <div className="d-flex flex-column">
            <span className="fw-semibold text-dark">{status === "FAILED" ? "Failed" : "Cancelled"}</span>
            <span className="text-secondary-emphasis small">after order was placed</span>
          </div>
        </div>
      );
    }

    const failIndex = lastKnownStepIndex;
    const columns = `repeat(${HAPPY_PATH.length}, 1fr)`;
    const trackInset = `${100 / (HAPPY_PATH.length * 2)}%`;
    const percent = (failIndex / (HAPPY_PATH.length - 1)) * 100;

    return (
      <div className="px-3 py-3 border-bottom">
        <div className="position-relative mb-2" style={{ height: STEP_SIZE }}>
          <ProgressBar
            now={percent}
            variant="danger"
            style={{ position: "absolute", top: STEP_SIZE / 2 - 2, left: trackInset, right: trackInset, height: 4 }}
          />
          <div className="d-grid position-relative h-100" style={{ gridTemplateColumns: columns }}>
            {HAPPY_PATH.map((step, i) => {
              const isDone = i < lastKnownStepIndex;
              const isFailNode = i === failIndex;
              const circleClass = isFailNode
                ? "bg-danger text-white border-danger"
                : isDone
                  ? "bg-primary text-white border-primary"
                  : "bg-white text-secondary-emphasis border-secondary-subtle";
              return (
                <div key={step.status} className="d-flex justify-content-center">
                  <div
                    className={`rounded-circle border d-flex align-items-center justify-content-center flex-shrink-0 fs-5 ${circleClass}`}
                    style={{ width: STEP_SIZE, height: STEP_SIZE, borderWidth: 3 }}
                  >
                    {isFailNode ? (
                      <i className={`bi ${status === "FAILED" ? "bi-x-lg" : "bi-slash-lg"}`} aria-hidden="true" />
                    ) : (
                      <i className={`bi ${step.icon}`} aria-hidden="true" />
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        </div>
        <div className="d-grid" style={{ gridTemplateColumns: columns }}>
          {HAPPY_PATH.map((step, i) => {
            const isDone = i < lastKnownStepIndex;
            const isFailNode = i === failIndex;
            const label = isFailNode ? (status === "FAILED" ? "Failed" : "Cancelled") : step.label;
            return (
              <span
                key={step.status}
                className={`small text-center px-1 ${
                  isFailNode ? "text-danger fw-semibold" : isDone ? "fw-semibold text-dark" : "text-secondary-emphasis"
                }`}
              >
                {label}
              </span>
            );
          })}
        </div>
      </div>
    );
  }

  const states = stepStates(status);
  const compensatingLastStep = status === "COMPENSATING";
  const columns = `repeat(${HAPPY_PATH.length}, 1fr)`;
  const trackInset = `${100 / (HAPPY_PATH.length * 2)}%`;

  return (
    <div className="px-3 py-3 border-bottom">
      <div className="position-relative mb-2" style={{ height: STEP_SIZE }}>
        <ProgressBar
          now={progressPercent(status)}
          variant={compensatingLastStep ? "accent" : "primary"}
          style={{ position: "absolute", top: STEP_SIZE / 2 - 2, left: trackInset, right: trackInset, height: 4 }}
        />
        <div className="d-grid position-relative h-100" style={{ gridTemplateColumns: columns }}>
          {HAPPY_PATH.map((step, i) => {
            const state = states[i];
            const isCompensatingNode = compensatingLastStep && i === HAPPY_PATH.length - 1;
            const icon = isCompensatingNode ? "bi-hourglass-split" : step.icon;
            const circleClass = isCompensatingNode
              ? "bg-white text-accent-emphasis border-accent"
              : stepCircleClass(state);
            return (
              <div key={step.status} className="d-flex justify-content-center">
                <div
                  className={`rounded-circle border d-flex align-items-center justify-content-center flex-shrink-0 fs-5 ${circleClass}`}
                  style={{ width: STEP_SIZE, height: STEP_SIZE, borderWidth: 3 }}
                >
                  {state === "active" && !isCompensatingNode ? (
                    <Spinner animation="border" size="sm" />
                  ) : (
                    // Done keeps its own icon: the filled circle already says it happened, and a row
                    // of identical ticks says nothing about which step was which.
                    <i className={`bi ${icon}`} aria-hidden="true" />
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </div>
      <div className="d-grid" style={{ gridTemplateColumns: columns }}>
        {HAPPY_PATH.map((step, i) => {
          const state = states[i];
          const isCompensatingNode = compensatingLastStep && i === HAPPY_PATH.length - 1;
          const label = isCompensatingNode ? "Resolving payment" : step.label;
          return (
            <span
              key={step.status}
              className={`small text-center px-1 ${
                state === "pending"
                  ? "text-secondary-emphasis"
                  : isCompensatingNode
                    ? "text-accent-emphasis fw-semibold"
                    : "fw-semibold text-dark"
              }`}
            >
              {label}
            </span>
          );
        })}
      </div>
    </div>
  );
}
