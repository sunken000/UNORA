export interface Env {
  PARTY_ROOM: DurableObjectNamespace;
  MAX_PARTY_SIZE?: string;
  ENABLE_TURN?: string;
  TURN_TTL_SECONDS?: string;
  TURN_API_BASE?: string;
  CLOUDFLARE_TURN_KEY_ID?: string;
  CLOUDFLARE_TURN_API_TOKEN?: string;
  /** Optional single browser origin for the HTTP API. Android does not use CORS. */
  ALLOWED_ORIGIN?: string;
}
