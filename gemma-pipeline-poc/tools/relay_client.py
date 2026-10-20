"""Test client for backend/relay (POST /speak, framed stream). Measures client-side time to first
audio, saves WAVs, prints the server's per-stage stats.

Usage: python relay_client.py [base_url] [out_dir]   (default http://127.0.0.1:8080, logs/tts_ab)
App token from backend/.app_token.
"""
import json
import struct
import sys
import time
import urllib.request
import wave
from pathlib import Path

ROOT = Path(__file__).parent
PHRASES = [
    "no mamen como que no va a venir el profesor",
    "¿qué opinan?",
    "Estuvimos esperando el autobús casi cuarenta minutos y al final pasó lleno, así que decidimos caminar.",
    "qué les parece si vamos a glenn ellin",
    "hoy toca mantenimiento preventivo, tenemos algunas ups y cras que revisar",
    "pa puta madre se me olvidó el equipo de protección",
]


def speak(base: str, token: str, text: str, history):
    req = urllib.request.Request(f"{base}/speak", data=json.dumps({"text": text, "history": list(history)}).encode(),
                                 headers={"content-type": "application/json", "x-app-token": token}, method="POST")
    t0 = time.perf_counter(); first = None; pcm = b""; clauses = []; stats = {}; buf = b""
    with urllib.request.urlopen(req, timeout=30) as r:
        while True:
            b = r.read1(8192)
            if not b:
                break
            buf += b
            while len(buf) >= 5:
                t, n = buf[0], struct.unpack(">I", buf[1:5])[0]
                if len(buf) < 5 + n:
                    break
                p, buf = buf[5:5 + n], buf[5 + n:]
                if t == 1:
                    clauses.append(p.decode())
                elif t == 2:
                    if first is None:
                        first = time.perf_counter() - t0
                    pcm += p
                elif t == 3:
                    stats = json.loads(p)
                elif t == 4:
                    print("  ERROR", p.decode())
    return first, pcm, clauses, stats


def main():
    base = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080"
    out = ROOT / (sys.argv[2] if len(sys.argv) > 2 else "logs/tts_ab")
    out.mkdir(parents=True, exist_ok=True)
    token = (ROOT.parent / "backend" / ".app_token").read_text().strip()
    hist, firsts = [], []
    for i, t in enumerate(PHRASES):
        f, pcm, cl, st = speak(base, token, t, hist)
        hist.append(t)
        with wave.open(str(out / f"server_eric_{i}.wav"), "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(24000); w.writeframes(pcm)
        firsts.append(f or 0)
        print(f"#{i} FIRST_AUDIO={(f or 0)*1000:.0f}ms haiku={st.get('haikuFirstMs')} clause={st.get('firstClauseMs')} "
              f"total={st.get('totalMs')} audio={len(pcm)/48000:.2f}s  '{t}' -> {cl}")
    firsts.sort()
    print(f"== relay first audio p50={firsts[len(firsts)//2]*1000:.0f}ms min={firsts[0]*1000:.0f} max={firsts[-1]*1000:.0f}")


if __name__ == "__main__":
    main()
