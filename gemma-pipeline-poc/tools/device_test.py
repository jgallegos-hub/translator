"""Anti-echo device test helper (Capas 1/2/3, commit 2db432c).

Usage (from gemma-pipeline-poc/):
    python tools/device_test.py install            # install the debug APK on the connected phone
    python tools/device_test.py record ronda1      # clear logcat, capture until Ctrl+C -> tools/logs/ronda1.log
    python tools/device_test.py summary ronda1     # summarise one round
    python tools/device_test.py summary            # summarise every round in tools/logs/
    python tools/device_test.py pull               # copy echo datasets (E0/E1) to tools/datasets/

Each round: set the toggles in the app, press Start, run `record`, speak the
script, press Ctrl+C, then Stop in the app.
"""
import os
import re
import statistics
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
LOG_DIR = ROOT / "logs"
DATASET_DIR = ROOT / "datasets"
DEVICE_DATASETS = "/sdcard/Android/data/com.travel2chicago.gemmapipeline/files/echo_dataset"
APK = ROOT.parent / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
TAGS = ["AstChunkRouter", "TtsRouter", "VadChunkingPipeline", "GemmaPipelineVM"]


def adb_path() -> str:
    local = Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk" / "platform-tools" / "adb.exe"
    return str(local) if local.exists() else "adb"


def adb(*args, **kw):
    return subprocess.run([adb_path(), *args], **kw)


def require_device():
    out = adb("devices", capture_output=True, text=True).stdout
    devices = [l for l in out.splitlines()[1:] if l.strip().endswith("device")]
    if not devices:
        sys.exit("No device connected (adb devices is empty). Plug in the phone with USB debugging on.")


def cmd_install():
    require_device()
    if not APK.exists():
        sys.exit(f"APK not found: {APK}")
    adb("install", "-r", str(APK), check=True)


def cmd_record(name: str):
    require_device()
    LOG_DIR.mkdir(exist_ok=True)
    path = LOG_DIR / f"{name}.log"
    adb("logcat", "-c")
    print(f"Recording to {path} — speak now; Ctrl+C to stop.")
    with open(path, "w", encoding="utf-8") as f:
        proc = subprocess.Popen(
            [adb_path(), "logcat", "-v", "time", "-s", *TAGS],
            stdout=f, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace",
        )
        try:
            proc.wait()
        except KeyboardInterrupt:
            proc.terminate()
    print()
    summarise(path)


RE_ACCEPT = re.compile(r"Chunk accepted: RMS ([\d.]+)")
RE_LOW = re.compile(r"Chunk discarded: low RMS \(([\d.]+)")
RE_SKIP = re.compile(r"Chunk discarded: non-Spanish audio")
RE_ECHO = re.compile(r"echo of TTS output \(sim=([\d.]+)\) text='(.*?)' matched='(.*?)'")
RE_META = re.compile(r"Translation discarded: meta-text|dropping rest of chunk after meta-text")
RE_TRANSL = re.compile(r"Translation #\d+ \((\d+)ms, firstToken=(\d+)ms\): (.*)")
RE_TTS = re.compile(r"TTS ready \(.*?firstAudio=(\d+)ms")


def stats(values):
    if not values:
        return "—"
    v = sorted(values)
    p = lambda q: v[min(len(v) - 1, int(q * len(v)))]
    return f"n={len(v)} min={v[0]:.0f} p50={statistics.median(v):.0f} p90={p(0.9):.0f} max={v[-1]:.0f}"


def summarise(path: Path):
    accepted, low, sims, ft, fa = [], [], [], [], []
    rms_skip, rms_echo, rms_translated = [], [], []
    last_rms = None  # router is single-consumer: outcomes follow their chunk's RMS line
    skip = meta = 0
    translations, echoes = [], []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if m := RE_ACCEPT.search(line):
            last_rms = float(m.group(1))
            accepted.append(last_rms)
        elif m := RE_LOW.search(line):
            low.append(float(m.group(1)))
        elif RE_SKIP.search(line):
            skip += 1
            if last_rms is not None:
                rms_skip.append(last_rms)
        elif m := RE_ECHO.search(line):
            sims.append(float(m.group(1)))
            echoes.append((m.group(1), m.group(2), m.group(3)))
            if last_rms is not None:
                rms_echo.append(last_rms)
        elif RE_META.search(line):
            meta += 1
        elif m := RE_TRANSL.search(line):
            if int(m.group(2)) > 0:
                ft.append(int(m.group(2)))
            translations.append(m.group(3))
            if last_rms is not None:
                rms_translated.append(last_rms)
                last_rms = None  # count each chunk once even if it yields several sentences
        elif m := RE_TTS.search(line):
            if int(m.group(1)) > 0:
                fa.append(int(m.group(1)))
    total = len(accepted) + len(low)
    print(f"=== {path.stem} ===")
    print(f"Chunks: {total}  (accepted {len(accepted)}, low RMS {len(low)})")
    print(f"Skipped non-Spanish: {skip}   Echo dropped: {len(sims)}   Meta-text dropped: {meta}")
    print(f"Translations emitted (sentences): {len(translations)}")
    print(f"RMS accepted : {stats(accepted)}")
    print(f"RMS low      : {stats(low)}")
    print(f"RMS of chunks -> SKIP      : {stats(rms_skip)}")
    print(f"RMS of chunks -> echo      : {stats(rms_echo)}")
    print(f"RMS of chunks -> translated: {stats(rms_translated)}")
    print(f"First token ms: {stats(ft)}")
    print(f"First audio ms: {stats(fa)}  (per TTS event; first value per utterance is the meaningful one)")
    if echoes:
        print("Echo matches:")
        for sim, text, matched in echoes:
            print(f"  sim={sim}  '{text}'  <-  '{matched}'")
    if translations:
        print("Translations:")
        for t in translations:
            print(f"  - {t}")
    print()


def cmd_pull():
    require_device()
    DATASET_DIR.mkdir(exist_ok=True)
    adb("pull", DEVICE_DATASETS + "/.", str(DATASET_DIR), check=True)
    print(f"Datasets en {DATASET_DIR}. Analiza con: uv run --with numpy python tools/analyze_dataset.py")


def cmd_summary(name: str | None):
    files = [LOG_DIR / f"{name}.log"] if name else sorted(LOG_DIR.glob("*.log"))
    if not files:
        sys.exit(f"No logs in {LOG_DIR}")
    for f in files:
        summarise(f)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    cmd, arg = sys.argv[1], (sys.argv[2] if len(sys.argv) > 2 else None)
    if cmd == "install":
        cmd_install()
    elif cmd == "record" and arg:
        cmd_record(arg)
    elif cmd == "pull":
        cmd_pull()
    elif cmd == "summary":
        cmd_summary(arg)
    else:
        sys.exit(__doc__)
