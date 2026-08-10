# Unora Edge

Cloudflare Worker and SQLite-backed Durable Object for Unora. It only carries
WebSocket signalling, presence and chat; video and system audio stay on the
WebRTC peer-to-peer connections.

## Local use

```sh
cd edge
pnpm install
pnpm dev
```

Set optional TURN secrets in `.dev.vars` (copy `.dev.vars.example`). With the
default `ENABLE_TURN=false`, `/api/ice` returns only Cloudflare's public STUN
server and requires no secret or paid resource.

Use `pnpm typecheck` to validate the Worker, and rerun `pnpm cf-typegen` after
changing bindings in `wrangler.jsonc`.

## Deploy

```sh
cd edge
pnpm exec wrangler login
pnpm deploy
```

The `v1` Durable Object migration is applied by Wrangler on the first deploy.
Copy the resulting `https://unora-edge.<account>.workers.dev` URL into the
Android app's public `UNORA_EDGE_URL` build setting.

Only set the two secrets and change `ENABLE_TURN` to `true` after provisioning
a Cloudflare Realtime TURN key. `TURN_API_BASE` normally remains
`https://rtc.live.cloudflare.com/v1/turn`. The worker asks Cloudflare for short-lived
credentials; the permanent API token is never sent to Electron/Android.

To enable TURN, first set `ENABLE_TURN` to `true` in `wrangler.jsonc`, then:

```sh
pnpm exec wrangler secret put CLOUDFLARE_TURN_KEY_ID
pnpm exec wrangler secret put CLOUDFLARE_TURN_API_TOKEN
pnpm deploy
```

`MAX_PARTY_SIZE` is a Worker variable (default: 8). It includes the host and
is capped by the Worker at 32. Rooms are cleared ten minutes after the last
participant leaves; media never traverses the Worker or Durable Object.

## HTTP API

- `POST /api/party` creates a party and returns `{ partyId, joinUrl, hostToken }`.
- `GET /api/party/:partyId` returns non-sensitive party state.
- `GET /api/party/:partyId/ws` upgrades to the room WebSocket.
- `GET /api/ice` returns STUN and, if explicitly enabled, short-lived TURN ICE
  credentials.

The browser/client sends `join` as its first WebSocket message. Full request
and event shapes are in `src/protocol.ts`; this file is intentionally free of
Cloudflare-specific types so it can be copied or shared with the Android app.
