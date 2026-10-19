import { UserManager } from "oidc-client-ts";

/**
 * The target of the silent sign-in iframe.
 *
 * It hands the authorization response back to the window that opened the iframe and stops there —
 * the application does the token exchange with its own settings, which is why the ones below are
 * structural only. Pointing the iframe at `/callback` instead would boot the entire SPA inside it,
 * on first load and again on every silent token renewal.
 */
new UserManager({ authority: "", client_id: "", redirect_uri: window.location.href })
  .signinSilentCallback()
  .catch(() => {
    // Nothing to report from in here: the application times out and treats it as signed out.
  });
