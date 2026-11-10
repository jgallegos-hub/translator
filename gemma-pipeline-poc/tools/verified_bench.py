"""Speaker-verified prompt check, offline (2026-11-10): replays device round r29 (background music,
talking over Eric, short replies) through the relay's prompt with the history the relay would have had.

- "current": SYSTEM with every chunk that reached Haiku in r29 (reproduces the 6 silenced utterances).
- "verified": SYSTEM_VERIFIED with only the chunks the speaker gate passes (sim >= 0.40) — what the relay
  sees once the gate is enforced. Every one of them is Abraham and must be spoken.

Usage: .venv-cascade/Scripts/python -I verified_bench.py [reps]
Prompts are read through Deno from backend/shared/interpreter_prompt.ts, so they are the deployed text.
"""
import subprocess
import sys
from pathlib import Path

import anthropic

sys.path.insert(0, str(Path(__file__).parent))
from english_bench import translate  # noqa: E402
from relay_bench import key  # noqa: E402

PROMPT_TS = Path(__file__).parent.parent / "backend" / "shared" / "interpreter_prompt.ts"
THRESHOLD = 0.40

# (speaker-gate similarity, Moonshine transcript) in r29 order; sim < 0.40 = not Abraham (music / echo).
R29 = [
    (0.788, "Hola compañeros, buenos días."),
    (0.787, "amigos están ahí"),
    (0.847, "o que noté que la primera frase no salió hasta que repetí la segunda puede ser el arranque pero hay que revisar"),
    (0.864, "ahora mismo tengo música de fondo reproduciendo en español y veo que no se está mezclando con el audio"),
    (0.791, "ahora voy a decir una frase corta como si"),
    (0.517, "sí"),
    (0.643, "claro que sí"),
    (0.663, "desde luego que sí"),
    (0.798, "por supuesto que sí"),
    (0.805, "claro que por supuesto que desde luego que si"),
    (0.285, "no sé qué es lo que me ha dicho"),
    (0.141, "ay no no no"),
    (0.533, "ok."),
    (0.797, "nri y parece que jai correspondió no estoy seguro"),
    (0.754, "ok ya no reprodujo nada"),
    (0.866, "Ok, bueno. Ahora voy a seguir hablando encima de la traducción."),
    (0.730, "o por ejemplo esta es la primera calce que estoy diciendo encima"),
    (0.766, "y es testigo hablando"),
    (0.766, "estoy hablando pero no veo la traducción"),
    (0.757, "continuó hablando pero no veo la producción"),
    (0.646, "sigo hablando"),
    (0.797, "ahora la veo pero como dos trastes no las dijo"),
    (0.824, "o por lo menos no las dije en el momento"),
    (0.842, "entonces podemos decir que esta es una falla o simplemente es el filtro"),
    (0.868, "pero de todas formas creo que debemos de seguir encaminando este sistema a que hable encima"),
    (0.808, "bueno ahora voy a seguir intentando con el audio de fondo en ingles"),
    (0.309, "la misma"),
    (0.740, "sigamos con el siguiente paso. no lo sé."),
]


def prompt(name: str) -> str:
    code = f'import {{ {name} }} from "{PROMPT_TS.as_uri()}"; await Deno.stdout.write(new TextEncoder().encode({name}));'
    return subprocess.run(["deno", "eval", code], capture_output=True, check=True).stdout.decode("utf-8")


def replay(client, system, cases, reps):
    silenced = 0
    for rep in range(reps):
        history = []
        for sim, text in cases:
            out = translate(client, system, text, history)
            history.append(text)
            mine = sim >= THRESHOLD
            if mine and not out:
                silenced += 1
            if rep == 0 or (mine and not out):
                tag = "SILENCED" if mine and not out else ("ok" if mine else "not-him")
                print(f"  r{rep} {tag:8} {sim:.2f} {text[:70]!r} -> {out[:70]!r}", flush=True)
    mine_total = sum(1 for s, _ in cases if s >= THRESHOLD) * reps
    return silenced, mine_total


def main():
    reps = int(sys.argv[1]) if len(sys.argv) > 1 else 3
    client = anthropic.Anthropic(api_key=key("ANTHROPIC_API_KEY"), max_retries=1)
    print("== current prompt, all r29 chunks that reached Haiku")
    s, n = replay(client, prompt("SYSTEM"), R29, reps)
    print(f"== current: Abraham silenced {s}/{n}")
    print("== verified prompt, only chunks the gate passes")
    s, n = replay(client, prompt("SYSTEM_VERIFIED"), [c for c in R29 if c[0] >= THRESHOLD], reps)
    print(f"== verified: Abraham silenced {s}/{n}")


if __name__ == "__main__":
    main()
