"""시연 영상 배경음·효과음 직접 합성(외부 음원 없음). make_video.py가 불러 쓴다.

배경음: 90BPM, G장조 5음계(솔라시레미). 줄 뜯는 소리(Karplus-Strong) 펼침화음 + 패드 + 낮은 베이스,
        본편에는 가벼운 셰이커·킥, 빨리 감는 구간에는 16분 셰이커를 더한다. 끝 카드에서는 타악기를 빼고 잦아든다.
효과음: 누르기, 고객 알림(띠링), 늦어요 알림, 도착, 작업 시작, 작업 완료, 운행 마감, 제목.
"""
import wave

import numpy as np

SR = 48000
BPM = 90
BEAT = 60 / BPM
BAR = BEAT * 4
rng = np.random.default_rng(11)


def hz(m):
    return 440.0 * 2 ** ((m - 69) / 12)


def lowpass(x, cutoff):
    X = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1 / SR)
    return np.fft.irfft(X / np.sqrt(1 + (f / cutoff) ** 4), len(x))


def highpass(x, cutoff):
    return x - lowpass(x, cutoff)


def pluck(m, dur=2.2, bright=3500, decay=0.996):
    """줄 뜯는 소리. 정수 주기로 만든 뒤 늘려서 음높이를 맞춘다."""
    f = hz(m)
    p = max(2, int(SR / f - 0.5))
    base = SR / (p + 0.5)  # 두 표본 평균 필터가 반 표본 늦추므로 실제 주기는 p + 0.5
    rate = f / base
    n = int(dur * SR * rate) + 2 * p
    y = np.zeros(n)
    burst = lowpass(rng.uniform(-1, 1, p * 4), bright)[:p]
    y[:p] = burst - burst.mean()  # 직류 성분이 남으면 줄 소리 대신 '퍽' 하는 낮은 덩어리가 된다
    k = 1
    while k * p < n:
        prev = y[(k - 1) * p:k * p]
        shift = np.concatenate(([y[(k - 1) * p - 1] if k > 1 else 0.0], prev[:-1]))
        seg = decay * 0.5 * (prev + shift)
        y[k * p:(k + 1) * p] = seg[:len(y[k * p:(k + 1) * p])]
        k += 1
    # 정수 주기로 생긴 음높이(base)를 원하는 f로 맞춤
    out_n = int(dur * SR)
    src = np.arange(out_n) * rate
    src = src[src < n - 1]
    out = np.interp(src, np.arange(n), y)
    out *= np.exp(-np.arange(len(out)) / SR * 1.2)
    out[-int(0.05 * SR):] *= np.linspace(1, 0, int(0.05 * SR))
    return out


def env(n, a, r):
    e = np.ones(n)
    a, r = int(a * SR), int(r * SR)
    if a:
        e[:a] = np.linspace(0, 1, a)
    if r:
        e[-r:] *= np.linspace(1, 0, r)
    return e


def pad(chord, dur):
    n = int(dur * SR)
    t = np.arange(n) / SR
    s = np.zeros(n)
    for m in chord:
        for det in (-0.08, 0.08):
            f = hz(m) * 2 ** (det / 12)
            s += np.sin(2 * np.pi * f * t) + 0.25 * np.sin(2 * np.pi * 2 * f * t)
    s *= env(n, 0.6, 0.6) * (0.85 + 0.15 * np.sin(2 * np.pi * 0.2 * t))
    return lowpass(s, 1800)


def bass(m, dur):
    n = int(dur * SR)
    t = np.arange(n) / SR
    s = np.sin(2 * np.pi * hz(m) * t) + 0.2 * np.sin(2 * np.pi * 2 * hz(m) * t)
    return s * env(n, 0.03, 0.4) * np.exp(-t * 0.5)


def shaker(len_s=0.07):
    n = int(len_s * SR)
    x = highpass(rng.standard_normal(n), 5000)
    t = np.arange(n) / SR
    return x * (1 - np.exp(-t * 400)) * np.exp(-t * 55)


