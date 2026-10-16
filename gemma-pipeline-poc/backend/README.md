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
