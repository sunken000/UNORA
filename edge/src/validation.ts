import type { ClientMessage, IceCandidatePayload, SessionDescriptionPayload } from "./protocol";

const CLIENT_ID = /^[a-zA-Z0-9_-]{8,96}$/;
const MESSAGE_ID = /^[a-zA-Z0-9_-]{8,96}$/;
const MAX_SDP = 24_000;
const MAX_CANDIDATE = 2_048;

export function isPartyId(value: string): boolean {
  return /^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}$/.test(value);
}

export function parseClientMessage(raw: unknown): ClientMessage | null {
  if (!isRecord(raw) || typeof raw.type !== "string") return null;
  switch (raw.type) {
    case "join":
      return validClientId(raw.clientId) && validNickname(raw.nickname) && optionalToken(raw.hostToken)
        ? { type: "join", clientId: raw.clientId, nickname: raw.nickname.trim(), ...(typeof raw.hostToken === "string" ? { hostToken: raw.hostToken } : {}) }
        : null;
    case "offer":
    case "answer": {
      const description = parseDescription(raw.description, raw.type);
      return validClientId(raw.targetId) && description ? { type: raw.type, targetId: raw.targetId, description } : null;
    }
    case "ice_candidate": {
      const candidate = parseCandidate(raw.candidate);
      return validClientId(raw.targetId) && candidate ? { type: "ice_candidate", targetId: raw.targetId, candidate } : null;
    }
    case "ice_restart":
      return validClientId(raw.targetId) ? { type: "ice_restart", targetId: raw.targetId } : null;
    case "chat_message":
      return typeof raw.clientMessageId === "string" && MESSAGE_ID.test(raw.clientMessageId) && validChat(raw.text)
        ? { type: "chat_message", clientMessageId: raw.clientMessageId, text: raw.text.trim() }
        : null;
    case "share_started":
    case "share_stopped":
      return { type: raw.type };
    case "ping":
      return typeof raw.sentAt === "number" && Number.isFinite(raw.sentAt)
        ? { type: "ping", sentAt: raw.sentAt }
        : { type: "ping" };
    default:
      return null;
  }
}

export function validClientId(value: unknown): value is string {
  return typeof value === "string" && CLIENT_ID.test(value);
}

function validNickname(value: unknown): value is string {
  return typeof value === "string" && value.trim().length >= 1 && value.trim().length <= 32;
}

function optionalToken(value: unknown): boolean {
  return value === undefined || (typeof value === "string" && /^[A-Za-z0-9_-]{32,256}$/.test(value));
}

function validChat(value: unknown): value is string {
  return typeof value === "string" && value.trim().length >= 1 && value.trim().length <= 1_000;
}

function parseDescription(value: unknown, expected: "offer" | "answer"): SessionDescriptionPayload | null {
  if (!isRecord(value) || value.type !== expected || typeof value.sdp !== "string" || value.sdp.length < 1 || value.sdp.length > MAX_SDP) return null;
  return { type: expected, sdp: value.sdp };
}

function parseCandidate(value: unknown): IceCandidatePayload | null {
  if (!isRecord(value) || typeof value.candidate !== "string" || value.candidate.length > MAX_CANDIDATE) return null;
  if (!optionalNullableString(value.sdpMid, 128) || !optionalNullableNumber(value.sdpMLineIndex) || !optionalNullableString(value.usernameFragment, 256)) return null;
  return {
    candidate: value.candidate,
    ...(value.sdpMid !== undefined ? { sdpMid: value.sdpMid as string | null } : {}),
    ...(value.sdpMLineIndex !== undefined ? { sdpMLineIndex: value.sdpMLineIndex as number | null } : {}),
    ...(value.usernameFragment !== undefined ? { usernameFragment: value.usernameFragment as string | null } : {})
  };
}

function optionalNullableString(value: unknown, max: number): boolean {
  return value === undefined || value === null || (typeof value === "string" && value.length <= max);
}

function optionalNullableNumber(value: unknown): boolean {
  return value === undefined || value === null || (typeof value === "number" && Number.isInteger(value) && value >= 0 && value < 65_536);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
