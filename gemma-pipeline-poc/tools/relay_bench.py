"""Server-side voice prototype on PC: Spanish text -> Claude Haiku 5.5 (direct, streaming) ->
clause chunker -> ElevenLabs streaming TTS -> time to first audio byte. Simulates the planned
own relay (phone sends Moonshine text, server returns audio) to decide the architecture.

Usage:
  .venv-cascade/Scripts/python -I relay_bench.py voices <provider>
  .venv-cascade/Scripts/python -I relay_bench.py tts <provider> <out_dir> <voice_id> [model]
  .venv-cascade/Scripts/python -I relay_bench.py relay <provider> <out_dir> <voice_id> [model]
Providers: eleven (ElevenLabs, default model eleven_flash_v2_5) and deepinfra (Kokoro-82M via
DeepInfra's ElevenLabs-compatible stream endpoint, voice e.g. am_puck).
Keys: ANTHROPIC_API_KEY, ELEVENLABS_API_KEY, DEEPINFRA_API_KEY from the process env, else the
Windows user env. Never printed. Output audio: PCM 24 kHz mono, saved as WAV for listening.
"""
import json
import os
import re
import sys
import time
import urllib.request
import wave
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

PROVIDERS = {
    # base URL, key env var, auth header builder, default model
    "eleven": ("https://api.elevenlabs.io/v1", "ELEVENLABS_API_KEY",
               lambda k: {"xi-api-key": k}, "eleven_flash_v2_5"),
    "deepinfra": ("https://api.deepinfra.com/v1", "DEEPINFRA_API_KEY",
                  lambda k: {"Authorization": f"Bearer {k}"}, "hexgrad/Kokoro-82M"),
}
SR = 24000

TTS_SENTENCES = [
    "Oh, for fuck's sake.",
    "What do you think?",
    "Okay friends, good morning, how are you all?",
    "I honestly don't like working on electric generators, to be real.",
    "Today we have maintenance on the air conditioning units and the UPS.",
    "We're going to Navy Pier and then to Millennium Park.",
]

RELAY_PHRASES = [
    "pa puta madre",
    "¿qué opinan?",
    "qué les parece si vamos a glenn ellin",
    "no mamen como que no va a venir el profesor",
    "hoy tenemos mantenimiento a los equipos de ira condicionado y los generadores eléctricos",
    "bueno... lo bueno es que tenemos calefacción",
    "Estuvimos esperando el autobús casi cuarenta minutos y al final pasó lleno, así que decidimos caminar.",
]


def key(name: str) -> str:
    v = os.environ.get(name)
    if not v and sys.platform == "win32":
        import winreg
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, "Environment") as k:
                v, _ = winreg.QueryValueEx(k, name)
        except FileNotFoundError:
            v = None
    if not v:
        sys.exit(f"{name} not configured")
    return v


def write_wav(path: Path, pcm: bytes):
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm)


def tts_stream(provider: str, text: str, voice: str, model: str):
    """POST /text-to-speech/{voice}/stream (ElevenLabs API shape, also served by DeepInfra);
    yields PCM chunks as they arrive."""
    base, key_name, auth, _ = PROVIDERS[provider]
    if provider == "eleven":
        url, body = f"{base}/text-to-speech/{voice}/stream?output_format=pcm_{SR}", {"text": text, "model_id": model}
    else:
        url = f"{base}/text-to-speech/{voice}/stream?output_format=pcm"
        body = {"text": text, "model_id": model, "output_format": "pcm", "language_code": "en"}
    req = urllib.request.Request(
        url, data=json.dumps(body).encode(),
        headers={**auth(key(key_name)), "content-type": "application/json"}, method="POST",
    )
    with urllib.request.urlopen(req, timeout=20) as r:
        while True:
            b = r.read1(4096)
            if not b:
                break
            yield b


def cmd_voices(provider: str):
    base, key_name, auth, _ = PROVIDERS[provider]
    req = urllib.request.Request(f"{base}/voices", headers=auth(key(key_name)))
    with urllib.request.urlopen(req, timeout=20) as r:
        data = json.load(r)
    for v in data.get("voices", []):
        lab = v.get("labels", {})
        print(f"{v['voice_id']}  {v['name']:<24} {lab.get('gender','?'):<7} {lab.get('accent','?'):<10} "
              f"{lab.get('age','?'):<12} {v.get('category','')}")


