"""Reference implementation of the speaker-gate front end, to port to Kotlin: Kaldi-style 80-bin log
mel fbank (25 ms / 10 ms, Povey window, pre-emphasis 0.97, DC removal) + global-mean normalisation,
then CAM++ (3D-Speaker zh_en advanced) through plain onnxruntime 1.23.2 — the runtime the app ships.
sherpa-onnx's Android build bundles ORT 1.28.2, which would clash with ours, so the app cannot use it.

Usage: .venv-cascade/Scripts/python -I campp_features.py      (compares against sherpa-onnx on the
dataset chunks and writes golden vectors for the Kotlin unit test)
       .venv-cascade/Scripts/python -I campp_features.py profile   (speaker_profile.json from the chunks
labelled "enroll", for adb push to /sdcard/Download/gate/ — the user's voiceprint: never to git)
"""
import json
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort

sys.path.insert(0, str(Path(__file__).parent))
from gate_eval import DATA, MODELS, Speaker, read_wav, to16k  # noqa: E402

MODEL = MODELS / "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"
SR, FRAME, SHIFT, NFFT, NMEL = 16000, 400, 160, 512, 80
LOW_HZ, HIGH_HZ, PREEMPH = 20.0, 7600.0, 0.97  # sherpa-onnx: high_freq = -400 -> 8000 - 400


def mel(f):
    return 1127.0 * np.log(1.0 + f / 700.0)


def mel_banks() -> np.ndarray:
    """Kaldi triangular filters on the mel scale; shape [NMEL, NFFT // 2 + 1]."""
    fft_bins = NFFT // 2
    fft_freq = SR / NFFT
    mlow, mhigh = mel(LOW_HZ), mel(HIGH_HZ)
    delta = (mhigh - mlow) / (NMEL + 1)
    banks = np.zeros((NMEL, fft_bins + 1), np.float32)
    for b in range(NMEL):
        left, center, right = mlow + b * delta, mlow + (b + 1) * delta, mlow + (b + 2) * delta
        for i in range(fft_bins):
            m = mel(fft_freq * i)
            if left < m < right:
                banks[b, i] = (m - left) / (center - left) if m <= center else (right - m) / (right - center)
    return banks


BANKS = mel_banks()
WINDOW = (0.5 - 0.5 * np.cos(2 * np.pi * np.arange(FRAME) / (FRAME - 1))) ** 0.85  # Povey


def frame_at(x: np.ndarray, t: int) -> np.ndarray:
    """Kaldi snip_edges=false: frame centred on t*shift + shift/2, edges reflected."""
    start = t * SHIFT + SHIFT // 2 - FRAME // 2
    idx = np.arange(start, start + FRAME)
    n = len(x)
    idx = np.where(idx < 0, -idx - 1, idx)
    idx = np.where(idx >= n, 2 * n - 1 - idx, idx)
    return x[idx]


def fbank(x: np.ndarray) -> np.ndarray:
    """x: float32 samples in [-1, 1] at 16 kHz -> [T, 80] log mel energies (snip_edges=False)."""
    n = (len(x) + SHIFT // 2) // SHIFT
    out = np.zeros((n, NMEL), np.float32)
    eps = np.finfo(np.float32).eps
    for t in range(n):
        f = frame_at(x, t).astype(np.float64)
        f = f - f.mean()
        f = np.concatenate([[f[0] - PREEMPH * f[0]], f[1:] - PREEMPH * f[:-1]])
        f = f * WINDOW
        spec = np.abs(np.fft.rfft(f, NFFT)) ** 2
        out[t] = np.log(np.maximum(BANKS @ spec, eps))
    return out


class CamPP:
    def __init__(self, model: Path = MODEL):
        self.sess = ort.InferenceSession(str(model), providers=["CPUExecutionProvider"])

    def embed(self, x: np.ndarray) -> np.ndarray:
        feats = fbank(x)
        feats = feats - feats.mean(axis=0, keepdims=True)  # feature_normalize_type: global-mean
        e = self.sess.run(None, {"x": feats[None].astype(np.float32)})[0][0]
        return e / (np.linalg.norm(e) + 1e-9)


def write_profile():
    import csv
    chunk_dir = next(p for p in DATA.iterdir() if p.is_dir())
    index = {r["seq"]: r for r in csv.DictReader(open(chunk_dir / "index.tsv", encoding="utf-8"), delimiter="\t")}
    labels = csv.DictReader(open(DATA / "labels.tsv", encoding="utf-8"), delimiter="\t")
    enroll = [r["seq"] for r in labels if "enroll" in (r.get("note") or "")]
    cam = CamPP()
    audio = [to16k(*read_wav(chunk_dir / index[s]["file"])) for s in enroll]
    emb = [cam.embed(x).round(6).tolist() for x in audio]
    out = DATA / "speaker_profile.json"
    out.write_text(json.dumps({"seconds": sum(len(x) for x in audio) / SR, "embeddings": emb}))
    print(f"profile: {len(emb)} chunks, {sum(len(x) for x in audio) / SR:.1f} s -> {out}")


def main():
    if sys.argv[1:] == ["profile"]:
        return write_profile()
    chunk_dir = next(p for p in DATA.iterdir() if p.is_dir())
    wavs = sorted(chunk_dir.glob("chunk_*.wav"))
    ours, ref = CamPP(), Speaker(MODEL)
    cos = []
    for w in wavs:
        x = to16k(*read_wav(w))
        cos.append(float(ours.embed(x) @ ref.embed(x)))
    print(f"cosine(ours, sherpa-onnx) over {len(cos)} chunks: min {min(cos):.4f} median {np.median(cos):.4f}")

    # Golden vectors for the Kotlin test: a synthetic signal (no user audio in the repo).
    t = np.arange(SR * 2) / SR
    sig = (0.3 * np.sin(2 * np.pi * 220 * t) + 0.2 * np.sin(2 * np.pi * 1330 * t) * np.sin(2 * np.pi * 3 * t)).astype(np.float32)
    feats = fbank(sig)
    golden = {
        "signal": "0.3*sin(2pi*220t) + 0.2*sin(2pi*1330t)*sin(2pi*3t), 16 kHz, 2 s",
        "frames": int(feats.shape[0]),
        "fbank_frame10": [round(float(v), 4) for v in feats[10]],
        "fbank_mean": round(float(feats.mean()), 4),
    }
    out = Path(__file__).parent.parent / "app" / "src" / "test" / "resources" / "campp_fbank_golden.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(golden, indent=1))
    print(f"golden fbank -> {out}")


if __name__ == "__main__":
    main()
