import type { Env } from "./types";

export type IceServer = {
  // Keep this as an array: Android's kotlinx schema deliberately uses
  // List<String>, and WebRTC accepts either the single or multi-URL form.
  urls: string[];
  username?: string;
  credential?: string;
};

const DEFAULT_STUN: IceServer = { urls: ["stun:stun.cloudflare.com:3478", "stun:stun.cloudflare.com:53"] };
const DEFAULT_TURN_API = "https://rtc.live.cloudflare.com/v1/turn";

/**
 * Returns only short-lived credentials. The TURN key and API token never leave
 * the Worker. Cloudflare already returns the WebRTC-compatible iceServers
 * shape, so no provider credentials are exposed by this adapter.
 */
export async function getIceServers(env: Env): Promise<{ iceServers: IceServer[]; turnEnabled: boolean; expiresInSeconds?: number }> {
  if (env.ENABLE_TURN?.toLowerCase() !== "true") {
    return { iceServers: [DEFAULT_STUN], turnEnabled: false };
  }

  if (!env.CLOUDFLARE_TURN_KEY_ID || !env.CLOUDFLARE_TURN_API_TOKEN) {
    throw new Error("TURN is enabled but Cloudflare TURN secrets are not configured");
  }

  const ttl = clampInteger(env.TURN_TTL_SECONDS, 3600, 60, 86_400);
  const base = (env.TURN_API_BASE || DEFAULT_TURN_API).replace(/\/$/, "");
  const response = await fetch(`${base}/keys/${encodeURIComponent(env.CLOUDFLARE_TURN_KEY_ID)}/credentials/generate-ice-servers`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${env.CLOUDFLARE_TURN_API_TOKEN}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ ttl })
  });

  if (!response.ok) {
    throw new Error(`Cloudflare TURN credential request failed (${response.status})`);
  }

  const payload: unknown = await response.json();
  if (!isIcePayload(payload)) {
    throw new Error("Cloudflare TURN returned an invalid ICE server response");
  }
  return { iceServers: payload.iceServers.map(normalizeIceServer), turnEnabled: true, expiresInSeconds: ttl };
}

function clampInteger(value: string | undefined, fallback: number, minimum: number, maximum: number): number {
  const parsed = Number.parseInt(value || "", 10);
  return Number.isFinite(parsed) ? Math.min(maximum, Math.max(minimum, parsed)) : fallback;
}

function isIcePayload(value: unknown): value is { iceServers: Array<{ urls: string | string[]; username?: string; credential?: string }> } {
  if (typeof value !== "object" || value === null || !Array.isArray((value as { iceServers?: unknown }).iceServers)) return false;
  return (value as { iceServers: unknown[] }).iceServers.every((server) => {
    if (typeof server !== "object" || server === null) return false;
    const urls = (server as { urls?: unknown }).urls;
    return typeof urls === "string" || (Array.isArray(urls) && urls.every((url) => typeof url === "string"));
  });
}

function normalizeIceServer(server: { urls: string | string[]; username?: string; credential?: string }): IceServer {
  return {
    urls: typeof server.urls === "string" ? [server.urls] : server.urls,
    ...(typeof server.username === "string" ? { username: server.username } : {}),
    ...(typeof server.credential === "string" ? { credential: server.credential } : {})
  };
}
