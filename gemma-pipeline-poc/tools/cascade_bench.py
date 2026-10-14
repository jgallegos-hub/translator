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


def normalize(text: str) -> list[str]:
    """Lowercase, strip accents and punctuation (WER on words only)."""
    import re
    import unicodedata
    t = unicodedata.normalize("NFKD", text.lower())
    t = "".join(c for c in t if not unicodedata.combining(c))
    return re.sub(r"[^a-z0-9ñ' ]+", " ", t).split()


def edit_ops(ref: list[str], hyp: list[str]) -> tuple[int, int, int]:
    """(substitutions, deletions, insertions) of the minimum alignment."""
    n, m = len(ref), len(hyp)
    d = [[(0, 0, 0)] * (m + 1) for _ in range(n + 1)]
    for i in range(1, n + 1):
        d[i][0] = (0, i, 0)
    for j in range(1, m + 1):
        d[0][j] = (0, 0, j)
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            if ref[i - 1] == hyp[j - 1]:
                d[i][j] = d[i - 1][j - 1]
                continue
            s, de, ins = d[i - 1][j - 1], d[i - 1][j], d[i][j - 1]
            d[i][j] = min(
                (s[0] + 1, s[1], s[2]), (de[0], de[1] + 1, de[2]), (ins[0], ins[1], ins[2] + 1),
                key=sum,
            )
    return d[n][m]


def run_wer(asr_csv: Path, script_md: Path, last_chunk: int) -> None:
    """Global WER of each model: all scripted phrases vs. all chunks
    0..last_chunk concatenated (chunk boundaries need not match phrases)."""
    import re
    ref_lines = [m.group(1) for m in re.finditer(r"^\d+\. (.+)$", script_md.read_text("utf-8"), re.M)]
    ref = [w for l in ref_lines for w in normalize(l)]
    rows = list(csv.DictReader(asr_csv.open(encoding="utf-8")))[: last_chunk + 1]
    models = [c for c in rows[0] if c not in ("file", "dur_s") and not c.endswith("_ms")]
    for mname in models:
        hyp = [w for r in rows for w in normalize(r[mname])]
        s, de, ins = edit_ops(ref, hyp)
        ms = [int(r[f"{mname}_ms"]) for r in rows]
        audio = sum(float(r["dur_s"]) for r in rows)
        print(f"{mname:6s} WER {100 * (s + de + ins) / len(ref):5.1f}%  "
              f"(sub {s}, del {de}, ins {ins}, ref {len(ref)} words)  "
              f"RTF {sum(ms) / 1000 / audio:.3f}")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if len(sys.argv) == 4 and sys.argv[1] == "asr":
        run_asr(Path(sys.argv[2]), Path(sys.argv[3]))
    elif len(sys.argv) == 5 and sys.argv[1] == "wer":
        run_wer(Path(sys.argv[2]), Path(sys.argv[3]), int(sys.argv[4]))
    else:
        sys.exit(__doc__ + "\n    ... cascade_bench.py wer asr.csv corpus/guion_f0.md LAST_CHUNK")
