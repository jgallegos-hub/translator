// Own relay for the translator app: Spanish transcript in, translated English *speech* out.
// Claude Haiku 5.5 (streaming) -> clause chunker -> ElevenLabs streaming TTS (Flash v2.5),
// clauses synthesised as soon as they close and streamed back in order. Plain Deno HTTP
// server so it runs locally (adb reverse) or in any container host (Fly.io ord, AWS, Azure).
//
// POST /speak  { "text": "<spanish>", "history": ["<previous spanish>", ...] }
//   header x-app-token: <APP_TOKEN>
//   response: application/octet-stream of frames  [type u8][length u32 BE][payload]
//     1 = clause text (UTF-8)   2 = audio, PCM s16le 24 kHz mono   3 = end (JSON stats)
//     4 = error (UTF-8)
// GET /health -> "ok" (warm-up / liveness)
// Env: ANTHROPIC_API_KEY, ELEVENLABS_API_KEY, APP_TOKEN, VOICE_ID (default Eric),
//      TTS_MODEL (default eleven_flash_v2_5), PORT (default 8080).
import Anthropic from "npm:@anthropic-ai/sdk@0.131.0";
import { SYSTEM } from "../shared/interpreter_prompt.ts";

const MODEL = "claude-haiku-5-5";
const MAX_TEXT = 1000;
const MAX_HISTORY = 3;
const VOICE_ID = Deno.env.get("VOICE_ID") ?? "cjVigY5qzO86Huf0OWal"; // ElevenLabs "Eric"
const TTS_MODEL = Deno.env.get("TTS_MODEL") ?? "eleven_flash_v2_5";
const ELEVEN_KEY = Deno.env.get("ELEVENLABS_API_KEY") ?? "";
const APP_TOKEN = Deno.env.get("APP_TOKEN") ?? "";

const anthropic = new Anthropic({ apiKey: Deno.env.get("ANTHROPIC_API_KEY"), maxRetries: 1 });
const enc = new TextEncoder();

function sameToken(a: string, b: string): boolean {
  if (!b || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function frame(type: number, payload: Uint8Array): Uint8Array {
  const out = new Uint8Array(5 + payload.length);
  out[0] = type;
  new DataView(out.buffer).setUint32(1, payload.length);
  out.set(payload, 5);
  return out;
}

/** Cut at . , ! ? ; : followed by whitespace — or at a final ! ? that ends the buffer — once the
 *  clause has >= 3 words. Never mid-phrase. */
export function takeClause(buf: string): [string, string] | null {
  const re = /[.,!?;:](\s+)|[!?]$/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(buf)) !== null) {
    const end = m.index + 1;
    const clause = buf.slice(0, end).trim();
    if (clause.split(/\s+/).length >= 3) return [clause, buf.slice(end + (m[1]?.length ?? 0))];
  }
  return null;
}

/** Starts ElevenLabs synthesis now; the caller reads the body later, in clause order. */
function startTts(text: string, previous: string): Promise<Response> {
  return fetch(`https://api.elevenlabs.io/v1/text-to-speech/${VOICE_ID}/stream?output_format=pcm_24000`, {
    method: "POST",
    headers: { "xi-api-key": ELEVEN_KEY, "content-type": "application/json" },
    // previous_text keeps prosody continuous across clause requests.
    body: JSON.stringify({ text, model_id: TTS_MODEL, ...(previous ? { previous_text: previous } : {}) }),
  });
}

