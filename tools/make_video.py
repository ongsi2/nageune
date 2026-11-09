"""녹화 원본(raw.webm + marks.json)을 콘티대로 자르고 배속·자막·카드를 입혀 최종 영상을 만든다.

사용:  python make_video.py <녹화폴더> [출력.mp4]
필요:  ffmpeg, Pillow. 글꼴은 앱에 넣은 Asta Sans(SIL OFL 1.1)를 쓴다.
"""
import json
import os
import subprocess
import sys
import textwrap

from PIL import Image, ImageDraw, ImageFont

sys.stdout.reconfigure(encoding="utf-8")
HERE = os.path.dirname(os.path.abspath(__file__))
FONT = os.path.join(HERE, "..", "app", "src", "main", "res", "font", "asta_sans.ttf")
W, H, FPS = 1920, 1080, 30

ACCENT = (37, 99, 235)
TEXT = (17, 20, 24)
MUTED = (106, 113, 123)
BG = (242, 243, 245)

# 콘티(영상/콘티.md)와 같은 순서. caps는 장면 길이를 똑같이 나눠 차례로 보여준다.
SPEC = [
    {"card": "title", "dur": 5},
    {"from": "s1_start_screen", "to": "s2_plan", "speed": 1,
     "caps": ["정수기 점검 기사의 하루. 오늘 방문 7곳이 차 화면에 떠 있어요"]},
    {"from": "s2_plan", "to": "s3_depart", "fit": 10,
     "caps": ["급한 방문 추가나 순서 변경은 차를 세운 채 바로 고쳐요"]},
    {"from": "s3_depart", "to": "s4_driving", "fit": 10,
     "caps": ["출발을 누르면 3곳을 한 번에 내비에 넣어요",
              "내비 경유지는 2곳까지라 3곳씩 끊어서 이어 붙여요"]},
    {"from": "s4_driving", "to": "s5_late", "fit": 10,
     "caps": ["주행 중에는 한눈에 볼 것만 남겨요. 다음 고객, 남은 시간, 고객 알림",
              "도착 10분 전이면 고객에게 도착 예정 알림이 나가요"]},
    {"from": "s5_late", "to": "s6_arrive", "speed": 1,
     "caps": ["앞 일정이 길어지면 버튼 하나로 늦는다고 알려요. 주행 중 조작은 이것 하나뿐이에요"]},
    {"from": "s6_arrive", "to": "s7_checkin", "fit": 8,
     "caps": ["방문지 근처에 오면 도착으로 기록해요"]},
    {"from": "s7_checkin", "to": "s8_leave", "speed": 1,
     "caps": ["P로 세우고 운전석 문을 열면 작업 시작",
              "지하주차장처럼 위치가 흔들려도 문 열림으로 잡아요"]},
    {"from": "s8_leave", "to": "s9_rest", "fit": 6,
     "caps": ["다시 출발해 방문지를 벗어나면 작업 완료, 다음 고객으로 넘어가요"]},
    {"from": "s9_rest", "to": "s9_rest_end", "fit": 12,
     "caps": ["나머지 여섯 곳은 빠르게 돌려요"]},
    {"from": "s10_close", "to": "s11_log", "fit": 8,
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


def card_png(kind, path):
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    if kind == "title":
        d.text((160, 330), "나그네", font=font(150, "Bold"), fill=ACCENT)
        d.text((168, 540), "방문 업무용 차량 비서", font=font(56, "SemiBold"), fill=TEXT)
        sub = "하루 방문 일정만 넣어두면 차가 경로를 깔고, 고객에게 도착 시간을 알리고,\n퇴근할 때 운행기록부까지 써줘요."
        d.multiline_text((168, 650), sub, font=font(38), fill=MUTED, spacing=18)
        d.text((168, 900), "Pleos Connect 차량용 앱 · 현대자동차그룹 차량용 앱 개발 공모전", font=font(28, "Medium"), fill=MUTED)
    else:
        d.text((160, 170), "나그네", font=font(96, "Bold"), fill=ACCENT)
        lines = [
            ("시연 환경", "Pleos Connect Emulator 2.1 · Vehicle SDK 2.3.2 · NaviHelper SDK 3.0.5"),
            ("쓴 차량 신호", "기어(Gear), 운전석 문(Door), 계기판 누적 거리(Odometer), 주행 제한 상태(UX Restrictions)"),
            ("밝혀 둘 점", "에뮬레이터에서는 내비 위치가 움직이지 않아, 차 위치·기어·문·계기판을 가상 콘솔과 스크립트로 바꿔 시연했어요."),
            ("", "고객 알림은 1차 제출본에서 발송 기록만 남겨요. 지난주 운행기록은 예시 데이터예요."),
            ("", "고객 이름·주소는 모두 가명이에요."),
        ]
        y = 360
        for k, v in lines:
            if k:
                d.text((168, y), k, font=font(30, "SemiBold"), fill=TEXT)
            for i, ln in enumerate(textwrap.wrap(v, width=66)):
                d.text((420, y + i * 46), ln, font=font(30), fill=MUTED)
            y += 46 * max(1, len(textwrap.wrap(v, width=66))) + 26
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
    for i, s in enumerate(SPEC):
        seg = os.path.join(work, f"seg{i:02d}.mp4")
        if "card" in s:
            png = os.path.join(work, f"card{i:02d}.png")
            card_png(s["card"], png)
            dur = s["dur"]
            run(["ffmpeg", "-y", "-loop", "1", "-t", str(dur), "-i", png,
                 "-vf", f"fps={FPS},format=yuv420p,fade=t=in:st=0:d=0.4,fade=t=out:st={dur - 0.4}:d=0.4",
                 "-c:v", "libx264", "-crf", "18", "-preset", "medium", seg])
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
        parts.append(seg)
        total += dur
        print(f"{i:02d} {s.get('card') or s['from']:<18} {dur:6.1f}s  (누적 {total:6.1f}s)", flush=True)
    lst = os.path.join(work, "list.txt")
    with open(lst, "w", encoding="utf-8") as f:
        for p in parts:
            f.write(f"file '{os.path.abspath(p).replace(os.sep, '/')}'\n")
    run(["ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", lst, "-c", "copy", "-movflags", "+faststart", out])
    print("완성:", out, f"{total:.1f}초")


if __name__ == "__main__":
    main()
