"""Desglose de latencia de la cola de TTS a partir de un log con TTSDIAG.

Uso: python tools/tts_latency.py tools/logs/<ronda>.log
"""
import re, statistics, sys
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
RE_START = re.compile(r"(\d\d:\d\d:\d\d\.\d+).*TTSDIAG start: waitMs=(\d+) queue=(\d+) chars=(\d+)")
RE_PIECE = re.compile(r"(\d\d:\d\d:\d\d\.\d+).*TTSDIAG piece: synthMs=(\d+) audioMs=(\d+) playBlockMs=(\d+)")
RE_PLAY = re.compile(r"(\d\d:\d\d:\d\d\.\d+).*TTSDIAG play: queuedBeforeMs=(-?\d+) pieceMs=(\d+)")
starts, pieces, plays = [], [], []
for line in open(sys.argv[1], encoding="utf-8", errors="replace"):
    if m := RE_START.search(line): starts.append((int(m[2]), int(m[3]), int(m[4])))
    elif m := RE_PIECE.search(line): pieces.append((int(m[2]), int(m[3]), int(m[4])))
    elif m := RE_PLAY.search(line): plays.append((int(m[2]), int(m[3])))
def st(v): 
    if not v: return "—"
    v = sorted(v); return f"n={len(v)} p50={statistics.median(v):.0f} p90={v[min(len(v)-1, int(.9*len(v)))]:.0f} max={v[-1]:.0f}"
print(f"Traducciones procesadas: {len(starts)}  |  piezas de audio: {len(pieces)}")
print(f"Espera en cola TTS (ms)     : {st([w for w,_,_ in starts])}")
print(f"Profundidad de cola al tomar: {st([q for _,q,_ in starts])}")
print(f"Síntesis por pieza (ms)     : {st([s for s,_,_ in pieces])}")
print(f"Audio por pieza (ms)        : {st([a for _,a,_ in pieces])}")
print(f"play() bloqueado (ms)       : {st([b for _,_,b in pieces])}")
rtf = sum(s for s,_,_ in pieces) / max(1, sum(a for _,a,_ in pieces))
print(f"RTF Kokoro (síntesis/audio) : {rtf:.2f}  ({'más rápido' if rtf < 1 else 'MÁS LENTO'} que tiempo real)")
gaps = [q for q,_ in plays if q < 60]
print(f"Piezas que llegaron con el altavoz ya en silencio (hueco audible): {len(gaps)}/{len(plays)}")
tot_audio = sum(a for _,a,_ in pieces); tot_synth = sum(s for s,_,_ in pieces); tot_block = sum(b for _,_,b in pieces)
print(f"Totales: audio {tot_audio/1000:.1f} s | síntesis {tot_synth/1000:.1f} s | play bloqueado {tot_block/1000:.1f} s")
