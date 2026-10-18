import { useNavigate } from "react-router-dom";
import { Button } from "react-bootstrap";

export interface BackButtonProps {
  /** Where it goes. Without it the browser's history is used — which loops where a page bounces. */
  to?: string;
  /** Names the destination, since the control is only an arrow. */
  label?: string;
}

/** The one back control of the app, so the arrow is the same shape on every page. */
export function BackButton({ to, label = "Go back" }: BackButtonProps) {
  const navigate = useNavigate();
  return (
    <Button
      variant="outline-primary"
      className="rounded-circle p-0 d-flex align-items-center justify-content-center flex-shrink-0"
      style={{ width: 40, height: 40 }}
      aria-label={label}
      title={label}
      onClick={() => (to ? navigate(to) : navigate(-1))}
    >
      <i className="bi bi-arrow-left" aria-hidden="true" />
    </Button>
  );
}
