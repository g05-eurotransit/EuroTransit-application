/** Shape passed as oidc-client-ts's custom `state` — see ProtectedRoute and CallbackPage. */
export interface PostLoginRedirect {
  pathname: string;
  search: string;
  state: unknown;
}

export function isPostLoginRedirect(value: unknown): value is PostLoginRedirect {
  return !!value && typeof value === "object" && typeof (value as PostLoginRedirect).pathname === "string";
}
