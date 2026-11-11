"""Speaker segmentation inside a chunk, offline (2026-11-11). In r32 Moonshine merged an English video and
Abraham into ONE line, so no per-line rule can separate them, but CAM++ over 1.5 s windows shows clearly where
the voice changes (#43: video 0.15-0.26 until 6.5 s, then Abraham 0.51-0.83).

Rule evaluated:
  windows of 1.5 s, hop HOP s; a window is "his" when sim >= THR; his region = union of his windows.
  - no window his           -> block the chunk (as today);
  - every window his        -> keep Moonshine's transcript as is (no extra cost);
  - mixed                   -> cut the audio to his region (+ MARGIN s each side) and transcribe again.
Then the usual volume gate per line.

Measured on tools/logs/gate_dataset/lines.tsv (labels by hand): a labelled line counts as kept when at least
half of its span is inside the kept audio. Mixed chunks are also re-transcribed and printed for a read.

Usage: .venv-cascade/Scripts/python -I segment_gate_eval.py [THR] [HOP]
"""
import csv
import json
import sys
import time
from collections import defaultdict
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from campp_features import DATA, CamPP, read_wav, to16k  # noqa: E402

THR = float(sys.argv[1]) if len(sys.argv) > 1 else 0.40
HOP = float(sys.argv[2]) if len(sys.argv) > 2 else 0.5
WIN, MARGIN, SR = 1.5, 0.25, 16000
QUIET = float(sys.argv[3]) if len(sys.argv) > 3 else 1200.0  # window RMS below this = silence: neither his nor other


def main():
    from moonshine_voice import download as dl
    from moonshine_voice.transcriber import ModelArch, Transcriber

    info = dl.find_model_info("es", ModelArch.TINY_STREAMING)
    path = dl.download_model_from_info(info)
    asr = Transcriber(str(path[0] if isinstance(path, tuple) else path), ModelArch.TINY_STREAMING)
    cam = CamPP()
    prof = np.array(json.loads((DATA / "speaker_profile.json").read_text())["embeddings"], np.float32)
    sim = lambda x: float((prof @ cam.embed(x)).max())  # noqa: E731

    rows = list(csv.DictReader(open(DATA / "lines.tsv", encoding="utf-8"), delimiter="\t"))
    chunks = defaultdict(list)
    for r in rows:
        chunks[(r["round"], r["dir"], r["seq"])].append(r)
    dirs = {"gate_dataset": DATA}
    mine_lost, others_through, mine_n, others_n = [], [], 0, 0
    mixed_n, windows_ms = 0, []
    for (rnd, dname, seq), lines in chunks.items():
        d = (DATA / dname) if rnd == "gate_dataset" else (DATA / rnd / dname)
        x = to16k(*read_wav(d / f"chunk_{int(seq):04d}.wav"))
        w, h = int(WIN * SR), int(HOP * SR)
        starts = list(range(0, max(1, len(x) - w + 1), h)) if len(x) > w else [0]
        t0 = time.perf_counter()
        wr = [float(np.sqrt(np.mean((x[s:s + w] * 32768.0) ** 2))) for s in starts]
        loud = [r >= QUIET for r in wr]
        his = [lo and sim(x[s:s + w]) >= THR for s, lo in zip(starts, loud)]
        windows_ms.append((time.perf_counter() - t0) * 1000)
        mask = np.zeros(len(x), bool)
        for s, ok in zip(starts, his):
            if ok:
                mask[max(0, s - int(MARGIN * SR)):s + w + int(MARGIN * SR)] = True
        other = [lo and not hi for lo, hi in zip(loud, his)]
        kind = "none" if not any(his) else ("mixed" if any(other) else "all")
        if kind == "all":
            mask[:] = True
        if kind == "mixed":
            mixed_n += 1
            # Only his runs, joined with 0.2 s of silence (others between two of his runs are cut out).
            edges = np.flatnonzero(np.diff(np.concatenate([[0], mask.astype(np.int8), [0]])))
            runs = [x[a:b] for a, b in zip(edges[::2], edges[1::2])]
            gap = np.zeros(int(0.2 * SR), np.float32)
            cut = np.concatenate([np.concatenate([r, gap]) for r in runs])
            idx = np.flatnonzero(mask)
            tr = asr.transcribe_without_streaming(cut.tolist(), SR)
            kept_lines = []
            for ln in tr.lines:
                a, b = int(ln.start_time * SR), int((ln.start_time + ln.duration) * SR)
                r = float(np.sqrt(np.mean((cut[a:max(b, a + 1)] * 32768.0) ** 2)))
                if ln.text.strip() and r >= (3000 if lines[0]["ttsActive"] == "true" else 1800):
                    kept_lines.append(ln.text.strip())
            new = " | ".join(kept_lines)
            old = " | ".join(f"[{l['label'] or '-'}] {l['text']}" for l in lines)
            print(f"MIXED {rnd}/{seq} keep {idx[0]/SR:.1f}-{idx[-1]/SR:.1f}s of {len(x)/SR:.1f}s\n   before: {old[:150]}\n   after:  {new[:150]}")
        tts_gate = 3000 if lines[0]["ttsActive"] == "true" else 1800
        for l in lines:
            if l["label"] not in ("abraham", "other", "echo", "laugh"):
                continue
            s, e = int(float(l["start"]) * SR), int((float(l["start"]) + float(l["dur"])) * SR)
            inside = mask[s:max(e, s + 1)].mean() >= 0.5 if kind != "none" else False
            kept = inside and float(l["rms"]) >= tts_gate
            tag = f"{rnd}/{seq}.{l['line']} {kind} rms {float(l['rms']):.0f} {l['text'][:50]!r}"
            if l["label"] == "abraham":
                mine_n += 1
                if not kept:
                    mine_lost.append(tag)
            else:
                others_n += 1
                if kept:
                    others_through.append(tag)
    print(f"\nTHR {THR} HOP {HOP} QUIET {QUIET:.0f}: Abraham lost {len(mine_lost)}/{mine_n}, others through {len(others_through)}/{others_n}; "
          f"mixed chunks {mixed_n}/{len(chunks)}; windows {np.median(windows_ms):.0f} ms median per chunk on this PC")
    for t in mine_lost:
        print("   lost  ", t)
    for t in others_through:
        print("   leaked", t)


if __name__ == "__main__":
    main()
