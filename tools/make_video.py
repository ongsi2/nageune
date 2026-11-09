"""녹화 원본(raw.webm + marks.json)을 콘티대로 자르고 배속·자막·카드를 입혀 최종 영상을 만든다.

사용:  python make_video.py <녹화폴더> [출력.mp4]
필요:  ffmpeg, numpy, Pillow. 글꼴은 앱에 넣은 Asta Sans(SIL OFL 1.1)를 쓴다.
소리:  make_audio.py로 배경음·효과음을 직접 합성해 입힌다. 효과음 시각(sfx)은 녹화 원본 기준 초.
표지:  assets/mark_tile.png(삿갓 쓴 핀). 숏폼 폴더에서 `npx remotion still MarkPng`로 만든다.
"""
import json
import os
import subprocess
import sys
import textwrap

import make_audio

from PIL import Image, ImageDraw, ImageFont

sys.stdout.reconfigure(encoding="utf-8")
HERE = os.path.dirname(os.path.abspath(__file__))
FONT = os.path.join(HERE, "..", "app", "src", "main", "res", "font", "asta_sans.ttf")
MARK = os.path.join(HERE, "assets", "mark_tile.png")
W, H, FPS = 1920, 1080, 30

ACCENT = (37, 99, 235)
TEXT = (17, 20, 24)
MUTED = (106, 113, 123)
BG = (242, 243, 245)

# 콘티(영상/콘티.md)와 같은 순서. caps는 장면 길이를 똑같이 나눠 차례로 보여준다.
SPEC = [
    {"card": "title", "dur": 5, "sfx_at": [(0.6, "title")]},
    {"from": "s1_start_screen", "to": "s2_plan", "speed": 1,
     "caps": ["정수기 점검 기사의 하루. 오늘 방문 7곳이 차 화면에 떠 있어요"]},
    {"from": "s2_plan", "to": "s3_depart", "fit": 10,
     "caps": ["급한 방문 추가나 순서 변경은 차를 세운 채 바로 고쳐요"]},
    {"from": "s3_depart", "to": "s4_driving", "fit": 10, "sfx": [(28.2, "tap"), (28.35, "ping")],
     "caps": ["출발을 누르면 3곳을 한 번에 내비에 넣어요",
              "내비 경유지는 2곳까지라 3곳씩 끊어서 이어 붙여요"]},
    {"from": "s4_driving", "to": "s5_late", "fit": 10,
     "caps": ["주행 중에는 한눈에 볼 것만 남겨요. 다음 고객, 남은 시간, 고객 알림",
              "도착 10분 전이면 고객에게 도착 예정 알림이 나가요"]},
    {"from": "s5_late", "to": "s6_arrive", "speed": 1, "sfx": [(57.4, "tap"), (57.5, "late")],
     "caps": ["앞 일정이 길어지면 버튼 하나로 늦는다고 알려요. 주행 중 조작은 이것 하나뿐이에요"]},
    {"from": "s6_arrive", "to": "s7_checkin", "fit": 8, "sfx": [(64.8, "arrive")],
     "caps": ["방문지 근처에 오면 도착으로 기록해요"]},
    {"from": "s7_checkin", "to": "s8_leave", "speed": 1, "sfx": [(72.2, "start")],
     "caps": ["P로 세우고 운전석 문을 열면 작업 시작",
              "지하주차장처럼 위치가 흔들려도 문 열림으로 잡아요"]},
    {"from": "s8_leave", "to": "s9_rest", "fit": 6, "sfx": [(79.2, "done")],
     "caps": ["다시 출발해 방문지를 벗어나면 작업 완료, 다음 고객으로 넘어가요"]},
    {"from": "s9_rest", "to": "s9_rest_end", "fit": 12,
     "caps": ["나머지 여섯 곳은 빠르게 돌려요"]},
    {"from": "s10_close", "to": "s11_log", "fit": 8, "sfx": [(151.2, "tap"), (151.3, "close")],
     "caps": ["퇴근해서 차를 세우고 운행 마감을 누르면"]},
    {"from": "s11_log", "to": "s12_split", "speed": 1,
     "caps": ["국세청 운행기록부 항목이 하루 한 줄로 채워지고, 방문 구간별 계기판 기록이 증빙으로 남아요",
              "기록 사이에 늘어난 거리는 개인 주행으로 보고 업무사용비율을 바로 계산해요"]},
    {"from": "s12_split", "to": "end", "speed": 1,
     "caps": ["내비 옆 절반 화면에서도 그대로 써요"]},
    {"card": "end", "dur": 9},
]


