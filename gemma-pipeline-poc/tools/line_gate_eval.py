"""Per-line speaker check, offline (2026-11-11). Mixed chunks (Abraham + a video / Eric's echo in the same
chunk) pass the chunk-level speaker gate whole, and with the speaker-verified prompt Haiku then translates the
other voice too (r32: "Nosotros fuimos al Art Institute" + video -> "...at the Descuaipés, pai pi").

Step 1 `lines`: re-transcribe every dumped chunk with Moonshine Tiny-es (as on the phone) to get line timings,
and score each line's audio against Abraham's profile (CAM++, tools/campp_features.py). Writes
logs/gate_dataset/lines.tsv; add a `label` column by hand (abraham / other / echo / laugh / ?).

Step 2 `eval`: compares decision rules on the labelled lines (chunk-level gate today, rule A, per-line gate).

Usage: .venv-cascade/Scripts/python -I line_gate_eval.py lines <dump_dir>...
       .venv-cascade/Scripts/python -I line_gate_eval.py eval
The audio and the labels are the user's voice: they stay in tools/logs (gitignored).
"""
import csv
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from campp_features import DATA, CamPP, read_wav, to16k  # noqa: E402

OUT = DATA / "lines.tsv"
PROFILE = DATA / "speaker_profile.json"


def rms(x: np.ndarray) -> float:
    """Same scale as the app (int16 samples)."""
    return float(np.sqrt(np.mean((x * 32768.0) ** 2))) if len(x) else 0.0