def kick():
    n = int(0.3 * SR)
    t = np.arange(n) / SR
    f = 50 + 70 * np.exp(-t * 30)
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 11)


def bell(m, dur=1.2, decay=4.5):
    n = int(dur * SR)
    t = np.arange(n) / SR
    s = sum(a * np.sin(2 * np.pi * hz(m) * r * t) * np.exp(-t * decay * (1 + r / 3))
            for r, a in ((1, 1.0), (2.0, 0.35), (3.0, 0.12), (4.2, 0.06)))
    s *= 1 - np.exp(-t * 900)
    return s


def mallet(m, dur=0.7):
    n = int(dur * SR)
    t = np.arange(n) / SR
    s = np.sin(2 * np.pi * hz(m) * t) * np.exp(-t * 7) + 0.3 * np.sin(2 * np.pi * hz(m) * 4 * t) * np.exp(-t * 30)
    return s * (1 - np.exp(-t * 1500))


def tap():
    n = int(0.045 * SR)
    t = np.arange(n) / SR
    return (np.sin(2 * np.pi * 2200 * t) * 0.5 + highpass(rng.standard_normal(n), 2500) * 0.35) * np.exp(-t * 140)


def seq(parts):
    """[(지연초, 소리, 음량)]를 한 소리로."""
    n = max(int(d * SR) + len(s) for d, s, _ in parts)
    out = np.zeros(n)
    for d, s, g in parts:
        i = int(d * SR)
        out[i:i + len(s)] += s * g
    return out


SFX = {
    "tap": lambda: tap(),
    "ping": lambda: seq([(0, bell(86, 1.0), 0.8), (0.13, bell(91, 1.4), 0.9)]),          # 고객 알림 띠링
    "late": lambda: seq([(0, bell(91, 1.0), 0.8), (0.15, bell(88, 1.4), 0.85)]),         # 늦어요 알림(내려감)
    "arrive": lambda: bell(83, 1.6, 3.5),                                                # 도착
    "start": lambda: seq([(0, mallet(79), 0.9), (0.12, mallet(86), 1.0)]),               # 작업 시작
    "done": lambda: seq([(0, mallet(79), 0.8), (0.1, mallet(83), 0.85), (0.2, mallet(86), 1.0)]),  # 작업 완료
    "close": lambda: seq([(0, mallet(79), 0.8), (0.11, mallet(83), 0.85), (0.22, mallet(86), 0.9),
                          (0.33, bell(91, 2.0, 2.5), 0.9)]),                              # 운행 마감
    "title": lambda: seq([(0, bell(91, 2.6, 2.0), 0.7), (0.0, bell(79, 2.6, 2.0), 0.5)]),
}
SFX_GAIN = {"tap": 0.35, "ping": 0.32, "late": 0.32, "arrive": 0.3, "start": 0.34, "done": 0.34, "close": 0.36, "title": 0.32}

# 2마디씩: 화음(패드), 베이스, 펼침음(낮은 것부터)
CHORDS = [
    ([55, 62, 69, 71], 43, [62, 67, 69, 71, 74, 76]),   # G(add9)
    ([52, 59, 62, 67], 40, [59, 64, 67, 71, 74, 76]),   # Em7
    ([48, 55, 62, 64], 36, [60, 64, 67, 69, 74, 76]),   # C(add9)
    ([50, 57, 62, 67], 38, [57, 62, 67, 69, 74, 79]),   # Dsus4
]
PATTERNS = [[0, 2, 3, 4, 5, 4, 3, 2], [0, 3, 2, 4, 3, 5, 4, 1]]


