"""Claude Haiku 5.5 as the MT stage of the cascade: latency and quality bench on PC.

Usage:  .venv-cascade/Scripts/python -I haiku_bench.py <phrases.txt> <out.csv> [variant ...]
  phrases.txt: "<id>\t<spanish>" per line (tools/logs/cascade/mt_ref.txt or mt_asr.txt)
  variants:    single  — minimal instructions, each phrase on its own
               session — cached instructions + glossary + previous 3 phrases as context
The API key is read from ANTHROPIC_API_KEY (process env, else the Windows user env) and never printed.
"""
import csv
import os
import statistics
import sys
import time

import anthropic

MODEL = "claude-haiku-5-5"
PRICE_IN, PRICE_CACHE_READ, PRICE_CACHE_WRITE, PRICE_OUT = 0.10, 0.01, 0.125, 0.50  # USD per MTok

SINGLE_SYSTEM = (
    "Translate this Mexican Spanish speech transcript into natural, idiomatic English. "
    "Translate slang and colloquial expressions by their meaning, not word by word. "
    "Reply with the English translation only."
)

SESSION_SYSTEM = """You are the translation engine of a real-time speech-to-speech interpreter. A Mexican tourist \
(Abraham) speaks Spanish into his phone while traveling in Chicago; your English output is read aloud by a \
text-to-speech voice to the person he is talking to (waiters, shop clerks, hotel staff, drivers, locals).

Input: one utterance from an on-device speech recognizer. It may lack punctuation and accents, and may contain \
recognition errors, fillers or noise.

Rules:
- Reply with the English translation only: no quotes, notes, alternatives or explanations.
- Natural spoken American English, as a fluent bilingual friend would say it. Keep it short; preserve the tone \
(polite, casual, joking, urgent).
- Translate Mexican slang and idioms by meaning (see glossary), never word by word.
- Fix obvious recognition errors from context (e.g. place names). If a word is unintelligible, translate the rest.
- Drop pure fillers and hesitations (eh, este, mmm, o sea, pues as filler). If the whole input is only fillers, \
laughter or noise, reply with an empty line.
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

Chicago names (fix misrecognitions to these): Navy Pier, Millennium Park, The Bean (Cloud Gate), Willis Tower, \
Skydeck, Magnificent Mile, Michigan Avenue, Wrigley Field, Wrigleyville, Lincoln Park, Pilsen, Little Village, \
Chinatown, Hyde Park, Lake Shore Drive, Lake Michigan, the Loop, the L (CTA), Blue Line, Red Line, O'Hare, Midway, \
Union Station, Art Institute, Field Museum, Shedd Aquarium, Adler Planetarium, Museum Campus, Soldier Field, \
Roosevelt Road, State Street, Wacker Drive, Riverwalk, Garrett Popcorn, Portillo's, Lou Malnati's, Giordano's, \
deep dish, Chicago-style hot dog, Italian beef, Cubs, White Sox, Bulls, Bears, Glen Ellyn, Oak Brook, Naperville.
"""


def api_key() -> str:
    key = os.environ.get("ANTHROPIC_API_KEY")
    if not key and sys.platform == "win32":
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, "Environment") as k:
            key, _ = winreg.QueryValueEx(k, "ANTHROPIC_API_KEY")
    if not key:
        sys.exit("ANTHROPIC_API_KEY not found")
    return key


def load_phrases(path):
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if "\t" in line:
                pid, text = line.split("\t", 1)
                rows.append((pid.strip(), text.strip()))
    return rows


def translate(client, variant, text, history):
    if variant == "single":
        system = SINGLE_SYSTEM
        user = text
    else:
        system = [{"type": "text", "text": SESSION_SYSTEM, "cache_control": {"type": "ephemeral"}}]
        prev = "\n".join(f"- {h}" for h in history[-3:]) or "(none)"
        user = f"Previous utterances:\n{prev}\n\nCurrent utterance:\n{text}"
    t0 = time.perf_counter()
    first = None
    with client.messages.stream(
        model=MODEL,
        max_tokens=200,
        thinking={"type": "disabled"},
        output_config={"effort": "low"},
        system=system,
        messages=[{"role": "user", "content": user}],
    ) as stream:
        for _ in stream.text_stream:
            if first is None:
                first = time.perf_counter()
        msg = stream.get_final_message()
    end = time.perf_counter()
    out = "".join(b.text for b in msg.content if b.type == "text").strip()
    u = msg.usage
    return {
        "out": out,
        "ttft_ms": round(((first or end) - t0) * 1000),
        "total_ms": round((end - t0) * 1000),
        "in_tok": u.input_tokens,
        "cache_read": u.cache_read_input_tokens or 0,
        "cache_write": u.cache_creation_input_tokens or 0,
        "out_tok": u.output_tokens,
        "stop": msg.stop_reason,
    }


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    src, out_csv = sys.argv[1], sys.argv[2]
    variants = sys.argv[3:] or ["single", "session"]
    phrases = load_phrases(src)
    client = anthropic.Anthropic(api_key=api_key(), max_retries=2)
    # Warm-up (TLS + DNS) so the first measured phrase is not penalized.
    translate(client, "single", "Hola.", [])

    rows, cost = [], 0.0
    for variant in variants:
        history = []
        for pid, text in phrases:
            r = translate(client, variant, text, history)
            history.append(text)
            cost += (r["in_tok"] * PRICE_IN + r["cache_read"] * PRICE_CACHE_READ
                     + r["cache_write"] * PRICE_CACHE_WRITE + r["out_tok"] * PRICE_OUT) / 1e6
            rows.append({"variant": variant, "id": pid, "src": text, **r})
            print(f"{variant:7} {pid:>18} ttft={r['ttft_ms']:4} total={r['total_ms']:4}  {text}  ->  {r['out']}",
                  flush=True)

    with open(out_csv, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)

    for variant in variants:
        vs = [r for r in rows if r["variant"] == variant]
        tt = sorted(r["ttft_ms"] for r in vs)
        to = sorted(r["total_ms"] for r in vs)
        p = lambda xs, q: xs[min(len(xs) - 1, int(q * len(xs)))]
        print(f"\n{variant}: n={len(vs)}  ttft p50={p(tt, .5)} p90={p(tt, .9)}  "
              f"total p50={p(to, .5)} p90={p(to, .9)} max={to[-1]}  mean={statistics.mean(to):.0f} ms  "
              f"cache_read_tok={sum(r['cache_read'] for r in vs)}")
    print(f"\nestimated cost: US${cost:.4f}")


if __name__ == "__main__":
    main()
