import { PartyRoom } from "./PartyRoom";
import { isPartyId } from "./validation";
import { getIceServers } from "./turn";
import type { Env } from "./types";

export { PartyRoom };

const PARTY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const PARTY_CREATE_ATTEMPTS = 5;
const edgeRateBuckets = new Map<string, { startedAt: number; count: number }>();

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const corsHeaders = cors(request, env);
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: corsHeaders });

    try {
      if (request.method === "POST" && url.pathname === "/api/party") {
        if (!allowEdgeRequest(request, "party", 8, 60_000)) {
          return withCors(json({ error: "rate_limited", message: "Muitas parties criadas. Tente novamente em instantes." }, 429), corsHeaders);
        }
        return withCors(await createParty(request, env, url), corsHeaders);
      }
      if (request.method === "GET" && url.pathname === "/api/ice") {
        if (!allowEdgeRequest(request, "ice", 30, 60_000)) {
          return withCors(json({ error: "rate_limited", message: "Muitas solicitações ICE. Tente novamente em instantes." }, 429), corsHeaders);
        }
        const ice = await getIceServers(env);
        return withCors(json(ice, 200, { "Cache-Control": "no-store" }), corsHeaders);
      }
      const partyRoute = parsePartyRoute(url.pathname);
      if (partyRoute) {
        if (!isPartyId(partyRoute.partyId)) return withCors(json({ error: "invalid_party_id" }, 400), corsHeaders);
        if (partyRoute.action === "ws" && !webSocketOriginAllowed(request, env)) {
          return withCors(json({ error: "origin_not_allowed" }, 403), corsHeaders);
        }
        const id = env.PARTY_ROOM.idFromName(partyRoute.partyId);
        const room = env.PARTY_ROOM.get(id);
        const target = new URL(request.url);
        target.pathname = partyRoute.action === "ws" ? "/ws" : "/state";
        const roomResponse = await room.fetch(new Request(target.toString(), request));
        // Reconstructing a Response drops Cloudflare's `webSocket` field, so
        // WebSocket upgrades must be returned unchanged. CORS does not govern
        // WebSocket handshakes; a configured origin is checked above instead.
        return partyRoute.action === "ws" ? roomResponse : withCors(roomResponse, corsHeaders);
      }
      return withCors(json({ error: "not_found" }, 404), corsHeaders);
    } catch (error) {
      console.error("Unora edge request failed", error);
      return withCors(json({ error: "internal_error", message: "Não foi possível concluir esta solicitação." }, 500), corsHeaders);
    }
  }
} satisfies ExportedHandler<Env>;

async function createParty(request: Request, env: Env, url: URL): Promise<Response> {
  const contentType = request.headers.get("Content-Type") || "";
  if (contentType && !contentType.includes("application/json")) return json({ error: "content_type_must_be_json" }, 415);
  for (let attempt = 0; attempt < PARTY_CREATE_ATTEMPTS; attempt += 1) {
    const partyId = randomPartyId();
    const hostToken = randomToken();
    const room = env.PARTY_ROOM.get(env.PARTY_ROOM.idFromName(partyId));
    const created = await room.fetch("https://party.internal/create", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ partyId, hostToken })
    });
    if (created.status === 409) continue;
    if (!created.ok) throw new Error(`Party creation failed (${created.status})`);
    return json({ partyId, hostToken, joinUrl: `${url.origin}/p/${partyId}` }, 201, { "Cache-Control": "no-store" });
  }
  return json({ error: "party_creation_conflict" }, 503);
}

function parsePartyRoute(pathname: string): { partyId: string; action: "state" | "ws" } | null {
  const match = /^\/api\/party\/([^/]+)(?:\/(ws))?$/.exec(pathname);
  if (!match) return null;
  return { partyId: match[1].toUpperCase(), action: match[2] === "ws" ? "ws" : "state" };
}

function randomPartyId(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(6));
  return Array.from(bytes, (byte) => PARTY_ALPHABET[byte % PARTY_ALPHABET.length]).join("");
}

function randomToken(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return bytesToBase64Url(bytes);
}

function bytesToBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function cors(request: Request, env: Env): Headers {
  const headers = new Headers({
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type",
    "Vary": "Origin"
  });
  const origin = request.headers.get("Origin");
  if (!env.ALLOWED_ORIGIN) headers.set("Access-Control-Allow-Origin", "*");
  else if (origin === env.ALLOWED_ORIGIN) headers.set("Access-Control-Allow-Origin", origin);
  return headers;
}

function webSocketOriginAllowed(request: Request, env: Env): boolean {
  const origin = request.headers.get("Origin");
  return !env.ALLOWED_ORIGIN || !origin || origin === env.ALLOWED_ORIGIN;
}

/** Best-effort Worker-isolate limit for public endpoints; socket limits live in the DO. */
function allowEdgeRequest(request: Request, bucket: string, maximum: number, windowMs: number): boolean {
  const key = `${bucket}:${request.headers.get("CF-Connecting-IP") || "anonymous"}`;
  const now = Date.now();
  const current = edgeRateBuckets.get(key);
  if (!current || now - current.startedAt >= windowMs) {
    edgeRateBuckets.set(key, { startedAt: now, count: 1 });
    // Bound memory in long-lived isolates while keeping the limiter deliberately simple.
    if (edgeRateBuckets.size > 2_000) edgeRateBuckets.delete(edgeRateBuckets.keys().next().value!);
    return true;
  }
  if (current.count >= maximum) return false;
  current.count += 1;
  return true;
}

function withCors(response: Response, headers: Headers): Response {
  const merged = new Headers(response.headers);
  headers.forEach((value, key) => merged.set(key, value));
  return new Response(response.body, response);
}

function json(payload: unknown, status = 200, headers: HeadersInit = {}): Response {
  return new Response(JSON.stringify(payload), { status, headers: { "Content-Type": "application/json; charset=utf-8", ...headers } });
}
