"""시연 영상 원본 녹화. 콘티(영상/콘티.md) 순서대로 에뮬레이터를 움직이며 화면을 녹화하고 장면 시각을 남긴다.

사용:  python demo_video.py <출력폴더>
결과:  <출력폴더>/raw.webm (2560x1440), <출력폴더>/marks.json (장면 이름 → 녹화 시작 후 초)
그다음: python make_video.py <출력폴더>  (자막·배속·카드 입혀 최종 mp4)

준비: 에뮬레이터 켜짐, 나그네 설치, 창을 나누지 않은 상태. 콘솔(8088) 포워딩은 스크립트가 한다.
"""
import json
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import demo_drive as dd  # noqa: E402  위치·기어·문·계기판 조작

ADB = dd.ADB
ENV = dict(os.environ, MSYS_NO_PATHCONV="1")
APP = "app.nageune/.MainActivity"

# 시연용 방문지(DemoDay와 같은 좌표)
STOPS = [
    (37.39250, 127.11050), (37.36750, 127.10850), (37.37900, 127.11700), (37.38400, 127.12900),
    (37.39600, 127.12700), (37.40950, 127.13000), (37.40100, 127.11000),
]
HOME = dd.START


def adb(*a, check=False):
    r = subprocess.run([ADB, "-e", *a], capture_output=True, env=ENV)
    if check and r.returncode != 0:
        raise RuntimeError(r.stderr.decode("utf-8", "replace"))
    return r.stdout.decode("utf-8", "replace")


def app(action=None):
    args = ["shell", "am", "start", "-n", APP]
    if action:
        args += ["--es", "action", action]
    adb(*args)


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("shell", "cat", "/sdcard/ui.xml")
    for m in re.finditer(r"<node [^>]*>", xml):
        n = m.group(0)
        t = re.search(r' text="([^"]*)"', n).group(1)
        b = list(map(int, re.findall(r"\d+", re.search(r' bounds="([^"]*)"', n).group(1))))
        yield t, b


def tap(text, index=0, tries=3):
    for _ in range(tries):
        hits = [b for t, b in nodes() if text in t]
        if len(hits) > index:
            b = hits[index]
            adb("shell", "input", "tap", str((b[0] + b[2]) // 2), str((b[1] + b[3]) // 2))
            return True
        time.sleep(0.8)
    print("! 못 찾음:", text)
    return False


class Recorder:
    def __init__(self, out):
        self.out = out
        self.path = os.path.abspath(os.path.join(out, "raw.webm"))
        self.marks = {}
        self.t0 = None

    def start(self):
        if os.path.exists(self.path):
            os.remove(self.path)
        adb("emu", "screenrecord", "start", "--time-limit", "900", self.path.replace("/", "\\"))
        self.t0 = time.time()

    def mark(self, name):
        self.marks[name] = round(time.time() - self.t0, 2)
        print(f"[{self.marks[name]:7.2f}s] {name}", flush=True)

    def stop(self):
        adb("emu", "screenrecord", "stop")
        time.sleep(2)
        with open(os.path.join(self.out, "marks.json"), "w", encoding="utf-8") as f:
            json.dump(self.marks, f, ensure_ascii=False, indent=2)


def prepare():
    adb("forward", "tcp:8088", "tcp:8088")
    dd.start_heartbeat()
    dd.geo(*HOME)
    dd.park()
    dd.door(False)
    app("reset")
    time.sleep(3)
    app("day")
    adb("shell", "settings", "put", "system", "show_touches", "1")
    time.sleep(2)


def finish():
    adb("shell", "settings", "put", "system", "show_touches", "0")


def drive_to(p, step=180.0, dt=0.9, stop_short=30.0):
    dd.drive(p, step=step, dt=dt, kmh=40, stop_short_m=stop_short)


def quick_visit(p):
    dd.drive(p, step=700.0, dt=0.5, kmh=40)
    time.sleep(1.8)  # 위치 갱신이 늦게 와도 방문지 안에서 P·문 열림을 보도록 잠깐 머문다
    dd.park()
    time.sleep(0.8)
    dd.door(True)
    time.sleep(0.6)
    dd.door(False)
    time.sleep(0.8)
    dd.leave(350.0, dt=0.5, kmh=40)
    app()  # 내비가 화면을 덮었으면 나그네를 앞으로


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "rec"
    os.makedirs(out, exist_ok=True)
    prepare()
    r = Recorder(out)
    r.start()
    try:
        time.sleep(1.0)
        r.mark("s1_start_screen")
        time.sleep(5)

        r.mark("s2_plan")
        tap("일정 편집")
        time.sleep(2.2)
        tap("고치기", 1)
        time.sleep(3.0)
        tap("닫기")
        time.sleep(1.0)
        tap("← 취소")
        time.sleep(1.5)

        r.mark("s3_depart")
        tap("출발 · 경로 넣기")
        time.sleep(13)  # 내비가 경로를 계산하고 지도를 그릴 때까지

        r.mark("s4_driving")
        app()
        time.sleep(1.2)
        # 첫 방문지 600m 앞까지 주행(주행 화면, 남은 시간 줄어듦)
        a = dd.load_pos()
        b = STOPS[0]
        near = (b[0] + (a[0] - b[0]) * 0.25, b[1] + (a[1] - b[1]) * 0.25)
        drive_to(near, step=220.0, dt=1.0, stop_short=0)
        time.sleep(1.0)

        r.mark("s5_late")
        tap("늦어요")
        time.sleep(3.5)

        r.mark("s6_arrive")
        drive_to(STOPS[0], step=150.0, dt=1.0)
        time.sleep(2.0)
        dd.park()
        time.sleep(3.0)

        r.mark("s7_checkin")
        dd.door(True)
        time.sleep(1.2)
        dd.door(False)
        time.sleep(4.0)

        r.mark("s8_leave")
        dd.leave(400.0, dt=0.8, kmh=40)
        time.sleep(3.5)

        r.mark("s9_rest")
        for p in STOPS[1:]:
            quick_visit(p)
        r.mark("s9_rest_end")

        r.mark("s10_close")
        drive_to(HOME, step=600.0, dt=0.7, stop_short=0)
        time.sleep(1.5)
        dd.park()
        app("day")
        time.sleep(3.0)
        tap("운행 마감")
        time.sleep(3.0)

        r.mark("s11_log")
        time.sleep(9.0)

        r.mark("s12_split")
        adb("shell", "input", "swipe", "2547", "717", "1700", "717", "800")
        time.sleep(6.0)
        r.mark("end")
        time.sleep(1.0)
    finally:
        r.stop()
        adb("shell", "input", "swipe", "1706", "717", "2555", "717", "800")
        finish()
    print("녹화 끝:", r.path)


if __name__ == "__main__":
    main()