def build(path, total, events, main=(5.0, None), fast=None, outro=None):
    """events: [(초, 종류)]. main: 타악기 구간(시작, 끝). fast: 16분 셰이커 구간. outro: 끝 카드 시작."""
    n = int((total + 0.5) * SR)
    L, R = np.zeros(n), np.zeros(n)

    def add(sig, t0, g=1.0, pan=0.0):
        i = int(t0 * SR)
        if i >= n or i < 0:
            return
        s = sig[:n - i] * g
        L[i:i + len(s)] += s * np.sqrt(0.5 * (1 - pan))
        R[i:i + len(s)] += s * np.sqrt(0.5 * (1 + pan))

    m0, m1 = main[0], main[1] if main[1] is not None else (outro or total)
    bars = int(total / BAR) + 1
    for b in range(bars):
        t0 = b * BAR
        if t0 >= total:
            break
        ch, bs, arp = CHORDS[(b // 2) % 4]
        in_outro = outro is not None and t0 >= outro
        if b % 2 == 0:
            add(pad(ch, BAR * 2 + 0.6), t0, 0.022, -0.1)
            add(pad(ch, BAR * 2 + 0.6), t0 + 0.015, 0.022, 0.1)
            add(bass(bs, BAR * 2), t0, 0.16)
        pat = PATTERNS[(b // 4) % 2]
        for k, idx in enumerate(pat):
            tt = t0 + k * BEAT / 2
            if tt >= total:
                break
            sparse = tt < m0 or in_outro
            if sparse and k % 2:
                continue
            g = 0.16 if k == 0 else 0.11
            add(pluck(arp[idx]), tt, g * (0.8 if sparse else 1.0), pan=-0.35 if k % 2 else 0.3)
        # 타악기
        if m0 <= t0 < m1:
            for k in range(4):
                tt = t0 + k * BEAT
                if k in (0, 2):
                    add(kick(), tt, 0.20)
                add(shaker(), tt + BEAT / 2, 0.045, 0.4)
                if fast and fast[0] <= tt < fast[1]:
                    add(shaker(0.05), tt + BEAT / 4, 0.03, -0.4)
                    add(shaker(0.05), tt + 3 * BEAT / 4, 0.03, -0.4)
    # 마지막 음: 으뜸화음 한 번 길게
    if outro is not None:
        last = (int((total - 4.5) / BAR)) * BAR
        for k, m in enumerate([55, 62, 67, 71, 74]):
            add(pluck(m, 4.0, decay=0.998), last + k * 0.06, 0.12, pan=-0.3 + k * 0.15)

    music = np.stack([L, R], 1)
    rms = np.sqrt(np.mean(music[int(m0 * SR):int(m1 * SR)] ** 2))
    music *= 10 ** (-25 / 20) / max(rms, 1e-9)   # 배경음은 자막 읽기를 방해하지 않게 낮게

    fx = np.zeros_like(music)
    for t, kind in events:
        s = SFX[kind]()
        s = s / max(np.max(np.abs(s)), 1e-9) * SFX_GAIN[kind]
        i = int(t * SR)
        if i >= n:
            continue
        s = s[:n - i]
        fx[i:i + len(s), 0] += s
        fx[i:i + len(s), 1] += s

    mix = music + fx
    nn = len(mix)
    fade = np.ones(nn)
    fo = int((total - 2.5) * SR)
    fade[fo:] = np.linspace(1, 0, nn - fo) ** 1.6
    fade[:int(0.3 * SR)] = np.linspace(0, 1, int(0.3 * SR))
    mix *= fade[:, None]
    mix = np.tanh(mix * 1.2) / np.tanh(1.2)
    mix *= min(1.0, 0.89 / np.max(np.abs(mix)))
    mix = mix[:int(total * SR)]
    with wave.open(path, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes((mix * 32767).astype("<i2").tobytes())
    return path


if __name__ == "__main__":
    # 소리만 들어보기: python make_audio.py out.wav
    import sys
    build(sys.argv[1], 30.0, [(0.5, "title"), (6, "tap"), (6.15, "ping"), (10, "late"), (14, "arrive"),
                               (18, "start"), (22, "done"), (26, "tap"), (26.1, "close")], fast=(12, 16), outro=24)
    print("저장:", sys.argv[1])