def window(x: np.ndarray, s: int, e: int, n: int) -> np.ndarray:
    """At least n samples centred on [s, e), shifted to stay inside the chunk."""
    if e - s >= n or len(x) <= n:
        return x[s:e] if e - s >= n else x
    start = min(max(0, (s + e) // 2 - n // 2), len(x) - n)
    return x[start:start + n]


def cmd_lines(dirs: list[str]):
    from moonshine_voice import download as dl
    from moonshine_voice.transcriber import ModelArch, Transcriber

    info = dl.find_model_info("es", ModelArch.TINY_STREAMING)
    path = dl.download_model_from_info(info)
    asr = Transcriber(str(path[0] if isinstance(path, tuple) else path), ModelArch.TINY_STREAMING)
    cam = CamPP()
    prof = np.array(json.loads(PROFILE.read_text())["embeddings"], np.float32)
    sim = lambda x: float((prof @ cam.embed(x)).max())  # noqa: E731

    rows = []
    for d in map(Path, dirs):
        index = list(csv.DictReader(open(d / "index.tsv", encoding="utf-8"), delimiter="\t"))
        for r in index:
            x = to16k(*read_wav(d / r["file"]))
            chunk_sim = sim(x)
            tr = asr.transcribe_without_streaming(x.tolist(), 16000)
            for i, l in enumerate(tr.lines):
                text = l.text.strip()
                if not text:
                    continue
                s = max(0, int(l.start_time * 16000))
                e = min(len(x), int((l.start_time + l.duration) * 16000))
                seg = x[s:e] if e > s else x
                rows.append({
                    "round": d.parent.name, "dir": d.name, "seq": r["seq"], "line": i, "ttsActive": r["ttsActive"],
                    "chunkDur": round(len(x) / 16000, 2), "chunkSim": round(chunk_sim, 3),
                    "start": round(l.start_time, 2), "dur": round(l.duration, 2), "rms": round(rms(seg)),
                    # CAM++ needs some audio: lines under 0.5 s are scored on a 0.5 s window around them.
                    "lineSim": round(sim(x[max(0, (s + e) // 2 - 4000):(s + e) // 2 + 4000] if e - s < 8000 else seg), 3),
                    # Same, with at least 1.5 s of audio centred on the line (short lines are unreliable alone).
                    "lineSim15": round(sim(window(x, s, e, 24000)), 3),
                    "outcome": r["outcome"], "text": text, "phoneLines": r["lines"], "label": "",
                })
            print(f"{d.parent.name}/{r['seq']}: {len(tr.lines)} lines", flush=True)
    keep = {}
    if OUT.exists():  # keep labels already written by hand
        for r in csv.DictReader(open(OUT, encoding="utf-8"), delimiter="\t"):
            keep[(r["dir"], r["seq"], r["line"])] = r["label"]
    for r in rows:
        r["label"] = keep.get((r["dir"], r["seq"], str(r["line"])), "")
    with open(OUT, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]), delimiter="\t")
        w.writeheader()
        w.writerows(rows)
    print(f"{len(rows)} lines -> {OUT}")


def cmd_eval():
    rows = [r for r in csv.DictReader(open(OUT, encoding="utf-8"), delimiter="\t") if r["label"] in ("abraham", "other", "echo", "laugh")]
    for r in rows:
        for k in ("chunkSim", "lineSim", "lineSim15", "rms", "dur"):
            r[k] = float(r[k])
    tts_gate = lambda r: 3000 if r["ttsActive"] == "true" else 1800  # noqa: E731  (4500 while AEC converges: ignored here)

    def chunk_gate(r, thr=0.40):
        return r["chunkSim"] >= thr

    def rule_a(r):
        loud = max(x["rms"] for x in rows if x["dir"] == r["dir"] and x["seq"] == r["seq"])
        return r["chunkSim"] >= 0.40 or (r["chunkSim"] >= 0.30 and loud >= 4000)

    def per_line(r, thr=0.40):
        return r["lineSim"] >= thr

    def per_line_a(r):
        return r["lineSim"] >= 0.40 or (r["lineSim"] >= 0.30 and r["rms"] >= 4000)

    def per_line15_a(r):
        return r["lineSim15"] >= 0.40 or (r["lineSim15"] >= 0.30 and r["rms"] >= 4000)

    rules = {
        "today: chunk >= 0.40 + volume": lambda r: chunk_gate(r) and r["rms"] >= tts_gate(r),
        "rule A + volume": lambda r: rule_a(r) and r["rms"] >= tts_gate(r),
        "per line >= 0.40 + volume": lambda r: per_line(r) and r["rms"] >= tts_gate(r),
        "per line A + volume": lambda r: per_line_a(r) and r["rms"] >= tts_gate(r),
        "per line A, no volume": per_line_a,
        "per line 1.5 s >= 0.40 + volume": lambda r: r["lineSim15"] >= 0.40 and r["rms"] >= tts_gate(r),
        "per line 1.5 s A + volume": lambda r: per_line15_a(r) and r["rms"] >= tts_gate(r),
        "chunk A and per line 1.5 s A + volume": lambda r: rule_a(r) and per_line15_a(r) and r["rms"] >= tts_gate(r),
        "chunk A and per line >= 0.30 + volume": lambda r: rule_a(r) and r["lineSim"] >= 0.30 and r["rms"] >= tts_gate(r),
    }
    mine = [r for r in rows if r["label"] == "abraham"]
    others = [r for r in rows if r["label"] != "abraham"]
    print(f"labelled lines: Abraham {len(mine)}, other/echo/laugh {len(others)}")
    for name, f in rules.items():
        lost = [r for r in mine if not f(r)]
        leaked = [r for r in others if f(r)]
        print(f"\n{name}: Abraham lost {len(lost)}/{len(mine)}, others through {len(leaked)}/{len(others)}")
        for r in lost[:12]:
            print(f"   lost   {r['round']}/{r['seq']}.{r['line']} chunk {r['chunkSim']:.2f} line {r['lineSim']:.2f} rms {r['rms']:.0f} {r['text'][:50]!r}")
        for r in leaked[:12]:
            print(f"   leaked {r['round']}/{r['seq']}.{r['line']} chunk {r['chunkSim']:.2f} line {r['lineSim']:.2f} rms {r['rms']:.0f} {r['text'][:50]!r}")


if __name__ == "__main__":
    {"lines": lambda: cmd_lines(sys.argv[2:]), "eval": cmd_eval}[sys.argv[1]]()
