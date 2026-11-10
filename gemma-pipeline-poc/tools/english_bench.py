"""English-interlocutor filter check, offline: replays real Moonshine transcripts through the relay's
prompt with the history the relay would have had. English heard through the Spanish recognizer must
come back empty; Abraham's Spanish (including the English words he mixes in) must still translate.

Usage: .venv-cascade/Scripts/python -I english_bench.py [reps]
Key: ANTHROPIC_API_KEY from the process env, else the Windows user env. Never printed.
"""
import re
import sys
from pathlib import Path

import anthropic

sys.path.insert(0, str(Path(__file__).parent))
from model_bench import system_prompt  # noqa: E402
from relay_bench import key  # noqa: E402

# (transcript, expected) in round order, so the history matches what the relay saw.
# Source: device rounds r25-r29 (r28/r29 = English probe played from the PC speakers).
CASES = [
    ("¿Qué tal amigos? ¿Cómo están? Buenos días.", "speak"),
    ("yo y mi madre tengo un fracción", "silent"),        # "Do you want a table for two?"
    ("jour no flavon", "silent"),                         # "Sure, no problem."
    ("disculpe donde está el baño", "speak"),             # Spanish control (other voice)
    ("oríga es proméxica", "silent"),                     # "Are you guys from Mexico?"
    ("fitso for charles trenate", "silent"),              # "Pizza or tacos tonight?"
    ("yas", "silent"),                                    # "Yes."
    ("también si es proceso de fallo", "silent"),         # "The museum closes at five."
    ("muchas gracias por todo que tengan buen día.", "speak"),
    ("Ok. Ok.", "silent"),                                # "Okay, okay."
    ("No es video que filmiste ni teñeos.", "silent"),    # "Let me know if you need anything else."
    ("no wee!", "silent"),                                # "No way!"
    ("ey eric jorrey itudei", "silent"),                  # Abraham speaking English on purpose (r27)
    ("en cualquier caso este es un fucking proyecto", "speak"),
    ("acerca de todos los tickets relacionados con safety me dijo que tú estabas de acuerdo en realizarlos", "speak"),
    ("Voy a hacer check-in en el hotel.", "speak"),
    ("¿Necesitamos decirle a Jason si vamos a continuar con el proyecto o no?", "speak"),
    ("o tal vez debamos decirle a oskin", "speak"),
    ("oye robert", "speak"),
    ("a ver", "speak"),
    ("ok", "speak"),
    ("sí", "speak"),
    ("No sé bien cómo planchar.", "speak"),
    ("hoy toca mantenimiento preventivo, tenemos algunas ups y cras que revisar", "speak"),
]


def translate(client, system, text, history):
    prev = "\n".join(f"- {h}" for h in history[-3:]) or "(none)"
    msg = client.messages.create(
        model="claude-haiku-5-5", max_tokens=300, thinking={"type": "disabled"}, output_config={"effort": "low"},
        system=[{"type": "text", "text": system, "cache_control": {"type": "ephemeral"}}],
        messages=[{"role": "user", "content": f"Previous utterances:\n{prev}\n\nCurrent utterance:\n{text}"}],
    )
    raw = "".join(b.text for b in msg.content if b.type == "text")
    m = re.search(r"<say>(.*?)(</say>|$)", raw, re.S)
    return m.group(1).strip() if m else ""


def main():
    reps = int(sys.argv[1]) if len(sys.argv) > 1 else 3
    client = anthropic.Anthropic(api_key=key("ANTHROPIC_API_KEY"), max_retries=1)
    system = system_prompt()
    wrong = {"leaked_english": 0, "silenced_abraham": 0}
    for rep in range(reps):
        history = []
        for text, expected in CASES:
            out = translate(client, system, text, history)
            history.append(text)
            ok = (out == "") == (expected == "silent")
            if not ok:
                wrong["leaked_english" if expected == "silent" else "silenced_abraham"] += 1
            if rep == 0 or not ok:
                print(f"r{rep} {'OK ' if ok else 'BAD'} [{expected:6}] {text!r} -> {out!r}", flush=True)
    silent = sum(1 for _, e in CASES if e == "silent") * reps
    speak = sum(1 for _, e in CASES if e == "speak") * reps
    print(f"== English leaked {wrong['leaked_english']}/{silent}; Abraham silenced {wrong['silenced_abraham']}/{speak}")


if __name__ == "__main__":
    main()