async function speak(text: string, history: string[], out: WritableStreamDefaultWriter<Uint8Array>) {
  const t0 = performance.now();
  const stats: Record<string, number | string> = {};
  const prev = history.length ? history.map((h) => `- ${h}`).join("\n") : "(none)";
  const pending: { clause: string; tts: Promise<Response> }[] = [];
  let spoken = "";
  let firstAudioSent = false;

  // Writer: drains clauses in order while Haiku is still generating later ones.
  let producerDone = false;
  let wake: (() => void) | null = null;
  const notify = () => { wake?.(); wake = null; };
  const writer = (async () => {
    let i = 0;
    while (true) {
      if (i >= pending.length) {
        if (producerDone) break;
        await new Promise<void>((r) => (wake = r));
        continue;
      }
      const { clause, tts } = pending[i++];
      await out.write(frame(1, enc.encode(clause)));
      const res = await tts;
      if (!res.ok || !res.body) {
        await out.write(frame(4, enc.encode(`tts HTTP ${res.status}: ${(await res.text()).slice(0, 200)}`)));
        continue;
      }
      // PCM s16le: forward whole samples only.
      let carry: Uint8Array | null = null;
      for await (let chunk of res.body) {
        if (carry) { const c = new Uint8Array(carry.length + chunk.length); c.set(carry); c.set(chunk, carry.length); chunk = c; carry = null; }
        if (chunk.length % 2) { carry = chunk.slice(chunk.length - 1); chunk = chunk.slice(0, chunk.length - 1); }
        if (!chunk.length) continue;
        if (!firstAudioSent) { firstAudioSent = true; stats.firstAudioMs = Math.round(performance.now() - t0); }
        await out.write(frame(2, chunk));
      }
    }
  })();

  const enqueue = (raw: string) => {
    // Haiku sometimes wraps the reply in quotes; never speak quotes or punctuation-only pieces.
    const clause = raw.replace(/^["'“”‘’«»\s]+|["'“”‘’«»\s]+$/g, "");
    if (!/[\p{L}\p{N}]/u.test(clause)) return;
    pending.push({ clause, tts: startTts(clause, spoken) });
    spoken = (spoken + " " + clause).trim();
    notify();
  };

  try {
    const stream = anthropic.messages.stream({
      model: MODEL,
      max_tokens: 300,
      thinking: { type: "disabled" },
      output_config: { effort: "low" },
      system: [{ type: "text", text: SYSTEM, cache_control: { type: "ephemeral" } }],
      messages: [{ role: "user", content: `Previous utterances:\n${prev}\n\nCurrent utterance:\n${text}` }],
    });
    let buf = "";
    for await (const ev of stream) {
      if (ev.type === "content_block_delta" && ev.delta.type === "text_delta") {
        if (stats.haikuFirstMs === undefined) stats.haikuFirstMs = Math.round(performance.now() - t0);
        buf += ev.delta.text;
        let cut: [string, string] | null;
        while ((cut = takeClause(buf)) !== null) {
          if (stats.firstClauseMs === undefined) stats.firstClauseMs = Math.round(performance.now() - t0);
          enqueue(cut[0]);
          buf = cut[1];
        }
      }
    }
    if (buf.trim()) enqueue(buf.trim());
  } catch (e) {
    await out.write(frame(4, enc.encode(`haiku: ${e instanceof Error ? e.message : String(e)}`)));
  } finally {
    producerDone = true;
    notify();
  }
  await writer;
  stats.totalMs = Math.round(performance.now() - t0);
  stats.english = spoken;
  console.log(JSON.stringify({ es: text, ...stats }));
  await out.write(frame(3, enc.encode(JSON.stringify(stats))));
}

Deno.serve({ port: Number(Deno.env.get("PORT") ?? 8080), hostname: "0.0.0.0" }, async (req) => {
  const url = new URL(req.url);
  if (req.method === "GET" && url.pathname === "/health") return new Response("ok");
  if (req.method !== "POST" || url.pathname !== "/speak") return new Response("not found", { status: 404 });
  if (!sameToken(req.headers.get("x-app-token") ?? "", APP_TOKEN)) return new Response("unauthorized", { status: 401 });
  let body: { text?: unknown; history?: unknown };
  try {
    body = await req.json();
  } catch {
    return new Response("bad json", { status: 400 });
  }
  const text = typeof body.text === "string" ? body.text.trim().slice(0, MAX_TEXT) : "";
  const history = Array.isArray(body.history)
    ? body.history.filter((h): h is string => typeof h === "string").slice(-MAX_HISTORY).map((h) => h.slice(0, MAX_TEXT))
    : [];
  const { readable, writable } = new TransformStream<Uint8Array, Uint8Array>();
  const out = writable.getWriter();
  (async () => {
    try {
      if (text) await speak(text, history, out);
      else await out.write(frame(3, enc.encode("{}")));
    } catch (e) {
      console.error("speak failed", e instanceof Error ? e.message : e);
    } finally {
      await out.close().catch(() => {});
    }
  })();
  return new Response(readable, { headers: { "content-type": "application/octet-stream", "cache-control": "no-store" } });
});
