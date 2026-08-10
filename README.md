# Unora

Aplicativo Android nativo de watch party. O host compartilha a tela e o áudio interno
permitido pelo Android por WebRTC; Cloudflare Workers/Durable Objects transportam apenas
sinalização, presença e chat.

## Requisitos

- Android Studio com Android SDK 36 e JDK 17 ou mais recente
- Node.js 20+ e pnpm
- Conta Cloudflare apenas para publicar o backend
- Android 10 (API 29) ou mais recente

## Android

O endpoint público é uma configuração de build, não um segredo. Depois de publicar o Worker:

```powershell
.\gradlew.bat assembleDebug -PUNORA_EDGE_URL=https://SEU-WORKER.workers.dev -PENABLE_TURN=false
```

O APK será criado em `app/build/outputs/apk/debug/app-debug.apk`. Para instalar em um aparelho
conectado, use o botão Run do Android Studio ou `adb install -r` com esse arquivo.

Para um build release sem assinatura de distribuição:

```powershell
.\gradlew.bat assembleRelease -PUNORA_EDGE_URL=https://SEU-WORKER.workers.dev -PENABLE_TURN=false
```

Antes de publicar na Play Store, configure uma keystore própria e um `signingConfig` de release.
Nenhuma credencial administrativa deve ser adicionada ao projeto Android.

## Backend local

```sh
cd edge
pnpm install
pnpm dev
```

No emulador Android, o host local é `10.0.2.2`; o build debug permite HTTP local:

```powershell
.\gradlew.bat assembleDebug -PUNORA_EDGE_URL=http://10.0.2.2:8787 -PENABLE_TURN=false
```

Builds release recusam cleartext e devem usar HTTPS/WSS.

## Publicar o backend

```sh
cd edge
pnpm install
pnpm exec wrangler login
pnpm deploy
```

O `wrangler.jsonc` já declara o Durable Object e sua migração SQLite. Copie a URL HTTPS exibida
no deploy e passe-a como `UNORA_EDGE_URL` no build Android.

## TURN

STUN funciona sem secrets. TURN é opcional e exige uma chave Cloudflare Realtime TURN. Para
ativá-lo, defina `ENABLE_TURN=true` no `edge/wrangler.jsonc`, grave os secrets e publique novamente:

```sh
pnpm exec wrangler secret put CLOUDFLARE_TURN_KEY_ID
pnpm exec wrangler secret put CLOUDFLARE_TURN_API_TOKEN
pnpm deploy
```

Depois gere o Android com `-PENABLE_TURN=true`. O APK recebe apenas credenciais ICE temporárias.
`TURN_API_BASE` só precisa ser alterado se a Cloudflare mudar o endpoint da API.

## Variáveis e configurações

- Android `UNORA_EDGE_URL`: URL pública do Worker; obrigatória para um APK funcional.
- Android `ENABLE_TURN`: `true` ou `false`; deve acompanhar a configuração do Worker.
- Worker `ENABLE_TURN`: já vem `false`.
- Worker secrets `CLOUDFLARE_TURN_KEY_ID` e `CLOUDFLARE_TURN_API_TOKEN`: necessários somente com TURN.
- Worker `MAX_PARTY_SIZE`: opcional, padrão 8.

## Limitações

- `AudioPlaybackCapture` só recebe áudio de aplicativos que permitem captura. Áudio protegido ou
  bloqueado pela política do aplicativo pode resultar em transmissão somente de vídeo.
- `FLAG_SECURE` e DRM podem produzir tela preta ou impedir a captura; o Unora não contorna essas proteções.
- A topologia é P2P em estrela. Cada espectador adiciona uma cópia ao upload do host; a party é
  limitada inicialmente a oito participantes. Uma versão futura pode substituir esse transporte por SFU.
- A qualidade e a disponibilidade de compartilhamento de um único aplicativo dependem da versão
  do Android e da interface oficial de MediaProjection do fabricante.
