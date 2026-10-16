"""Kokoro fp16 on PC with the app's own G2P (CMUdict IPA + overrides) — baseline for the
Supertonic A/B (same sentences, same PC, same thread count). Writes WAVs.

Usage: .venv-cascade/Scripts/python -I kokoro_pc_bench.py <out_dir> [threads] [voice]
Model + voices pulled from the phone into tools/models/kokoro (gitignored).
"""
import json
import re
import sys
import time
import wave
from pathlib import Path

import numpy as np
import onnxruntime as ort

sys.path.insert(0, str(Path(__file__).parent))
from supertonic_bench import SENTENCES  # noqa: E402

ROOT = Path(__file__).parent
ASSETS = ROOT.parent / "app" / "src" / "main" / "assets"
PUNCT = '.,!?;:"()[]{}“”‘’'
OVERRIDES = {"it": "ɪt", "us": "ʌs"}
SPOKEN_AS_WORD = {"OK", "NASA", "NATO"}


def load_dict():
    d = {}
    for line in (ASSETS / "cmudict_ipa.dict").read_text(encoding="utf-8").splitlines():
        if "\t" not in line or line.startswith(";;;"):
            continue
        w, p = line.split("\t", 1)
        d.setdefault(w.split("(")[0].upper(), p.strip())
    return d


def phonemize(text, d):
    out = []
    for word in text.replace("’", "'").split():
        clean = word.strip(PUNCT)
        if not clean:
            continue
        if clean != clean.upper() and clean.lower() in OVERRIDES:
            out.append(OVERRIDES[clean.lower()]); continue
        m = re.fullmatch(r"([A-Z]{2,5})('?s)?", clean)
        if m and m.group(1) not in SPOKEN_AS_WORD:
            out.append("".join(d[c] for c in m.group(1)) + ("z" if m.group(2) else "")); continue
        p = d.get(clean.upper()) or d.get(clean.replace("'", "").upper())
        if p is None and "-" in clean:
            p = " ".join(d.get(x.upper(), "ə" * len(x)) for x in clean.split("-") if x)
        out.append(p or "ə" * len(clean))
    return " ".join(out)


def main():
    out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
    threads = int(sys.argv[2]) if len(sys.argv) > 2 else 4
    voice = sys.argv[3] if len(sys.argv) > 3 else "am_puck"
    cfg = json.loads((ASSETS / "kokoro_config.json").read_text(encoding="utf-8"))
    vocab = cfg["vocab"]
    d = load_dict()
    voices = np.load(ROOT / "models" / "kokoro" / "voices-v1.0.bin")
    opts = ort.SessionOptions(); opts.intra_op_num_threads = threads
    sess = ort.InferenceSession(str(ROOT / "models" / "kokoro" / "kokoro-v1.0.fp16.onnx"), opts,
                                providers=["CPUExecutionProvider"])
    names = [i.name for i in sess.get_inputs()]

    def synth(text):
        toks = [vocab[c] for c in phonemize(text, d) if c in vocab][:510]
        style = voices[voice][len(toks)].astype(np.float32).reshape(1, 256)
        feeds = {names[0]: np.array([[0, *toks, 0]], np.int64), names[1]: style,
                 names[2]: np.array([1.0], np.float32)}
        t0 = time.perf_counter()
        wav = sess.run(None, feeds)[0].reshape(-1)
        return wav, time.perf_counter() - t0

    synth("Warm up.")
    tot_s = tot_a = 0.0
    for i, s in enumerate(SENTENCES):
        wav, t = synth(s)
        a = len(wav) / 24000
        tot_s += t; tot_a += a
        pcm = (np.clip(wav, -1, 1) * 32767).astype(np.int16)
        with wave.open(str(out / f"kokoro_{voice}_{i}.wav"), "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(24000); w.writeframes(pcm.tobytes())
        print(f"kokoro #{i} audio={a:.2f}s synth={t*1000:.0f}ms rtf={t/a:.3f}")
    print(f"== kokoro {voice} threads={threads}: RTF {tot_s/tot_a:.3f}")


if __name__ == "__main__":
    main()
