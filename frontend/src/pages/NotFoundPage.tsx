import { Link } from "react-router-dom";

/** Catch-all for any path that doesn't match a route (see App.tsx's `path="*"`). */
export function NotFoundPage() {
  return (
    <div className="text-center py-5">
      <i className="bi bi-signpost-split fs-1 text-secondary-emphasis d-block mb-3" aria-hidden="true" />
      <h1 className="fw-bold mb-2">Page not found</h1>
      <p className="text-secondary-emphasis mb-4">
        The page you're looking for doesn't exist or may have been moved.
      </p>
      <Link to="/" className="btn btn-primary fw-semibold">
        Back to routes
      </Link>
    </div>
  );
}