def font(size, weight="Regular"):
    f = ImageFont.truetype(FONT, size)
    f.set_variation_by_name(weight)
    return f


def caption_png(text, path, speed_note=None):
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    f = font(40, "SemiBold")
    lines = textwrap.wrap(text, width=44) or [""]
    line_h = 56
    bar_h = 48 + line_h * len(lines)
    top = H - bar_h
    d.rectangle([0, top, W, H], fill=(10, 12, 16, 228))
    y = top + 24
    for ln in lines:
        d.text((64, y), ln, font=f, fill=(255, 255, 255, 255))
        y += line_h
    tag = "에뮬레이터 시연" + (f" · {speed_note}" if speed_note else "")
    ft = font(24, "Medium")
    tw = d.textlength(tag, font=ft)
    d.text((W - 64 - tw, H - 24 - 30), tag, font=ft, fill=(190, 196, 204, 255))
    img.save(path)


def wrap_px(d, text, f, max_w):
    """글자 수가 아니라 실제 폭으로 줄바꿈(영문이 섞여도 한쪽이 일찍 꺾이지 않게)."""
    lines, cur = [], ""
    for word in text.split(" "):
        cand = f"{cur} {word}".strip()
        if cur and d.textlength(cand, font=f) > max_w:
            lines.append(cur)
            cur = word
        else:
            cur = cand
    return lines + ([cur] if cur else [])


def lockup(img, x, y, tile, size):
    """표지 타일(tile px) + '나그네' 글자. 타일 왼쪽 끝이 x, 글자 위치 y."""
    d = ImageDraw.Draw(img)
    f = font(size, "Bold")
    tx = x + tile + int(tile * 0.26)
    l, t, r, b = d.textbbox((tx, y), "나그네", font=f)
    mark = Image.open(MARK).convert("RGBA")  # 600px 캔버스 가운데 400px 타일
    k = tile / 400
    mark = mark.resize((round(600 * k), round(600 * k)), Image.LANCZOS)
    cy = (t + b) / 2
    img.paste(mark, (round(x - 100 * k), round(cy - 300 * k)), mark)
    d.text((tx, y), "나그네", font=f, fill=ACCENT)


def card_png(kind, path):
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    if kind == "title":
        lockup(img, 164, 330, 172, 150)
        d.text((168, 540), "방문 업무용 차량 비서", font=font(56, "SemiBold"), fill=TEXT)
        sub = "하루 방문 일정만 넣어두면 차가 경로를 깔고, 고객에게 도착 시간을 알리고,\n퇴근할 때 운행기록부까지 써줘요."
        d.multiline_text((168, 650), sub, font=font(38), fill=MUTED, spacing=18)
        d.text((168, 900), "Pleos Connect 차량용 앱 · 현대자동차그룹 차량용 앱 개발 공모전", font=font(28, "Medium"), fill=MUTED)
    else:
        lockup(img, 166, 170, 112, 96)
        lines = [
            ("시연 환경", "Pleos Connect Emulator 2.1 · Vehicle SDK 2.3.2 · NaviHelper SDK 3.0.5"),
            ("쓴 차량 신호", "기어(Gear), 운전석 문(Door), 계기판 누적 거리(Odometer), 주행 제한 상태(UX Restrictions)"),
            ("밝혀 둘 점", "에뮬레이터에서는 내비 위치가 움직이지 않아, 차 위치·기어·문·계기판을 가상 콘솔과 스크립트로 바꿔 시연했어요."),
            ("", "고객 알림은 1차 제출본에서 발송 기록만 남겨요. 지난주 운행기록은 예시 데이터예요."),
            ("", "고객 이름·주소는 모두 가명이에요. 배경음과 효과음은 직접 만들었어요."),
        ]
        y = 360
        for k, v in lines:
            if k:
                d.text((168, y), k, font=font(30, "SemiBold"), fill=TEXT)
            wrapped = wrap_px(d, v, font(30), W - 420 - 120)
            for i, ln in enumerate(wrapped):
                d.text((420, y + i * 46), ln, font=font(30), fill=MUTED)
            y += 46 * max(1, len(wrapped)) + 26
    img.save(path)


def run(cmd):
    r = subprocess.run(cmd, capture_output=True)
    if r.returncode != 0:
        print(r.stderr.decode("utf-8", "replace")[-2000:])
        raise SystemExit(1)