def cmd_tts(provider: str, out: Path, voice: str, model: str):
    out.mkdir(parents=True, exist_ok=True)
    list(tts_stream(provider, "Warm up.", voice, model))
    firsts = []
    for i, s in enumerate(TTS_SENTENCES):
        t0 = time.perf_counter(); first = None; pcm = b""
        for b in tts_stream(provider, s, voice, model):
            if first is None:
                first = time.perf_counter() - t0
            pcm += b
        total = time.perf_counter() - t0
        firsts.append(first)
        write_wav(out / f"{provider}_{voice}_{i}.wav", pcm)
        print(f"#{i} first_audio={first*1000:.0f}ms total={total*1000:.0f}ms audio={len(pcm)/2/SR:.2f}s  {s}")
    firsts.sort()
    print(f"== TTS {provider} {model} {voice}: first audio p50={firsts[len(firsts)//2]*1000:.0f}ms max={firsts[-1]*1000:.0f}ms")


# Same session prompt as the Edge Function (single source of truth there).
def session_system() -> str:
    src = (Path(__file__).parent.parent / "backend" / "supabase" / "functions" / "translate" / "index.ts").read_text(
        encoding="utf-8")
    body = src.split("const SYSTEM = `", 1)[1].split("`;", 1)[0]
    return body.replace("\\\n", "")


CLAUSE_END = re.compile(r"[.!?;:,](\s|$)")


def cmd_relay(provider: str, out: Path, voice: str, model: str):
    import anthropic
    out.mkdir(parents=True, exist_ok=True)
    client = anthropic.Anthropic(api_key=key("ANTHROPIC_API_KEY"), max_retries=1)
    system = [{"type": "text", "text": session_system(), "cache_control": {"type": "ephemeral"}}]
    list(tts_stream(provider, "Warm up.", voice, model))
    history, rows = [], []
    for i, es in enumerate(RELAY_PHRASES):
        prev = "\n".join(f"- {h}" for h in history[-3:]) or "(none)"
        t0 = time.perf_counter()
        first_text = first_clause_t = first_audio = None
        buf, clause = "", None
        with client.messages.stream(
            model="claude-haiku-5-5", max_tokens=300, thinking={"type": "disabled"},
            output_config={"effort": "low"}, system=system,
            messages=[{"role": "user", "content": f"Previous utterances:\n{prev}\n\nCurrent utterance:\n{es}"}],
        ) as stream:
            for delta in stream.text_stream:
                if first_text is None:
                    first_text = time.perf_counter() - t0
                buf += delta
                # First clause: a terminator after >= 3 words, or 8 words with no terminator.
                if clause is None:
                    m = CLAUSE_END.search(buf)
                    words = len(buf.split())
                    if (m and len(buf[:m.end()].split()) >= 3) or words >= 8:
                        cut = m.end() if m and len(buf[:m.end()].split()) >= 3 else len(buf)
                        clause, buf = buf[:cut].strip(), buf[cut:]
                        first_clause_t = time.perf_counter() - t0
                        break
            rest = "".join(stream.text_stream)
        full_en = (clause or "") + " " + (buf + rest)
        if clause is None:
            clause, buf, first_clause_t = full_en.strip(), "", time.perf_counter() - t0
        else:
            buf = (buf + rest).strip()
        # TTS of the first clause: this is what the listener hears first.
        pcm = b""
        for b in tts_stream(provider, clause, voice, model):
            if first_audio is None:
                first_audio = time.perf_counter() - t0
            pcm += b
        if buf:
            for b in tts_stream(provider, buf, voice, model):
                pcm += b
        write_wav(out / f"relay_{provider}_{voice}_{i}.wav", pcm)
        history.append(es)
        rows.append(first_audio)
        print(f"#{i} haiku_first={first_text*1000:.0f}ms clause={first_clause_t*1000:.0f}ms "
              f"FIRST_AUDIO={first_audio*1000:.0f}ms  '{es}' -> '{full_en.strip()}'  [1st: '{clause}']")
    rows.sort()
    print(f"== relay (Haiku direct + {provider} {model} {voice}): first audio p50={rows[len(rows)//2]*1000:.0f}ms max={rows[-1]*1000:.0f}ms")


def main():
    a = sys.argv[1:]
    if len(a) >= 2 and a[0] == "voices" and a[1] in PROVIDERS:
        cmd_voices(a[1])
    elif len(a) >= 4 and a[0] in ("tts", "relay") and a[1] in PROVIDERS:
        model = a[4] if len(a) > 4 else PROVIDERS[a[1]][3]
        (cmd_tts if a[0] == "tts" else cmd_relay)(a[1], Path(a[2]), a[3], model)
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
