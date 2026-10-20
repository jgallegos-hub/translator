# Online MT backend (Claude Haiku 5.5 via Supabase Edge Function)

`supabase/functions/translate` relays a Spanish transcript to Claude Haiku 5.5 and streams the
English back as plain text. The Anthropic key lives only in the function's secrets; the app
authenticates with its own token (`x-app-token`).

Deployed to the `illinois-libraries` project (ref `akoklyhljdrtuvztchnw`, us-east-1):
`https://akoklyhljdrtuvztchnw.supabase.co/functions/v1/translate`

## Deploy / rotate
```bash
npx supabase login
npx supabase secrets set --env-file <tmp file with ANTHROPIC_API_KEY=… and APP_TOKEN=…> --project-ref akoklyhljdrtuvztchnw
npx supabase functions deploy translate --project-ref akoklyhljdrtuvztchnw --no-verify-jwt --use-api
```
`backend/.app_token` (gitignored) holds the app token. Never put either secret in the APK or git.

## Phone config
`/sdcard/Download/translator/online.json` = `{"url": "<function url>", "token": "<app token>"}`
(pushed with adb). Mode "En línea: Claude Haiku (respaldo Auto)" in the app.

## Local test
`deno run --allow-net --allow-env supabase/functions/translate/index.ts` with both secrets in the
environment, then `adb reverse tcp:8000 tcp:8000` and url `http://127.0.0.1:8000/` (cleartext
allowed only for 127.0.0.1/localhost, see `res/xml/network_security_config.xml`).

## Latency (2026-10-16, home network, first text)
Direct API ~0.55 s p50 · local Deno ~0.55 s · Supabase ~0.9 s p50 (p90 1.0–2.2 s, cold start ~1.8 s).
Region pinning (`x-region`) made no difference.

# Own relay with voice (backend/relay) — Fly.io

Deployed 2026-10-20 as Fly app **`traductor-t2c`** (region `ord`, 1 always-on shared-cpu-1x 512 MB):
`https://traductor-t2c.fly.dev` (`POST /speak`, `GET /health`, `GET /health?warm=1` with the app token).
Secrets (Fly): ANTHROPIC_API_KEY, ELEVENLABS_API_KEY, APP_TOKEN — imported from a temp file
(`cmd /c "flyctl secrets import --app traductor-t2c < file"`; PowerShell 5.1 pipes add a BOM).
Deploy: `cd backend && flyctl deploy --remote-only --ha=false`. Phone config:
`/sdcard/Download/translator/server.json` = `{"url": "https://traductor-t2c.fly.dev", "token": "<app token>"}`.
Local run: `deno run --allow-net --allow-env --allow-read relay/main.ts` (+ DEBUG_WAV_DIR to dump replies).
PC → Fly first audio p50 ~1.03 s (local relay ~0.91 s).
