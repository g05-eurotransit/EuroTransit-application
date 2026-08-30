export interface RuntimeConfig {
  keycloakIssuer: string;
  keycloakClientId: string;
  apiBase: string;
}

const CONFIG_URL = "/config/config.json";

let activeConfig: RuntimeConfig | undefined;

function requiredString(value: unknown, key: keyof RuntimeConfig): string {
  if (typeof value !== "string" || value.trim() === "") {
    throw new Error(`Frontend runtime configuration is missing '${key}'`);
  }
  return value.trim();
}

function validateIssuer(value: string): string {
  let issuer: URL;
  try {
    issuer = new URL(value);
  } catch {
    throw new Error("Frontend runtime configuration has an invalid 'keycloakIssuer'");
  }

  const localHttp = issuer.protocol === "http:" &&
    (issuer.hostname === "localhost" || issuer.hostname === "127.0.0.1");
  if (issuer.protocol !== "https:" && !localHttp) {
    throw new Error("Frontend runtime configuration requires an HTTPS Keycloak issuer");
  }
  if (issuer.username || issuer.password || issuer.search || issuer.hash) {
    throw new Error("Frontend runtime configuration has an invalid 'keycloakIssuer'");
  }

  return issuer.toString().replace(/\/$/, "");
}

function validateClientId(value: string): string {
  if (!/^[A-Za-z0-9._-]+$/.test(value)) {
    throw new Error("Frontend runtime configuration has an invalid 'keycloakClientId'");
  }
  return value;
}

function validateApiBase(value: string): string {
  if (!value.startsWith("/") || value.startsWith("//") || value.includes("?") || value.includes("#")) {
    throw new Error("Frontend runtime configuration requires a same-origin 'apiBase'");
  }
  return value.replace(/\/$/, "");
}

export function initializeRuntimeConfig(value: unknown): RuntimeConfig {
  activeConfig = undefined;

  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new Error("Frontend runtime configuration must be a JSON object");
  }

  const candidate = value as Record<string, unknown>;
  const config = Object.freeze({
    keycloakIssuer: validateIssuer(requiredString(candidate.keycloakIssuer, "keycloakIssuer")),
    keycloakClientId: validateClientId(
      requiredString(candidate.keycloakClientId, "keycloakClientId"),
    ),
    apiBase: validateApiBase(requiredString(candidate.apiBase, "apiBase")),
  });

  activeConfig = config;
  return config;
}

export async function loadRuntimeConfig(): Promise<RuntimeConfig> {
  const response = await fetch(CONFIG_URL, {
    cache: "no-store",
    headers: { Accept: "application/json" },
  });

  if (!response.ok) {
    throw new Error(`Unable to load frontend runtime configuration (${response.status})`);
  }

  return initializeRuntimeConfig(await response.json());
}

export function getRuntimeConfig(): RuntimeConfig {
  if (!activeConfig) {
    throw new Error("Frontend runtime configuration was used before initialization");
  }
  return activeConfig;
}
