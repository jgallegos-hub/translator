"""Which Claude setup should the relay use? Compares model / thinking variants at low effort, same
interpreter prompt, on real (often garbled) Moonshine transcripts from device rounds. Each phrase
runs REPS times because commentary leaks are intermittent; suspected leaks are counted.

Usage: .venv-cascade/Scripts/python -I model_bench.py <out.csv>
Key: ANTHROPIC_API_KEY from the process env, else the Windows user env. Never printed.
"""
import csv
import re
import statistics
import sys
import time
from pathlib import Path

import anthropic

sys.path.insert(0, str(Path(__file__).parent))
from relay_bench import key  # noqa: E402

PROMPT_FILE = Path(__file__).parent.parent / "backend" / "shared" / "interpreter_prompt.ts"

VARIANTS = {
    "haiku-off": ("claude-haiku-5-5", {"type": "disabled"}),
    "sonnet-off": ("claude-sonnet-5-5", {"type": "between_tools"}),  # Sonnet 5.5 rejects "disabled"
}
REPS = 3
LEAK = re.compile(r"unintelligible|utterance|recogni|transcri|\blikely\b|\bhmm\b|translate|best guess|mix-up|\bmeant\b", re.I)

PHRASES = [
    "o además pregunta leyen sances tres keri",
    "ni es tenemos ajustar",
    "los parámetros de noviembre",
    "No sé bien cómo planchar.",
    "¿y algún tipo? Roder.",
    "Ebraron al centro. ¿puedes que bueno? es que bueno.",
    "y entonces así pues el alma un poco sin que funcione realmente bien",
    "ahora mississa dímelo.",
    "hacemos cracks generadores y cracks o que prefieren hacer primero",
    "si no pasen a chingar a su puta madre",
    "le comento que resolver el cuarto por internet hace dos semanas",
    "vamos a neybe peer y luego a mi venian park",
    "mañana y bebe vamos a ir al acuario en la mañana",
    "el vuelo sale a las seis cuarenta y cinco de la mañana",
    "son ochenta y cinco dólares con veinte centavos",
]


def system_prompt() -> str:
    src = PROMPT_FILE.read_text(encoding="utf-8")
    return src.split("export const SYSTEM = `", 1)[1].split("`;", 1)[0].replace("\\\r\n", "").replace("\\\n", "")


def run(client, model, thinking, system, text):
    user = f"Previous utterances:\n(none)\n\nCurrent utterance:\n{text}"
    t0 = time.perf_counter()
    first = None
    buf = ""
    with client.messages.stream(
        model=model, max_tokens=300, thinking=thinking, output_config={"effort": "low"},
        system=[{"type": "text", "text": system, "cache_control": {"type": "ephemeral"}}],
        messages=[{"role": "user", "content": user}],
    ) as stream:
        for delta in stream.text_stream:
            buf += delta
            # Only what is inside <say> reaches the voice: time the first spoken character.
            if first is None and re.search(r"<say>\s*[^\s<]", buf):
                first = time.perf_counter()
        msg = stream.get_final_message()
    end = time.perf_counter()
    m = re.search(r"<say>(.*?)(</say>|$)", buf, re.S)
    out = m.group(1).strip() if m else ""
    return out, round(((first or end) - t0) * 1000), round((end - t0) * 1000), ("tag" if m else "NO_TAG") + ("" if buf.lstrip().startswith("<say>") else "+preamble")


def main():
    client = anthropic.Anthropic(api_key=key("ANTHROPIC_API_KEY"), max_retries=1)
    system = system_prompt()
    rows = []
    for label, (model, thinking) in VARIANTS.items():
        run(client, model, thinking, system, "hola")  # warm the connection and the prompt cache
        for rep in range(REPS):
            for text in PHRASES:
                out, first, total, stop = run(client, model, thinking, system, text)
                leak = bool(LEAK.search(out)) or len(out) > 3 * len(text) + 40
                rows.append({"variant": label, "rep": rep, "es": text, "en": out, "first_ms": first,
                             "total_ms": total, "stop": stop, "leak": leak})
                if rep == 0 or leak:
                    print(f"{label:15} r{rep} first={first:5} total={total:5} {'LEAK ' if leak else ''}[{stop}] {text!r} -> {out!r}", flush=True)
    with open(sys.argv[1], "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    for label in VARIANTS:
        vs = [r for r in rows if r["variant"] == label]
        firsts = sorted(r["first_ms"] for r in vs)
        totals = sorted(r["total_ms"] for r in vs)
        print(f"== {label}: n={len(vs)} first text p50={statistics.median(firsts):.0f} p90={firsts[int(.9*len(firsts))]} max={firsts[-1]} ms; "
              f"total p50={statistics.median(totals):.0f} ms; leaks={sum(r['leak'] for r in vs)}; empty={sum(not r['en'] for r in vs)}; "
              f"preamble={sum('preamble' in r['stop'] for r in vs)}; no_tag={sum('NO_TAG' in r['stop'] for r in vs)}")


if __name__ == "__main__":
    main()
