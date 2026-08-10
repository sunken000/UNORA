/**
 * JSON protocol used by desktop and future Android clients. It deliberately
 * contains no Worker/DO types, so it can live in a shared package unchanged.
 */
export const PARTY_ID_PATTERN = /^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}$/;

export type Participant = {
  clientId: string;
  nickname: string;
  isHost: boolean;
  joinedAt: number;
};

export type ChatMessage = {
  id: string;
  clientId: string;
  nickname: string;
  text: string;
  sentAt: number;
};

export type SessionDescriptionPayload = {
  type: "offer" | "answer";
  sdp: string;
};

export type IceCandidatePayload = {
  candidate: string;
  sdpMid?: string | null;
  sdpMLineIndex?: number | null;
  usernameFragment?: string | null;
};

export type ClientMessage =
  | { type: "join"; clientId: string; nickname: string; hostToken?: string }
  | { type: "offer"; targetId: string; description: SessionDescriptionPayload }
  | { type: "answer"; targetId: string; description: SessionDescriptionPayload }
  | { type: "ice_candidate"; targetId: string; candidate: IceCandidatePayload }
  | { type: "ice_restart"; targetId: string }
  | { type: "chat_message"; clientMessageId: string; text: string }
  | { type: "share_started" }
  | { type: "share_stopped" }
  | { type: "ping"; sentAt?: number };

export type ServerMessage =
  | { type: "joined"; partyId: string; self: Participant; hostId: string | null; participants: Participant[]; chatHistory: ChatMessage[]; shareActive: boolean }
  | { type: "participant_joined"; participant: Participant }
  | { type: "participant_left"; clientId: string }
  | { type: "peer_list"; participants: Participant[]; hostId: string | null }
  | { type: "offer"; fromId: string; description: SessionDescriptionPayload }
  | { type: "answer"; fromId: string; description: SessionDescriptionPayload }
  | { type: "ice_candidate"; fromId: string; candidate: IceCandidatePayload }
  | { type: "ice_restart"; fromId: string }
  | { type: "chat_message"; message: ChatMessage }
  | { type: "share_started"; hostId: string }
  | { type: "share_stopped"; hostId: string }
  | { type: "host_changed"; hostId: string | null }
  | { type: "pong"; sentAt?: number }
  | { type: "error"; code: string; message: string };
