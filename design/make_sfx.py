"""Generates the temporary sound effects in assets/sfx/ (mono 16-bit 22.05 kHz WAV)."""
import math, random, struct, wave, os

RATE = 22050
OUT = os.path.join(os.path.dirname(__file__), "..", "assets", "sfx")
random.seed(7)

def write(name, samples):
    peak = max(1e-6, max(abs(s) for s in samples))
    with wave.open(os.path.join(OUT, name), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(s / peak * 0.9 * 32767)) for s in samples))

def env(i, n, attack=0.005, power=2.0):
    t = i / RATE
    a = min(1.0, t / attack) if attack > 0 else 1.0
    return a * (1 - i / n) ** power

def land():
    n = int(RATE * 0.16); out = []
    for i in range(n):
        t = i / RATE
        f = 150 - 70 * (i / n)
        out.append(env(i, n, 0.002, 3) * (math.sin(2 * math.pi * f * t) + 0.25 * (random.random() * 2 - 1) * (1 - i / n) ** 8))
    return out

def fall():
    n = int(RATE * 0.55); out = []; lp = 0.0
    for i in range(n):
        p = i / n
        cutoff = 0.25 * (1 - p) + 0.02
        lp += cutoff * ((random.random() * 2 - 1) - lp)
        out.append(lp * math.sin(math.pi * p) ** 0.7)
    return out

def tone_seq(freqs, dur, shape="tri"):
    out = []
    for f in freqs:
        n = int(RATE * dur)
        for i in range(n):
            t = i / RATE
            ph = (f * t) % 1.0
            s = 4 * abs(ph - 0.5) - 1 if shape == "tri" else math.sin(2 * math.pi * f * t)
            out.append(s * env(i, n, 0.004, 1.5))
    return out

def click():
    n = int(RATE * 0.05)
    return [math.sin(2 * math.pi * 900 * i / RATE) * env(i, n, 0.001, 3) for i in range(n)]

def boom():
    n = int(RATE * 0.9); out = []; lp = 0.0
    for i in range(n):
        t = i / RATE
        p = i / n
        lp += (0.35 * (1 - p) + 0.03) * ((random.random() * 2 - 1) - lp)
        thump = math.sin(2 * math.pi * (70 - 40 * p) * t) * (1 - p) ** 4
        out.append((lp * 1.6 + thump) * env(i, n, 0.002, 2.2))
    return out

write("land.wav", land())
write("boom.wav", boom())
write("fall.wav", fall())
write("heart.wav", tone_seq([440, 330], 0.14))
write("click.wav", click())
write("gameover.wav", tone_seq([523, 440, 349, 262], 0.18))
write("perfect.wav", tone_seq([660, 880], 0.08, "sine"))
