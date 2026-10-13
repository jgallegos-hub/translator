"""Simulador offline del chunker (actual vs corte inteligente) sobre audio real.

Replica la cadena de la app: Silero VAD (onnx de assets, contexto de 64
muestras, frames de 512 @16 kHz) -> histéresis (umbral 0.5, minSpeech 50 ms,
minSilence 300 ms) -> AudioChunker. Compara dónde corta cada variante y si el
corte cae en medio de habla (prob. de voz alta en el frame del corte).

Uso (desde gemma-pipeline-poc/):
    uv run --with numpy --with onnxruntime python tools/chunker_sim.py [wav ...]
Sin argumentos usa aec_out.wav (o mic.wav) de cada dataset en tools/datasets/.
Escribe los chunks de cada variante en tools/datasets/chunks_<variante>/ para
escucharlos.
"""
import glob, os, sys, wave
from pathlib import Path
import numpy as np
import onnxruntime as ort

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
ROOT = Path(__file__).resolve().parent
SR, BLOCK, CTX = 16000, 512, 64
FRAME_MS = BLOCK * 1000 / SR  # 32 ms

def frames_for(ms): return max(1, int(ms * SR / 1000) // BLOCK)

def silero_probs(x: np.ndarray) -> np.ndarray:
    sess = ort.InferenceSession(str(ROOT.parent / "app/src/main/assets/silero_vad.onnx"))
    state = np.zeros((2, 1, 128), np.float32); ctx = np.zeros(CTX, np.float32)
    sr = np.array([SR], np.int64); out = []
    for i in range(len(x) // BLOCK):
        f = x[i * BLOCK:(i + 1) * BLOCK].astype(np.float32) / 32768.0
        inp = np.concatenate([ctx, f])[None, :]
        p, state = sess.run(None, {"input": inp, "state": state, "sr": sr})
        out.append(float(p[0][0])); ctx = f[-CTX:]
    return np.array(out)

def vad_states(p, thr=0.5, min_speech=50, min_sil=300):
    ms, msl = frames_for(min_speech), frames_for(min_sil)
    st, cs, cl, out = 0, 0, 0, []
    for v in p:
        if v >= thr: cs += 1; cl = 0
        else: cl += 1; cs = 0
        if st == 0 and cs >= ms: st = 1
        elif st == 1 and cl >= msl: st = 0
        out.append(st)
    return out

def chunker(states, probs, min_ms=1500, max_ms=6000, silence_end_ms=500, preroll_ms=200,
            smart=False, hard_ms=12000, pause_ms=100, thr=0.5):
    """Returns list of (start_frame, end_frame_exclusive, reason)."""
    minf, maxf, hardf = frames_for(min_ms), frames_for(max_ms), frames_for(hard_ms)
    silf, pref, pausef = frames_for(silence_end_ms), frames_for(preroll_ms), frames_for(pause_ms)
    chunks = []; collecting = False; start = 0; sil = 0; low = 0
    for i, (s, p) in enumerate(zip(states, probs)):
        if not collecting:
            if s == 1: collecting = True; start = max(0, i - pref + 1); sil = 0; low = 0
            else: continue
        else:
            sil = 0 if s == 1 else sil + 1
            low = low + 1 if p < thr else 0
        n = i - start + 1
        if not smart and n >= maxf:
            chunks.append((start, i + 1, "max")); collecting = False
        elif smart and n >= hardf:
            # Backtrack: cut at the lowest-probability frame in the last 2 s, carry the rest.
            lo = max(start + minf, i + 1 - frames_for(2000))
            cut = lo + int(np.argmin(probs[lo:i + 1])) + 1
            chunks.append((start, cut, "hard")); start = cut; sil = 0; low = 0
        elif smart and n >= maxf and low >= pausef:
            chunks.append((start, i + 1, "pause")); collecting = False
        elif s == 0 and sil >= silf and n >= minf:
            chunks.append((start, i + 1, "eou")); collecting = False
    return chunks

def chunker_v2(states, probs, min_ms=1500, silence_end_ms=500, preroll_ms=200,
               hard_ms=12000, pause_ms=100, thr=0.5):
    """Prefer end-of-utterance; only at hard_ms cut at the LAST pause (>= pause_ms
    of p<thr) seen in the chunk, carrying the remainder into the next chunk."""
    minf, hardf = frames_for(min_ms), frames_for(hard_ms)
    silf, pref, pausef = frames_for(silence_end_ms), frames_for(preroll_ms), frames_for(pause_ms)
    chunks = []; collecting = False; start = 0; sil = 0; low = 0; last_pause = None
    for i, (s, p) in enumerate(zip(states, probs)):
        if not collecting:
            if s == 1: collecting = True; start = max(0, i - pref + 1); sil = 0; low = 0; last_pause = None
            else: continue
        else:
            sil = 0 if s == 1 else sil + 1
            low = low + 1 if p < thr else 0
            if low >= pausef and i - start + 1 >= minf:
                last_pause = i + 1          # cut point = end of the current pause frame
        n = i - start + 1
        if n >= hardf:
            cut = last_pause if last_pause and last_pause > start + minf else                 max(start + minf, i + 1 - frames_for(2000)) + int(np.argmin(probs[max(start + minf, i + 1 - frames_for(2000)):i + 1])) + 1
            chunks.append((start, cut, "hard")); start = cut; sil = 0; low = 0; last_pause = None
        elif s == 0 and sil >= silf and n >= minf:
            chunks.append((start, i + 1, "eou")); collecting = False
    return chunks

def report(name, chunks, probs):
    durs = [(e - s) * FRAME_MS / 1000 for s, e, _ in chunks]
    # "Corte en habla": prob media de los 3 frames alrededor del corte >= 0.5
    mid = [r for s, e, r in chunks if r in ("max", "hard", "pause")
           and np.mean(probs[max(0, e - 2):e + 1]) >= 0.5]
    forced = [r for *_, r in chunks if r in ("max", "hard", "pause")]
    short = [d for (s, e, r), d in zip(chunks, durs) if d < 2.0 and r == "eou"]
    print(f"  {name:8s} chunks={len(chunks):3d}  cortes forzados={len(forced):2d}  "
          f"cortes A MITAD DE HABLA={len(mid):2d}  chunks <2 s={len(short):2d}  "
          f"dur p50={np.median(durs):.1f}s max={max(durs):.1f}s")

def main():
    files = sys.argv[1:] or [
        (str(Path(d) / "aec_out.wav") if (Path(d) / "aec_out.wav").exists() else str(Path(d) / "mic.wav"))
        for d in sorted(glob.glob(str(ROOT / "datasets" / "2*")))]
    for f in files:
        with wave.open(f) as w: x = np.frombuffer(w.readframes(w.getnframes()), "<i2")
        probs = silero_probs(x); states = vad_states(probs)
        print(f"{Path(f).parent.name}/{Path(f).name} ({len(x)/SR:.0f} s)")
        for name, fn in [("actual", lambda: chunker(states, probs)),
                         ("smart", lambda: chunker(states, probs, smart=True)),
                         ("smart2", lambda: chunker_v2(states, probs))]:
            ch = fn(); report(name, ch, probs)
            od = ROOT / "datasets" / f"chunks_{name}" / Path(f).parent.name
            od.mkdir(parents=True, exist_ok=True)
            for k, (s, e, r) in enumerate(ch):
                with wave.open(str(od / f"{k:02d}_{r}_{(e-s)*FRAME_MS/1000:.1f}s.wav"), "wb") as o:
                    o.setnchannels(1); o.setsampwidth(2); o.setframerate(SR)
                    o.writeframes(x[s * BLOCK:e * BLOCK].tobytes())

if __name__ == "__main__":
    main()
