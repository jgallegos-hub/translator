"""Build app/libs/moonshine-voice-<ver>-noort.aar (arm64 only, without ORT).

The Moonshine AAR bundles its own libonnxruntime.so (1.23.2, ~6 MB reduced
build), which collides with the one from onnxruntime-android that Kokoro and
Silero use. The app pins onnxruntime-android to the same 1.23.2, so this
script drops Moonshine's copy (and the non-arm64 ABIs) to keep one runtime.

Usage (from gemma-pipeline-poc/):  python tools/vendor_moonshine.py
"""
import io
import urllib.request
import zipfile
from pathlib import Path

VERSION = "0.1.5"
URL = f"https://repo1.maven.org/maven2/ai/moonshine/moonshine-voice/{VERSION}/moonshine-voice-{VERSION}.aar"
OUT = Path(__file__).resolve().parent.parent / "app" / "libs" / f"moonshine-voice-{VERSION}-noort.aar"


def keep(name: str) -> bool:
    if name.startswith("jni/"):
        return name.startswith("jni/arm64-v8a/") and not name.endswith("libonnxruntime.so")
    return True


def main() -> None:
    src = zipfile.ZipFile(io.BytesIO(urllib.request.urlopen(URL).read()))
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as dst:
        for info in src.infolist():
            if keep(info.filename):
                dst.writestr(info, src.read(info.filename))
    print(f"wrote {OUT} ({OUT.stat().st_size / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
