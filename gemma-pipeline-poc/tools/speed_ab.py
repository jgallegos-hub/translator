"""Listening A/B for Eric's pace: ElevenLabs voice_settings.speed and a pause between sentences,
synthesised the way the relay does it (one request per sentence, previous_text for continuity).

Usage: python speed_ab.py <out_dir>     (writes WAVs and index.html)
Key: ELEVENLABS_API_KEY from the process env, else the Windows user env. Never printed.
"""
import json
import os
import sys
import urllib.request
import wave
from pathlib import Path

VOICE = "cjVigY5qzO86Huf0OWal"  # Eric
MODEL = "eleven_flash_v2_5"
SR = 24000

# Real translations from device round r25.
PHRASES = [
    ["He agreed with most of the points and only asked me one thing."],
    ["So I wanted to check with you whether you agree with that, or if you want the other shifts involved too."],
    ["Today is preventive maintenance day.", "We have some UPS units and crack units to check."],
    ["Hi friends, how are you all?", "Good morning."],
]
VARIANTS = [
    ("Actual: velocidad 1.0, sin pausa", 1.0, 0),
    ("0.90 con pausa 200 ms", 0.90, 200),
    ("0.85 con pausa 200 ms", 0.85, 200),
    ("0.80 con pausa 200 ms", 0.80, 200),
]


def key() -> str:
    v = os.environ.get("ELEVENLABS_API_KEY")
    if not v and sys.platform == "win32":
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, "Environment") as k:
            v, _ = winreg.QueryValueEx(k, "ELEVENLABS_API_KEY")
    if not v:
        sys.exit("ELEVENLABS_API_KEY not configured")
    return v


def synth(text: str, previous: str, speed: float) -> bytes:
    body = {"text": text, "model_id": MODEL, "voice_settings": {"speed": speed}}
    if previous:
        body["previous_text"] = previous
    req = urllib.request.Request(
        f"https://api.elevenlabs.io/v1/text-to-speech/{VOICE}/stream?output_format=pcm_{SR}",
        data=json.dumps(body).encode(), headers={"xi-api-key": key(), "content-type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read()


def main():
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    rows = ""
    for i, sentences in enumerate(PHRASES):
        cells = ""
        for v, (_, speed, gap) in enumerate(VARIANTS):
            pcm, previous = b"", ""
            for s in sentences:
                if pcm and gap:
                    pcm += bytes(int(SR * gap / 1000) * 2)
                pcm += synth(s, previous, speed)
                previous = (previous + " " + s).strip()
            name = f"pace_{i}_{v}.wav"
            with wave.open(str(out / name), "wb") as w:
                w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm)
            words = sum(len(s.split()) for s in sentences)
            secs = len(pcm) / 2 / SR
            print(f"#{i} speed={speed} gap={gap}: {secs:.2f} s, {words / secs:.2f} words/s")
            cells += f'<td><audio controls preload="none" src="{name}"></audio></td>'
        rows += f"<tr><th>{' '.join(sentences)}</th>{cells}</tr>"
    head = "".join(f"<th>{label}</th>" for label, _, _ in VARIANTS)
    (out / "index.html").write_text(
        f"""<!doctype html><html lang="es"><meta charset="utf-8"><title>Velocidad de Eric</title>
<style>body{{font-family:system-ui;margin:16px;background:#fafafa;color:#222}}table{{border-collapse:collapse}}
th,td{{border:1px solid #ddd;padding:6px}}th{{text-align:left;max-width:300px;font-weight:500}}thead th{{background:#eee;font-size:13px}}audio{{width:170px}}</style>
<h2>Velocidad de Eric</h2><p>Misma frase por fila. La primera columna es como suena hoy.</p>
<table><thead><tr><th>Frase</th>{head}</tr></thead><tbody>{rows}</tbody></table></html>""",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
