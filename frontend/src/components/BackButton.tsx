import { useNavigate } from "react-router-dom";
import { Button } from "react-bootstrap";

export function BackButton() {
  const navigate = useNavigate();
  return (
    <Button
      variant="outline-primary"
      className="rounded-circle p-0 d-flex align-items-center justify-content-center flex-shrink-0"
      style={{ width: 40, height: 40 }}
      aria-label="Go back"
      onClick={() => navigate(-1)}
    >
      <i className="bi bi-arrow-left" aria-hidden="true" />
    </Button>
  );
}
