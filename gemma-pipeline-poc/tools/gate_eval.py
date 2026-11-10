"""Offline evaluation of an audio gate before ASR on Abraham's labelled chunks (tools/logs/gate_dataset).

Q1 "is it Abraham?": CAM++ speaker embeddings (sherpa-onnx). Enrolment = chunks marked "enroll";
score = max cosine similarity to the enrolment embeddings; optional negative profile = Eric (ElevenLabs
WAVs from the relay tests, resampled to 16 kHz) -> margin = sim(Abraham) - sim(Eric).
Q2 "is it speech, not laughter?": YAMNet (LiteRT) over 0.975 s windows, hop 0.48 s.

Usage: .venv-cascade/Scripts/python -I gate_eval.py [model_name]
"""
import csv
import statistics
import sys
import time
import wave
from pathlib import Path

import numpy as np
import sherpa_onnx
from ai_edge_litert.interpreter import Interpreter

ROOT = Path(__file__).parent
DATA = ROOT / "logs" / "gate_dataset"
MODELS = ROOT / "models" / "gate"
ERIC_WAVS = sorted((ROOT / "logs" / "tts_ab_fly").glob("server_eric_*.wav")) + sorted(
    (ROOT / "logs" / "tts_ab").glob("eleven_cjVig*.wav"))


def read_wav(path: Path) -> tuple[np.ndarray, int]:
    with wave.open(str(path)) as w:
        x = np.frombuffer(w.readframes(w.getnframes()), np.int16).astype(np.float32) / 32768.0
        return x, w.getframerate()


def to16k(x: np.ndarray, sr: int) -> np.ndarray:
    if sr == 16000:
        return x
    t = np.arange(0, len(x) * 16000 // sr) * sr / 16000
    return np.interp(t, np.arange(len(x)), x).astype(np.float32)


class Speaker:
    def __init__(self, model: Path):
        cfg = sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=str(model), num_threads=1)
        self.ex = sherpa_onnx.SpeakerEmbeddingExtractor(cfg)

    def embed(self, x: np.ndarray) -> np.ndarray:
        s = self.ex.create_stream()
        s.accept_waveform(16000, x)
        s.input_finished()
        e = np.array(self.ex.compute(s), np.float32)
        return e / (np.linalg.norm(e) + 1e-9)


class Yamnet:
    WIN, HOP = 15600, 7680

    def __init__(self, model: Path, class_map: Path):
        self.it = Interpreter(model_path=str(model))
        self.it.allocate_tensors()
        self.inp = self.it.get_input_details()[0]
        self.out = self.it.get_output_details()[0]
        names = [r["display_name"] for r in csv.DictReader(open(class_map, encoding="utf-8"))]
        idx = {n: i for i, n in enumerate(names)}
        self.speech = [idx[n] for n in ("Speech", "Conversation", "Narration, monologue")]
        self.laugh = [idx[n] for n in ("Laughter", "Giggle", "Snicker", "Belly laugh", "Chuckle, chortle")]
        self.cough = [idx[n] for n in ("Cough", "Throat clearing", "Breathing", "Sneeze")]

    def scores(self, x: np.ndarray) -> dict:
        if len(x) < self.WIN:
            x = np.pad(x, (0, self.WIN - len(x)))
        speech, laugh, cough = [], [], []
        for start in range(0, len(x) - self.WIN + 1, self.HOP):
            self.it.resize_tensor_input(self.inp["index"], [self.WIN]) if self.inp["shape"][0] != self.WIN else None
            self.it.set_tensor(self.inp["index"], x[start:start + self.WIN].astype(np.float32).reshape(self.inp["shape"]))
            self.it.invoke()
            p = self.it.get_tensor(self.out["index"]).reshape(-1)
            speech.append(float(p[self.speech].max()))
            laugh.append(float(p[self.laugh].max()))
            cough.append(float(p[self.cough].max()))
        return {
            "speech": float(np.mean(speech)),
            "laugh": float(np.max(laugh)),
            "cough": float(np.max(cough)),
            "laugh_frac": float(np.mean([l > s for l, s in zip(laugh, speech)])),
        }