def main():
    rec = sys.argv[1]
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(rec, "nageune_demo.mp4")
    raw = os.path.join(rec, "raw.webm")
    marks = json.load(open(os.path.join(rec, "marks.json"), encoding="utf-8"))
    work = os.path.join(rec, "work")
    os.makedirs(work, exist_ok=True)
    parts = []
    total = 0.0
    timing = []  # 장면마다 (녹화 시작, 배속) — 효과음 시각을 영상 시각으로 옮길 때 쓴다
    for i, s in enumerate(SPEC):
        seg = os.path.join(work, f"seg{i:02d}.mp4")
        if "card" in s:
            png = os.path.join(work, f"card{i:02d}.png")
            card_png(s["card"], png)
            dur = s["dur"]
            run(["ffmpeg", "-y", "-loop", "1", "-t", str(dur), "-i", png,
                 "-vf", f"fps={FPS},format=yuv420p,fade=t=in:st=0:d=0.4,fade=t=out:st={dur - 0.4}:d=0.4",
                 "-c:v", "libx264", "-crf", "18", "-preset", "medium", seg])
            timing.append((None, 1.0))
        else:
            a, b = marks[s["from"]], marks[s["to"]]
            raw_len = b - a
            speed = s.get("speed") or max(1.0, raw_len / s["fit"])
            dur = raw_len / speed
            note = (f"{speed:.1f}배속" if speed < 3 else f"{round(speed)}배속") if speed >= 1.3 else None
            caps = s.get("caps", [])
            inputs = ["-ss", f"{a:.2f}", "-t", f"{raw_len:.2f}", "-i", raw]
            chain = f"[0:v]setpts=(PTS-STARTPTS)/{speed:.4f},scale={W}:{H}:flags=lanczos,fps={FPS}[v0]"
            last = "v0"
            for j, c in enumerate(caps):
                png = os.path.join(work, f"cap{i:02d}_{j}.png")
                caption_png(c, png, note)
                inputs += ["-i", png]
                t0, t1 = dur * j / len(caps), dur * (j + 1) / len(caps)
                chain += f";[{last}][{j + 1}:v]overlay=0:0:enable='between(t,{t0:.2f},{t1:.2f})'[v{j + 1}]"
                last = f"v{j + 1}"
            chain += f";[{last}]format=yuv420p[vout]"
            run(["ffmpeg", "-y", *inputs, "-filter_complex", chain, "-map", "[vout]", "-an",
                 "-c:v", "libx264", "-crf", "18", "-preset", "medium", "-r", str(FPS), seg])
            timing.append((a, speed))
        parts.append(seg)
        total += dur
        print(f"{i:02d} {s.get('card') or s['from']:<18} {dur:6.1f}s  (누적 {total:6.1f}s)", flush=True)
    lst = os.path.join(work, "list.txt")
    with open(lst, "w", encoding="utf-8") as f:
        for p in parts:
            f.write(f"file '{os.path.abspath(p).replace(os.sep, '/')}'\n")
    silent = os.path.join(work, "video.mp4")
    run(["ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", lst, "-c", "copy", silent])

    # 장면별 실제 길이(프레임 반올림 포함)로 시작 시각을 다시 잡고 효과음을 놓는다.
    starts, t = [], 0.0
    for p in parts:
        starts.append(t)
        t += probe_dur(p)
    events, fast, outro = [], None, None
    for s, st, (a, speed), p in zip(SPEC, starts, timing, parts):
        for at, kind in s.get("sfx_at", []):
            events.append((st + at, kind))
        for raw_t, kind in s.get("sfx", []):
            events.append((st + (raw_t - a) / speed, kind))
        if speed > 3:
            fast = (st, st + probe_dur(p))
        if s.get("card") == "end":
            outro = st
    for at, kind in events:
        print(f"  소리 {at:6.2f}s {kind}")
    wav = os.path.join(work, "audio.wav")
    make_audio.build(wav, t, events, main=(starts[1], outro), fast=fast, outro=outro)
    run(["ffmpeg", "-y", "-i", silent, "-i", wav, "-map", "0:v", "-map", "1:a", "-c:v", "copy",
         "-c:a", "aac", "-b:a", "192k", "-shortest", "-movflags", "+faststart", out])
    print("완성:", out, f"{t:.1f}초")


def probe_dur(path):
    r = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path],
                       capture_output=True, text=True)
    return float(r.stdout.strip())


if __name__ == "__main__":
    main()
