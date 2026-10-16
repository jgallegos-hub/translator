// Online MT for the gemma-pipeline cascade: Mexican Spanish transcript -> English via
// Claude Haiku 5.5, streamed back as plain text. The app never holds the Anthropic key.
//
// Request:  POST { "text": "<spanish>", "history": ["<previous spanish>", ...] }
//           header x-app-token: <APP_TOKEN secret>
// Response: text/plain, chunked; English deltas as they are generated.
// Secrets:  ANTHROPIC_API_KEY, APP_TOKEN.  Deploy with --no-verify-jwt (own token instead).
import Anthropic from "npm:@anthropic-ai/sdk@0.131.0";

const MODEL = "claude-haiku-5-5";
const MAX_TEXT = 1000;
const MAX_HISTORY = 3;

// Same instructions as tools/haiku_bench.py "session" mode (bench 2026-10-16).
const SYSTEM = `You are the translation engine of a real-time speech-to-speech interpreter. A Mexican tourist \
(Abraham) speaks Spanish into his phone while traveling in Chicago; your English output is read aloud by a \
text-to-speech voice to the person he is talking to (waiters, shop clerks, hotel staff, drivers, locals).

Input: one utterance from an on-device speech recognizer. It may lack punctuation and accents, and may contain \
recognition errors, fillers or noise.

Rules:
- Reply with the English translation only: no quotes, notes, alternatives or explanations. Never comment on \
the input, never ask for clarification and never describe what you are doing.
- Natural spoken American English, as a fluent bilingual friend would say it. Keep it short; preserve the tone \
(polite, casual, joking, urgent).
- Translate Mexican slang and idioms by meaning (see glossary), never word by word.
- Fix obvious recognition errors from context (e.g. place names). If a word is unintelligible, translate the rest; \
do not invent content that is not there.
- Drop pure fillers and hesitations (eh, este, mmm, o sea, pues as filler). If the whole input is only fillers, \
laughter or noise, reply with nothing.
- Keep proper names, brands and Chicago places in their usual English form.
- Numbers, prices, times and addresses: write them the way an American would say them.
- The "previous utterances" are context only (same speaker, same conversation). Translate only the current one.

Mexican Spanish glossary (meaning):
- ahorita: right now / in a moment (context) · al rato: later · ¿mande?: sorry? / pardon?
- ¿qué onda? / ¿qué pedo?: what's up? · ¡qué padre! / ¡qué chido!: how cool! · ¡qué mala onda!: that sucks / that's not cool
- está cañón: it's tough / it's crazy · no manches / no mames: no way! / you're kidding · ¡aguas!: watch out!
- neta / ¿neta?: really / for real? · güey / wey: dude / man (often drop it) · chamba / chambear: job / to work
- lana / varo: money · un chorro: a lot · ándale: come on / that's it / go ahead · órale: okay / wow / let's go
- ¿cuánto sale?: how much is it? · me late: I like it / sounds good · fresa: posh / preppy · cuate / carnal: buddy / bro
- está de pelos: it's awesome · me da hueva: I can't be bothered · chela: beer · camión: bus · la cuenta: the check
- ¿me regala…? / ¿me da…?: can I get…? · ¿me puede hacer el paro?: can you do me a favor?
- ya merito: almost there · a poco: really? / no way · se me hace que: I think that · ni modo: oh well
- ¿a cómo?: how much? · está padrísimo: it's amazing · está gacho: it's bad / it's rough · sale / va: okay / deal
- le comento que: I wanted to let you know that

Chicago names (fix misrecognitions to these): Navy Pier, Millennium Park, The Bean (Cloud Gate), Willis Tower, \
Skydeck, Magnificent Mile, Michigan Avenue, Wrigley Field, Wrigleyville, Lincoln Park, Pilsen, Little Village, \
Chinatown, Hyde Park, Lake Shore Drive, Lake Michigan, the Loop, the L (CTA), Blue Line, Red Line, O'Hare, Midway, \
Union Station, Art Institute, Field Museum, Shedd Aquarium, Adler Planetarium, Museum Campus, Soldier Field, \
Roosevelt Road, State Street, Wacker Drive, Riverwalk, Garrett Popcorn, Portillo's, Lou Malnati's, Giordano's, \
deep dish, Chicago-style hot dog, Italian beef, Cubs, White Sox, Bulls, Bears, Glen Ellyn, Oak Brook, Naperville.
`;

const client = new Anthropic({ apiKey: Deno.env.get("ANTHROPIC_API_KEY"), maxRetries: 1 });
const appToken = Deno.env.get("APP_TOKEN") ?? "";

function sameToken(a: string, b: string): boolean {
  if (!b || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return new Response("method not allowed", { status: 405 });
  if (!sameToken(req.headers.get("x-app-token") ?? "", appToken)) {
    return new Response("unauthorized", { status: 401 });
  }
  let body: { text?: unknown; history?: unknown };
  try {
    body = await req.json();
  } catch {
    return new Response("bad json", { status: 400 });
  }
  const text = typeof body.text === "string" ? body.text.trim().slice(0, MAX_TEXT) : "";
  if (!text) return new Response("", { status: 200 });
  const history = Array.isArray(body.history)
    ? body.history.filter((h): h is string => typeof h === "string").slice(-MAX_HISTORY)
      .map((h) => h.slice(0, MAX_TEXT))
    : [];
  const prev = history.length ? history.map((h) => `- ${h}`).join("\n") : "(none)";

  const stream = client.messages.stream({
    model: MODEL,
    max_tokens: 300,
    thinking: { type: "disabled" },
    output_config: { effort: "low" },
    system: [{ type: "text", text: SYSTEM, cache_control: { type: "ephemeral" } }],
    messages: [{ role: "user", content: `Previous utterances:\n${prev}\n\nCurrent utterance:\n${text}` }],
  });

  const enc = new TextEncoder();
  const out = new ReadableStream<Uint8Array>({
    async start(controller) {
      try {
        for await (const event of stream) {
          if (event.type === "content_block_delta" && event.delta.type === "text_delta") {
            controller.enqueue(enc.encode(event.delta.text));
          }
        }
        controller.close();
      } catch (e) {
        console.error("anthropic stream failed", e instanceof Error ? e.message : e);
        controller.error(e);
      }
    },
    cancel() {
      stream.abort();
    },
  });
  return new Response(out, {
    headers: { "content-type": "text/plain; charset=utf-8", "cache-control": "no-store" },
  });
});