def main():
    model_name = sys.argv[1] if len(sys.argv) > 1 else "3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx"
    chunk_dir = next(p for p in DATA.iterdir() if p.is_dir())
    index = {r["seq"]: r for r in csv.DictReader(open(chunk_dir / "index.tsv", encoding="utf-8"), delimiter="\t")}
    labels = list(csv.DictReader(open(DATA / "labels.tsv", encoding="utf-8"), delimiter="\t"))

    spk = Speaker(MODELS / model_name)
    yam = Yamnet(MODELS / "yamnet.tflite", MODELS / "yamnet_class_map.csv")

    audio = {r["seq"]: to16k(*read_wav(chunk_dir / index[r["seq"]]["file"])) for r in labels}
    enroll = [r["seq"] for r in labels if "enroll" in (r.get("note") or "")]
    t0 = time.perf_counter()
    enroll_emb = [spk.embed(audio[s]) for s in enroll]
    eric_emb = [spk.embed(to16k(*read_wav(p))) for p in ERIC_WAVS[:6]]
    print(f"enrolment: {len(enroll)} chunks ({sum(len(audio[s]) for s in enroll) / 16000:.1f} s); "
          f"Eric profile: {len(eric_emb)} clips")

    rows, spk_ms, yam_ms = [], [], []
    for r in labels:
        s = r["seq"]
        x = audio[s]
        t = time.perf_counter(); e = spk.embed(x); spk_ms.append((time.perf_counter() - t) * 1000 / (len(x) / 16000))
        t = time.perf_counter(); y = yam.scores(x); yam_ms.append((time.perf_counter() - t) * 1000 / (len(x) / 16000))
        sim_ab = max(float(e @ v) for v in enroll_emb if s not in enroll) if s not in enroll else float("nan")
        sim_eric = max(float(e @ v) for v in eric_emb)
        rows.append({**r, "dur": len(x) / 16000, "sim_ab": sim_ab, "sim_eric": sim_eric, **y,
                     "outcome": index[s]["outcome"], "text": index[s]["spanish"] or index[s]["lines"]})

    print(f"cost on this PC (1 thread): speaker {statistics.median(spk_ms):.0f} ms, YAMNet {statistics.median(yam_ms):.0f} ms per second of audio")
    print(f"\n{'seq':>3} {'label':8} {'dur':>4} {'simAb':>6} {'simEric':>7} {'speech':>6} {'laugh':>6}  outcome    text")
    for r in rows:
        if r["seq"] in enroll:
            continue
        print(f"{r['seq']:>3} {r['label']:8} {r['dur']:4.1f} {r['sim_ab']:6.2f} {r['sim_eric']:7.2f} "
              f"{r['speech']:6.2f} {r['laugh']:6.2f}  {r['outcome']:10} {r['text'][:50]}")

    scored = [r for r in rows if r["seq"] not in enroll and r["label"] in ("abraham", "other", "echo", "laugh")]
    pos = sorted(r["sim_ab"] for r in scored if r["label"] == "abraham")
    neg = sorted(r["sim_ab"] for r in scored if r["label"] != "abraham")
    print(f"\nAbraham sim: min {pos[0]:.2f} p10 {pos[len(pos) // 10]:.2f} median {statistics.median(pos):.2f} (n={len(pos)})")
    print(f"not-him sim: max {neg[-1]:.2f} p90 {neg[int(len(neg) * .9)]:.2f} median {statistics.median(neg):.2f} (n={len(neg)})")
    for thr in (0.3, 0.35, 0.4, 0.45, 0.5, 0.55, 0.6):
        frr = sum(p < thr for p in pos) / len(pos)
        far = sum(n >= thr for n in neg) / len(neg)
        print(f"  threshold {thr:.2f}: rejects Abraham {frr:5.1%}  lets others through {far:5.1%}")


if __name__ == "__main__":
    main()
