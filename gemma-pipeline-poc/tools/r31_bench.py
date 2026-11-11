"""Split-sentence check, offline (2026-11-11): replays device round r31 (scripted, pauses inside and at the end
of sentences) through the relay's speaker-verified prompt with the history the relay had (last 3 Spanish
utterances). Every chunk is Abraham's and already passed the speaker gate.

Checks per reply:
- silenced: empty reply (never acceptable here);
- repeated: the reply starts with the start of the previous reply, i.e. Eric would say again what he just said
  (r31: "Quería preguntarte" -> "I wanted to ask you.", then "Si ya revisaste..." -> "I wanted to ask you, did...");
- must / must_not: case-specific words (r31: "la oma unatis" came out as "Giordano's" instead of Lou Malnati's).

Usage: .venv-cascade/Scripts/python -I r31_bench.py [reps] [SYSTEM_VERIFIED|SYSTEM]
"""
import re
import sys
from pathlib import Path

import anthropic

sys.path.insert(0, str(Path(__file__).parent))
from english_bench import translate  # noqa: E402
from relay_bench import key  # noqa: E402
from verified_bench import prompt  # noqa: E402

# (Moonshine transcript, must contain, must not contain) in r31 order.
R31 = [
    ("Hoy revisamos los generadores del cuarto dos.", None, None),
    ("ya llegó el técnico de los chillars", None, None),
    ("No, todavía no.", None, None),
    ("Le dijeron que la orden de trabajo era para mañana.", None, None),
    ("pero no estoy seguro", None, None),
    ("El breaker del tablero principal se volvió a votar.", None, None),
    ("otra vez", None, None),
    ("vamos a necesitar más tiempo para el mantenimiento la verdad", None, None),
    ("ese reporte lo mandé desde el viernes", None, None),
    ("¿ Sí?", None, None),
    ("¿Me puedes pasar la lista de las unidades crack?", "CRAC", None),
    ("Pensé que era la prueba de carga hoy.", None, None),
    ("pero la movieron", None, None),
    ("Quería preguntarte", None, None),
    ("Si ya revisaste el sistema de entrenamiento.", None, "ask you"),
    ("lo que pasa es que", None, None),
    ("El proveedor no ha confirmado.", None, "what's happening"),
    ("si quieres", None, None),
    ("Lo vemos después de la comida.", None, "if you want"),
    ("yo creo que", None, None),
    ("Lo mejor es esperar a que llegue el supervisor.", None, "I think"),
    ("sí", None, None),
    ("no.", None, None),
    ("ok?", None, None),
    ("claro", None, None),
    ("no, no.", None, None),
    ("la misma", None, None),
    ("ya quedó", None, None),
    ("mañana vamos a neybit pie con la familia", "Navy Pier", None),
    ("si no llueve.", None, None),
    ("cuántos hay en el boleto para el skydec", "Skydeck", None),
    ("por persona.", None, None),
    ("tenemos que probar la pizza de la oma unatis", "Malnati", "Giordano"),
    ("la de did dish", "deep dish", None),
    ("nos perdimos en el loop", "Loop", None),
    ("otra vez", None, None),
    ("el tren de la bludland ya pasó ¿verdad?", "Blue Line", None),
    ("gracias por la recomendación.", None, None),
    ("estuvo muy bueno", None, None),
    ("Muy bien. ¿Cómo les fue el fin de semana?", None, None),
    ("todo bien", None, None),
    ("Nosotros fuimos al \"Art Institute\".", "Art Institute", None),
    ("Estuvo increíble.", None, "Art Institute"),
    ("No se te olvide la junta, por cierto.", None, None),
    ("Por cierto.", None, None),
    ("Ya llegó el paquete.", None, "by the way"),
    ("Bueno, ya terminé la ronda.", None, None),
    ("gracias.", None, None),
]


def words(s: str) -> list[str]:
    return re.findall(r"[a-z']+", s.lower())


def repeated(prev: str, cur: str) -> bool:
    """The current reply opens with the previous reply's first words (>= 3 words, or all of a short one)."""
    p, c = words(prev), words(cur)
    n = min(len(p), 4)
    return n >= 2 and c[:n] == p[:n] and len(c) > len(p)


def main():
    reps = int(sys.argv[1]) if len(sys.argv) > 1 else 3
    name = sys.argv[2] if len(sys.argv) > 2 else "SYSTEM_VERIFIED"
    client = anthropic.Anthropic(api_key=key("ANTHROPIC_API_KEY"), max_retries=1)
    system = prompt(name)
    bad = {"silenced": 0, "repeated": 0, "must": 0, "must_not": 0}
    for rep in range(reps):
        history, prev_out = [], ""
        for text, must, must_not in R31:
            out = translate(client, system, text, history)
            history.append(text)
            issues = []
            if not out:
                issues.append("silenced")
            if repeated(prev_out, out):
                issues.append("repeated")
            if must and must.lower() not in out.lower():
                issues.append("must")
            if must_not and must_not.lower() in out.lower():
                issues.append("must_not")
            for i in issues:
                bad[i] += 1
            if rep == 0 or issues:
                tag = ",".join(issues) or "ok"
                print(f"  r{rep} {tag:16} {text[:48]!r:52} -> {out[:80]!r}", flush=True)
            prev_out = out
    n = len(R31) * reps
    print(f"== {name}: " + "  ".join(f"{k} {v}/{n}" for k, v in bad.items()))


if __name__ == "__main__":
    main()
