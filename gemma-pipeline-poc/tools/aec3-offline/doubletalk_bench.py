"""E2b — métrica de doble habla para AEC3 (offline).

Inserta tramos de voz sola del propio dataset sobre tramos de eco (doble
habla sintética con voz conocida), corre AEC3 con varias configuraciones y
reporta, por config: (a) supresión del eco (RMS mediano del eco después) y
(b) preservación de la voz insertada (dB; 0 = intacta).

Uso (desde gemma-pipeline-poc/):
    uv run --with numpy python tools/aec3-offline/doubletalk_bench.py [datasetDir]
Requiere `gradle installDist` en tools/aec3-offline.
"""
import copy, json, os, subprocess, sys, wave, glob
from pathlib import Path
import numpy as np
sys.stdout.reconfigure(encoding="utf-8", errors="replace")

SR = 16000; LAG = int(0.504 * SR); PRE = 380
ROOT = Path(__file__).resolve().parent
RUNNER = ROOT / "build/install/aec3-offline/bin/aec3-offline.bat"
JAVA_HOME = r"C:\Program Files\Android\Android Studio\jbr"

def rd(p):
    with wave.open(str(p), "rb") as w: return np.frombuffer(w.readframes(w.getnframes()), "<i2").astype(np.float64)
def wr(p, x):
    with wave.open(str(p), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(np.clip(np.round(x), -32768, 32767).astype("<i2").tobytes())
def rms(x): return float(np.sqrt(np.mean(x ** 2))) if len(x) else 0.0

d = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(sorted(glob.glob("tools/datasets/2*"))[-1])
mic, ref = rd(d / "mic.wav"), rd(d / "ref.wav")
n = min(len(mic), len(ref))

# Etiquetado por ventanas de 250 ms
W = SR // 4
lab = []
for s in range(2 * SR, n - W, W):
    r_at = rms(ref[s - LAG:s - LAG + W]); r_any = rms(ref[max(0, s - int(1.6 * SR)):s - int(0.3 * SR) + W])
    m = rms(mic[s:s + W])
    lab.append((s, "echo" if r_at > 1500 and m > 300 else "voice" if (r_any < 10 and m > 1500) else "-"))
def runs(kind, min_len):
    out, cur = [], []
    for s, k in lab:
        if k == kind: cur.append(s)
        else:
            if len(cur) * W >= min_len: out.append((cur[0], cur[-1] + W))
            cur = []
    return out
echo_runs = runs("echo", SR); voice_runs = runs("voice", SR)
print(f"tramos de eco ≥1 s: {len(echo_runs)}   tramos de voz sola ≥1 s: {len(voice_runs)}")

GAIN_DB = float(os.environ.get("DT_GAIN_DB", "0"))   # nivel de la voz insertada
g = 10 ** (GAIN_DB / 20)
# Inserta voz sola sobre los tramos de eco (ciclando los tramos de voz)
mic_dt = mic.copy(); inserts = []
for i, (a, b) in enumerate(echo_runs):
    va, vb = voice_runs[i % len(voice_runs)]
    L = min(b - a, vb - va)
    mic_dt[a:a + L] += g * mic[va:va + L]; inserts.append((a, L, va))
wr(d / "mic_dt.wav", mic_dt)
print(f"voz insertada a {GAIN_DB:+.0f} dB; voz/eco ≈ {20*np.log10(g*np.median([rms(mic[va:va+L]) for a,L,va in inserts])/np.median([rms(mic[a:a+L]) for a,L,va in inserts])):+.1f} dB")

base = json.load(open(ROOT.parent / "datasets/aec3_default_config.json"))
def variant(**kw):
    c = copy.deepcopy(base); s = c["aec3"]["suppressor"]
    for path, v in kw.items():
        node = s; keys = path.split("__")
        for k in keys[:-1]: node = node[k]
        node[keys[-1]] = v
    return c
CONFIGS_ALL = {
    "default": base,
    "nearend_sensible": variant(dominant_nearend_detection__enr_threshold=1.0,
                                dominant_nearend_detection__snr_threshold=10,
                                dominant_nearend_detection__trigger_threshold=4,
                                dominant_nearend_detection__hold_duration=100),
    "subband_nearend": variant(use_subband_nearend_detection=1),
    "mascaras_suaves": variant(normal_tuning__mask_lf=[0.6, 0.8, 0.3], normal_tuning__mask_hf=[0.2, 0.3, 0.3]),
    "combo": variant(dominant_nearend_detection__enr_threshold=1.0,
                     dominant_nearend_detection__snr_threshold=10,
                     dominant_nearend_detection__trigger_threshold=4,
                     dominant_nearend_detection__hold_duration=100,
                     normal_tuning__mask_lf=[0.5, 0.6, 0.3], normal_tuning__mask_hf=[0.15, 0.25, 0.3]),
}

CONFIGS = {k: v for k, v in CONFIGS_ALL.items() if k in os.environ.get("DT_CONFIGS", ",".join(CONFIGS_ALL)).split(",")}

def run(cfg_name, cfg, mic_name, out_name):
    cp = d / f"cfg_{cfg_name}.json"; json.dump(cfg, open(cp, "w"))
    env = dict(os.environ, JAVA_HOME=JAVA_HOME,
               JAVA_OPTS=f"-Dmic={mic_name} -Dcfg={cp} -Dout={out_name}")
    subprocess.run([str(RUNNER), str(d), str(PRE)], env=env, check=True, capture_output=True)
    return rd(d / out_name)

print(f"\n{'config':18s} {'eco RMS p50 (antes→después)':>28s} {'voz en doble habla':>20s} {'voz sola':>10s}")
for name, cfg in CONFIGS.items():
    out = run(name, cfg, "mic.wav", f"out_{name}.wav")
    out_dt = run(name, cfg, "mic_dt.wav", f"outdt_{name}.wav")
    eco_b = [rms(mic[a:b]) for a, b in echo_runs]; eco_a = [rms(out[a:b]) for a, b in echo_runs]
    v_in = g * g * sum(np.sum(mic[va:va + L] ** 2) for a, L, va in inserts)
    v_out = sum(np.sum((out_dt[a:a + L] - out[a:a + L]) ** 2) for a, L, va in inserts)
    solo = 10 * np.log10(sum(np.sum(out[a:b] ** 2) for a, b in voice_runs) / sum(np.sum(mic[a:b] ** 2) for a, b in voice_runs))
    print(f"{name:18s} {np.median(eco_b):12.0f} → {np.median(eco_a):6.0f}         {10*np.log10(v_out/v_in):+8.1f} dB {solo:+8.1f} dB")
