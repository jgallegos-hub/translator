"""Supertonic 3 (ONNX) speed/quality bench on PC — candidate replacement for Kokoro.

Pipeline (re-implemented from Supertone's reference helper.py): text -> unicode ids ->
duration_predictor -> text_encoder -> vector_estimator x total_step (flow matching from
noise) -> vocoder (44.1 kHz). Writes WAVs for listening and prints per-stage timings.

Usage: .venv-cascade/Scripts/python -I supertonic_bench.py <out_dir> [threads]
Models: tools/models/supertonic-3 (downloaded 2026-10-16, gitignored).
"""
import json
import re
import sys
import time
import unicodedata
import wave
from pathlib import Path

import numpy as np
import onnxruntime as ort

MODEL_DIR = Path(__file__).parent / "models" / "supertonic-3"

SENTENCES = [
    "Oh, for fuck's sake.",
    "What do you think?",
    "Okay friends, good morning, how are you all?",
    "I honestly don't like working on electric generators, to be real.",
    "Today we have maintenance on the air conditioning units and the UPS.",
    "We're going to Navy Pier and then to Millennium Park.",
    "Let's hope the cold goes away as the day goes on, because it's freezing.",
]


class Supertonic:
    def __init__(self, threads: int):
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = threads
        load = lambda n: ort.InferenceSession(str(MODEL_DIR / "onnx" / f"{n}.onnx"), opts,
                                              providers=["CPUExecutionProvider"])
        self.dp, self.enc, self.vec, self.voc = (load(n) for n in
                                                 ("duration_predictor", "text_encoder", "vector_estimator", "vocoder"))
        cfg = json.loads((MODEL_DIR / "onnx" / "tts.json").read_text())
        self.sr = cfg["ae"]["sample_rate"]
        self.chunk = cfg["ae"]["base_chunk_size"] * cfg["ttl"]["chunk_compress_factor"]
        self.latent_dim = cfg["ttl"]["latent_dim"] * cfg["ttl"]["chunk_compress_factor"]
        self.indexer = json.loads((MODEL_DIR / "onnx" / "unicode_indexer.json").read_text())

    @staticmethod
    def style(name: str):
        v = json.loads((MODEL_DIR / "voice_styles" / f"{name}.json").read_text())
        ttl = np.array(v["style_ttl"]["data"], np.float32).reshape([1] + v["style_ttl"]["dims"][1:])
        dp = np.array(v["style_dp"]["data"], np.float32).reshape([1] + v["style_dp"]["dims"][1:])
        return ttl, dp

    def text_ids(self, text: str, lang: str = "en"):
        t = unicodedata.normalize("NFKD", text)
        for a, b in {"–": "-", "—": "-", "“": '"', "”": '"', "‘": "'", "’": "'"}.items():
            t = t.replace(a, b)
        t = re.sub(r"\s+", " ", t).strip()
        if not re.search(r"[.!?;:,'\")\]}…]$", t):
            t += "."
        t = f"<{lang}>{t}</{lang}>"
        ids = np.array([[self.indexer[ord(c)] for c in t]], np.int64)
        return ids, np.ones((1, 1, ids.shape[1]), np.float32)

    def synth(self, text: str, style, steps: int, speed: float = 1.05, seed: int = 0):
        ttl, dp = style
        t = {}
        t0 = time.perf_counter()
        ids, mask = self.text_ids(text)
        dur = self.dp.run(None, {"text_ids": ids, "style_dp": dp, "text_mask": mask})[0] / speed
        t["dur"] = time.perf_counter() - t0
        t0 = time.perf_counter()
        emb = self.enc.run(None, {"text_ids": ids, "style_ttl": ttl, "text_mask": mask})[0]
        t["enc"] = time.perf_counter() - t0
        wav_len = int(dur[0] * self.sr)
        lat_len = (wav_len + self.chunk - 1) // self.chunk
        xt = np.random.default_rng(seed).standard_normal((1, self.latent_dim, lat_len)).astype(np.float32)
        lat_mask = np.ones((1, 1, lat_len), np.float32)
        t0 = time.perf_counter()
        total = np.array([steps], np.float32)
        for s in range(steps):
            xt = self.vec.run(None, {"noisy_latent": xt, "text_emb": emb, "style_ttl": ttl,
                                     "text_mask": mask, "latent_mask": lat_mask,
                                     "current_step": np.array([s], np.float32), "total_step": total})[0]
        t["flow"] = time.perf_counter() - t0
        t0 = time.perf_counter()
        wav = self.voc.run(None, {"latent": xt})[0][0][:wav_len]
        t["voc"] = time.perf_counter() - t0
        return wav, t


def write_wav(path: Path, wav: np.ndarray, sr: int):
    pcm = (np.clip(wav, -1, 1) * 32767).astype(np.int16)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr); w.writeframes(pcm.tobytes())


def main():
    out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
    threads = int(sys.argv[2]) if len(sys.argv) > 2 else 4
    tts = Supertonic(threads)
    tts.synth("Warm up.", tts.style("M1"), 2)
    for voice in ["M1", "M2", "M3", "F1", "F2"]:
        st = tts.style(voice)
        for steps in ([2, 3, 5] if voice == "M1" else [3]):
            tot_s = tot_a = 0.0
            for i, s in enumerate(SENTENCES):
                wav, t = tts.synth(s, st, steps)
                a = len(wav) / tts.sr
                synth = sum(t.values())
                tot_s += synth; tot_a += a
                write_wav(out / f"supertonic_{voice}_s{steps}_{i}.wav", wav, tts.sr)
                if voice == "M1":
                    print(f"{voice} steps={steps} #{i} audio={a:.2f}s synth={synth*1000:.0f}ms "
                          f"(dur {t['dur']*1000:.0f} enc {t['enc']*1000:.0f} flow {t['flow']*1000:.0f} "
                          f"voc {t['voc']*1000:.0f}) rtf={synth/a:.3f}")
            print(f"== {voice} steps={steps} threads={threads}: RTF {tot_s/tot_a:.3f}")


if __name__ == "__main__":
    main()
