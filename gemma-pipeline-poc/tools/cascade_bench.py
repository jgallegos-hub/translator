"""Cascade bench, ASR stage: run Moonshine models over a folder of chunk WAVs.

Usage (venv: tools/.venv-cascade, `pip install moonshine-voice`):
    .venv-cascade/Scripts/python -I cascade_bench.py asr datasets/chunks_smart out.csv

Writes one row per WAV with each model's transcript and wall-clock time, so
transcripts can be compared against references (WER) and checked for
hallucinations/loops on short chunks.
"""
import csv
import sys
import time
from pathlib import Path

from moonshine_voice import download as dl
from moonshine_voice.transcriber import ModelArch, Transcriber
from moonshine_voice.utils import load_wav_file

MODELS = {
    "small": ModelArch.SMALL_STREAMING,
    "tiny": ModelArch.TINY_STREAMING,
}


def load_models(language: str = "es") -> dict:
    out = {}
    for name, arch in MODELS.items():
        info = dl.find_model_info(language, arch)
        path = dl.download_model_from_info(info)
        path = path[0] if isinstance(path, tuple) else path
        out[name] = Transcriber(str(path), arch)
    return out


def transcribe(t: Transcriber, audio, sr) -> tuple[str, float]:
    t0 = time.perf_counter()
    tr = t.transcribe_without_streaming(audio, sr)
    ms = (time.perf_counter() - t0) * 1000
    return " ".join(l.text.strip() for l in tr.lines if l.text.strip()), ms


def run_asr(folder: Path, out_csv: Path) -> None:
    models = load_models()
    wavs = sorted(folder.rglob("*.wav"))
    with out_csv.open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["file", "dur_s"] + [c for m in models for c in (m, f"{m}_ms")])
        for p in wavs:
            audio, sr = load_wav_file(p)
            row = [str(p.relative_to(folder)), f"{len(audio) / sr:.2f}"]
            for name, t in models.items():
                text, ms = transcribe(t, audio, sr)
                row += [text, f"{ms:.0f}"]
            w.writerow(row)
            print(" | ".join(row))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if len(sys.argv) != 4 or sys.argv[1] != "asr":
        sys.exit(__doc__)
    run_asr(Path(sys.argv[2]), Path(sys.argv[3]))
