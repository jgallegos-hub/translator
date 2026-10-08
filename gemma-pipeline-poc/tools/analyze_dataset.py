"""E0/E1 — análisis offline de un dataset de eco grabado con la app.

Uso (desde gemma-pipeline-poc/):
    uv run --with numpy python tools/analyze_dataset.py tools/datasets/<timestamp>
    uv run --with numpy python tools/analyze_dataset.py            # el más reciente

Reporta:
  - E1: delay altavoz→mic por chirp (GCC-PHAT entre ref.wav y mic.wav),
    con media / desviación / rango → ¿es predecible dentro de ±50 ms?
  - E0: cuánto del tiempo hay referencia sonando, y nivel del mic
    con/sin referencia (indicador grueso de eco vs voz).
"""
import sys
import wave
from pathlib import Path

import numpy as np

SR = 16_000
ROOT = Path(__file__).resolve().parent
MAX_DELAY_S = 1.0  # buscamos el eco hasta 1 s después del chirp


def read_wav(p: Path) -> np.ndarray:
    with wave.open(str(p), "rb") as w:
        assert w.getframerate() == SR and w.getnchannels() == 1 and w.getsampwidth() == 2, p
        return np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float32)


def read_events(p: Path):
    rows = []
    for line in p.read_text(encoding="utf-8").splitlines()[1:]:
        kind, pos, length, note = (line.split(",", 3) + [""])[:4]
        rows.append((kind, int(pos), int(length), note))
    return rows


def gcc_phat(sig: np.ndarray, ref: np.ndarray, max_shift: int) -> tuple[int, float]:
    """Delay (muestras) de `ref` dentro de `sig` y pico normalizado (confianza)."""
    n = len(sig) + len(ref)
    nfft = 1 << (n - 1).bit_length()
    S = np.fft.rfft(sig, nfft)
    R = np.fft.rfft(ref, nfft)
    X = S * np.conj(R)
    X /= np.abs(X) + 1e-12
    cc = np.fft.irfft(X, nfft)
    cc = cc[: max_shift + 1]  # solo delays positivos (el eco llega después)
    k = int(np.argmax(cc))
    conf = float(cc[k] / (np.std(cc) + 1e-12))
    return k, conf


def rms_db(x: np.ndarray) -> float:
    return 20 * np.log10(np.sqrt(np.mean(x ** 2)) + 1e-9) if len(x) else float("-inf")


def analyse(d: Path):
    mic, ref = read_wav(d / "mic.wav"), read_wav(d / "ref.wav")
    events = read_events(d / "events.csv")
    print(f"=== {d.name} — {len(mic) / SR:.1f} s ===")

    # E1: chirps → el bloque ref que sigue a cada marcador chirp_N
    delays = []
    refs = [e for e in events if e[0] == "ref"]
    for kind, pos, _, note in events:
        if kind != "mark" or not note.startswith("chirp"):
            continue
        nxt = next((r for r in refs if r[1] >= pos), None)
        if nxt is None:
            continue
        start, length = nxt[1], nxt[2]
        seg_ref = ref[start:start + length]
        seg_mic = mic[start:start + length + int(MAX_DELAY_S * SR)]
        if len(seg_mic) < length:
            continue
        k, conf = gcc_phat(seg_mic, seg_ref, int(MAX_DELAY_S * SR))
        delays.append((note, k * 1000 / SR, conf))
    if delays:
        good = [ms for _, ms, c in delays if c > 8]
        print("E1 — delay altavoz→mic por chirp (GCC-PHAT; confianza = pico/σ):")
        for note, ms, c in delays:
            print(f"  {note:>9}: {ms:7.1f} ms   conf={c:5.1f}{'' if c > 8 else '  (baja, descartado)'}")
        if good:
            g = np.array(good)
            print(f"  → n={len(g)} media={g.mean():.1f} ms  σ={g.std():.1f} ms  "
                  f"rango={g.min():.1f}–{g.max():.1f} ms  (span {g.max() - g.min():.1f} ms)")
            verdict = "SÍ" if g.max() - g.min() <= 100 else "NO"
            print(f"  ¿Delay predecible dentro de ±50 ms? {verdict}")
    else:
        print("E1 — no hay chirps en este dataset (botón 'Chirps ×10').")

    # E0: actividad de la referencia y niveles del mic
    frame = SR // 50  # 20 ms
    nf = min(len(mic), len(ref)) // frame
    ref_on = np.array([np.abs(ref[i * frame:(i + 1) * frame]).max() > 300 for i in range(nf)])
    micf = mic[: nf * frame].reshape(nf, frame)
    mic_db = 20 * np.log10(np.sqrt((micf ** 2).mean(axis=1)) / 32768 + 1e-9)
    print(f"E0 — referencia sonando {100 * ref_on.mean():.0f}% del tiempo")
    if ref_on.any():
        print(f"  mic durante referencia : p50 {np.median(mic_db[ref_on]):.1f} dBFS  "
              f"p90 {np.percentile(mic_db[ref_on], 90):.1f}")
    if (~ref_on).any():
        print(f"  mic sin referencia     : p50 {np.median(mic_db[~ref_on]):.1f}  "
              f"p90 {np.percentile(mic_db[~ref_on], 90):.1f}  (voz + ruido)")
    print()


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if len(sys.argv) > 1:
        dirs = [Path(sys.argv[1])]
    else:
        base = ROOT / "datasets"
        dirs = sorted(p for p in base.glob("*") if (p / "mic.wav").exists())[-1:] if base.exists() else []
    if not dirs:
        sys.exit("No hay datasets. Bájalos con: python tools/device_test.py pull")
    for d in dirs:
        analyse(d)
