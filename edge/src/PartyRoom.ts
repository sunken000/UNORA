import type { ChatMessage, ClientMessage, Participant, ServerMessage } from "./protocol";
import { parseClientMessage } from "./validation";
import type { Env } from "./types";

const MAX_SOCKET_BYTES = 32 * 1024;
const MAX_CHAT_HISTORY = 100;
const EMPTY_ROOM_TTL_MS = 10 * 60 * 1000;

type RoomMeta = {
  partyId: string;
  hostToken: string;
  hostClientId: string | null;
  createdAt: number;
  shareActive: boolean;
};

type SocketAttachment = {
  clientId?: string;
  nickname?: string;
  joinedAt?: number;
  rate?: Record<string, { startedAt: number; count: number }>;
};

type PartyState = {
  partyId: string;
  participantCount: number;
  maxPartySize: number;
  hostId: string | null;
  shareActive: boolean;
  createdAt: number;
};

export class PartyRoom {
  constructor(private readonly ctx: DurableObjectState, private readonly env: Env) {}

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "POST" && url.pathname === "/create") return this.create(request);
    if (request.method === "GET" && url.pathname === "/state") return this.stateResponse();
    if (request.method === "GET" && url.pathname === "/ws") return this.connect(request);
    return new Response("Not found", { status: 404 });
  }

  async webSocketMessage(socket: WebSocket, message: string | ArrayBuffer): Promise<void> {
    if (typeof message !== "string" || new TextEncoder().encode(message).byteLength > MAX_SOCKET_BYTES) {
      this.fail(socket, "invalid_payload", "Mensagem WebSocket inválida ou grande demais.", true);
      return;
    }
    let raw: unknown;
    try {
      raw = JSON.parse(message);
    } catch {
      this.fail(socket, "invalid_payload", "JSON inválido.");
      return;
    }
    const clientMessage = parseClientMessage(raw);
    if (!clientMessage) {
      this.fail(socket, "invalid_payload", "Mensagem inválida.");
      return;
    }
    if (!this.allow(socket, "all", 80, 60_000)) {
      this.fail(socket, "rate_limited", "Muitas mensagens. Tente novamente em instantes.");
      return;
    }

    const attachment = this.attachment(socket);
    if (!attachment.clientId && clientMessage.type !== "join") {
      this.fail(socket, "join_required", "Envie join antes de outras mensagens.");
      return;
    }
    if (clientMessage.type === "join") {
      await this.join(socket, clientMessage);
    } else {
      await this.handleJoinedMessage(socket, clientMessage, attachment);
    }
  }

  async webSocketClose(socket: WebSocket): Promise<void> {
    await this.leave(socket);
  }

  async webSocketError(socket: WebSocket): Promise<void> {
    await this.leave(socket);
  }

  async alarm(): Promise<void> {
    if (this.participants().length === 0) {
      await this.ctx.storage.deleteAll();
    }
  }

  private async create(request: Request): Promise<Response> {
    const body = await request.json<unknown>().catch(() => null);
    if (!isCreateRequest(body)) return json({ error: "invalid_create_request" }, 400);
    const existing = await this.meta();
    if (existing) return json({ error: "party_exists" }, 409);
    const meta: RoomMeta = {
      partyId: body.partyId,
      hostToken: body.hostToken,
      hostClientId: null,
      createdAt: Date.now(),
      shareActive: false
    };
    await this.ctx.storage.put("meta", meta);
    return json({ partyId: meta.partyId, createdAt: meta.createdAt }, 201);
  }

  private async stateResponse(): Promise<Response> {
    const meta = await this.meta();
    if (!meta) return json({ error: "party_not_found" }, 404);
    return json(this.publicState(meta));
  }

  private async connect(request: Request): Promise<Response> {
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return json({ error: "websocket_upgrade_required" }, 426, { Upgrade: "websocket" });
    }
    if (!(await this.meta())) return json({ error: "party_not_found" }, 404);
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    this.ctx.acceptWebSocket(server);
    return new Response(null, { status: 101, webSocket: client });
  }

  private async join(socket: WebSocket, message: Extract<ClientMessage, { type: "join" }>): Promise<void> {
    if (this.attachment(socket).clientId) {
      this.fail(socket, "already_joined", "Esta conexão já entrou na party.");
      return;
    }
    const meta = await this.meta();
    if (!meta) {
      this.fail(socket, "party_not_found", "Esta party não existe mais.", true);
      return;
    }
    const sameClient = this.socketsForClient(message.clientId);
    const uniqueParticipants = this.participants();
    if (sameClient.length === 0 && uniqueParticipants.length >= maxPartySize(this.env)) {
      this.fail(socket, "party_full", "Esta party está cheia.", true);
      return;
    }

    const joinedAt = Date.now();
    socket.serializeAttachment({ clientId: message.clientId, nickname: message.nickname, joinedAt } satisfies SocketAttachment);
    for (const prior of sameClient) prior.close(4001, "Replaced by a newer connection");

    let hostChanged = false;
    if (message.hostToken && secureEquals(message.hostToken, meta.hostToken) && meta.hostClientId !== message.clientId) {
      meta.hostClientId = message.clientId;
      await this.saveMeta(meta);
      hostChanged = true;
    }
    await this.ctx.storage.deleteAlarm();
    const self = this.withHost(this.participant(socket)!, this.activeHostId(meta));
    this.send(socket, {
      type: "joined",
      partyId: meta.partyId,
      self,
      hostId: this.activeHostId(meta),
      participants: this.withHostFlags(this.participants(), this.activeHostId(meta)),
      chatHistory: await this.chatHistory(),
      shareActive: meta.shareActive
    });
    this.broadcast({ type: "participant_joined", participant: self }, socket);
    this.broadcastPeerList(meta);
    if (hostChanged) this.broadcast({ type: "host_changed", hostId: this.activeHostId(meta) });
  }

  private async handleJoinedMessage(socket: WebSocket, message: Exclude<ClientMessage, { type: "join" }>, attachment: SocketAttachment): Promise<void> {
    const meta = await this.meta();
    if (!meta || !attachment.clientId) return;
    switch (message.type) {
      case "ping":
        this.send(socket, { type: "pong", ...(message.sentAt === undefined ? {} : { sentAt: message.sentAt }) });
        return;
      case "chat_message":
        if (!this.allow(socket, "chat", 8, 10_000)) {
          this.fail(socket, "rate_limited", "Envie mensagens mais devagar.");
          return;
        }
        await this.chat(meta, attachment, message);
        return;
      case "share_started":
      case "share_stopped":
        await this.updateShareState(socket, meta, message.type === "share_started");
        return;
      case "offer":
      case "answer":
      case "ice_candidate":
      case "ice_restart":
        if (!this.allow(socket, "signal", 60, 60_000)) {
          this.fail(socket, "rate_limited", "Sinalização em excesso.");
          return;
        }
        this.signal(socket, attachment.clientId, message);
        return;
    }
  }

  private async chat(meta: RoomMeta, attachment: SocketAttachment, message: Extract<ClientMessage, { type: "chat_message" }>): Promise<void> {
    const history = await this.chatHistory();
    // Retries use the same client message id. Do not duplicate persisted chat.
    const duplicate = history.find((entry) => entry.clientId === attachment.clientId && entry.id === message.clientMessageId);
    if (duplicate) {
      this.sendToClient(attachment.clientId!, { type: "chat_message", message: duplicate });
      return;
    }
    const chat: ChatMessage = {
      id: message.clientMessageId,
      clientId: attachment.clientId!,
      nickname: attachment.nickname!,
      text: message.text,
      sentAt: Date.now()
    };
    history.push(chat);
    await this.ctx.storage.put("chat", history.slice(-MAX_CHAT_HISTORY));
    this.broadcast({ type: "chat_message", message: chat });
  }

  private async updateShareState(socket: WebSocket, meta: RoomMeta, sharing: boolean): Promise<void> {
    const clientId = this.attachment(socket).clientId;
    if (!clientId || this.activeHostId(meta) !== clientId) {
      this.fail(socket, "host_required", "Somente o host pode controlar a transmissão.");
      return;
    }
    if (meta.shareActive === sharing) return;
    meta.shareActive = sharing;
    await this.saveMeta(meta);
    this.broadcast(sharing ? { type: "share_started", hostId: clientId } : { type: "share_stopped", hostId: clientId });
  }

  private signal(socket: WebSocket, fromId: string, message: Extract<ClientMessage, { type: "offer" | "answer" | "ice_candidate" | "ice_restart" }>): void {
    if (message.targetId === fromId) {
      this.fail(socket, "invalid_target", "Não é possível sinalizar para si mesmo.");
      return;
    }
    const target = this.socketsForClient(message.targetId).at(-1);
    if (!target) {
      this.fail(socket, "participant_not_found", "Este participante não está conectado.");
      return;
    }
    switch (message.type) {
      case "offer": this.send(target, { type: "offer", fromId, description: message.description }); break;
      case "answer": this.send(target, { type: "answer", fromId, description: message.description }); break;
      case "ice_candidate": this.send(target, { type: "ice_candidate", fromId, candidate: message.candidate }); break;
      case "ice_restart": this.send(target, { type: "ice_restart", fromId }); break;
    }
  }

  private async leave(socket: WebSocket): Promise<void> {
    const departing = this.participant(socket);
    if (!departing) return;
    socket.serializeAttachment({});
    // A reconnect with the same client id may already have replaced this socket.
    if (this.socketsForClient(departing.clientId).length > 0) return;
    const meta = await this.meta();
    if (!meta) return;
    this.broadcast({ type: "participant_left", clientId: departing.clientId });
    if (meta.hostClientId === departing.clientId) {
      const successor = this.participants().sort((a, b) => a.joinedAt - b.joinedAt)[0]?.clientId ?? null;
      meta.hostClientId = successor;
      const wasSharing = meta.shareActive;
      if (wasSharing) meta.shareActive = false;
      await this.saveMeta(meta);
      if (wasSharing) this.broadcast({ type: "share_stopped", hostId: departing.clientId });
      this.broadcast({ type: "host_changed", hostId: successor });
    }
    this.broadcastPeerList(meta);
    if (this.participants().length === 0) await this.ctx.storage.setAlarm(Date.now() + EMPTY_ROOM_TTL_MS);
  }

  private participants(): Participant[] {
    const seen = new Map<string, Participant>();
    for (const socket of this.ctx.getWebSockets()) {
      const participant = this.participant(socket);
      if (participant && !seen.has(participant.clientId)) seen.set(participant.clientId, participant);
    }
    return [...seen.values()].sort((a, b) => a.joinedAt - b.joinedAt);
  }

  private participant(socket: WebSocket): Participant | null {
    const attachment = this.attachment(socket);
    if (!attachment.clientId || !attachment.nickname || !attachment.joinedAt) return null;
    return { clientId: attachment.clientId, nickname: attachment.nickname, isHost: false, joinedAt: attachment.joinedAt };
  }

  private socketsForClient(clientId: string): WebSocket[] {
    return this.ctx.getWebSockets().filter((socket) => this.attachment(socket).clientId === clientId);
  }

  private activeHostId(meta: RoomMeta): string | null {
    return meta.hostClientId && this.socketsForClient(meta.hostClientId).length > 0 ? meta.hostClientId : null;
  }

  private async chatHistory(): Promise<ChatMessage[]> {
    return (await this.ctx.storage.get<ChatMessage[]>("chat")) ?? [];
  }

  private async meta(): Promise<RoomMeta | undefined> {
    return this.ctx.storage.get<RoomMeta>("meta");
  }

  private saveMeta(meta: RoomMeta): Promise<void> {
    return this.ctx.storage.put("meta", meta);
  }

  private publicState(meta: RoomMeta): PartyState {
    return {
      partyId: meta.partyId,
      participantCount: this.participants().length,
      maxPartySize: maxPartySize(this.env),
      hostId: this.activeHostId(meta),
      shareActive: meta.shareActive,
      createdAt: meta.createdAt
    };
  }

  private broadcastPeerList(meta: RoomMeta): void {
    const hostId = this.activeHostId(meta);
    this.broadcast({ type: "peer_list", participants: this.withHostFlags(this.participants(), hostId), hostId });
  }

  private withHost(participant: Participant, hostId: string | null): Participant {
    return { ...participant, isHost: participant.clientId === hostId };
  }

  private withHostFlags(participants: Participant[], hostId: string | null): Participant[] {
    return participants.map((participant) => this.withHost(participant, hostId));
  }

  private broadcast(message: ServerMessage, excluded?: WebSocket): void {
    for (const socket of this.ctx.getWebSockets()) if (socket !== excluded) this.send(socket, message);
  }

  private sendToClient(clientId: string, message: ServerMessage): void {
    for (const socket of this.socketsForClient(clientId)) this.send(socket, message);
  }

  private send(socket: WebSocket, message: ServerMessage): void {
    try { socket.send(JSON.stringify(message)); } catch { /* socket is closing */ }
  }

  private fail(socket: WebSocket, code: string, message: string, close = false): void {
    this.send(socket, { type: "error", code, message });
    if (close) socket.close(1008, code);
  }

  private attachment(socket: WebSocket): SocketAttachment {
    return (socket.deserializeAttachment() as SocketAttachment | null) ?? {};
  }

  private allow(socket: WebSocket, bucket: string, maximum: number, windowMs: number): boolean {
    const attachment = this.attachment(socket);
    const rate = attachment.rate ?? {};
    const now = Date.now();
    const current = rate[bucket];
    if (!current || now - current.startedAt >= windowMs) {
      rate[bucket] = { startedAt: now, count: 1 };
    } else if (current.count >= maximum) {
      return false;
    } else {
      current.count += 1;
    }
    socket.serializeAttachment({ ...attachment, rate });
    return true;
  }
}

function maxPartySize(env: Env): number {
  const parsed = Number.parseInt(env.MAX_PARTY_SIZE || "8", 10);
  return Number.isFinite(parsed) ? Math.min(32, Math.max(2, parsed)) : 8;
}

function isCreateRequest(value: unknown): value is { partyId: string; hostToken: string } {
  return typeof value === "object" && value !== null
    && /^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}$/.test((value as { partyId?: unknown }).partyId as string)
    && /^[A-Za-z0-9_-]{32,256}$/.test((value as { hostToken?: unknown }).hostToken as string);
}

function secureEquals(left: string, right: string): boolean {
  if (left.length !== right.length) return false;
  let result = 0;
  for (let i = 0; i < left.length; i += 1) result |= left.charCodeAt(i) ^ right.charCodeAt(i);
  return result === 0;
}

function json(payload: unknown, status = 200, headers: HeadersInit = {}): Response {
  return new Response(JSON.stringify(payload), { status, headers: { "Content-Type": "application/json; charset=utf-8", ...headers } });
}
