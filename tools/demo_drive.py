"""시연용 주행 스크립트.

에뮬레이터 위치(geo fix)를 A → B 직선으로 조금씩 옮기고, Pleos 가상 콘솔에 기어·속도를 넣는다.
내비 화면은 에뮬레이터 GPS 문제로 움직이지 않지만, 나그네 앱은 안드로이드 위치로 도착을 판단하므로
이 스크립트로 하루 흐름(가는 중 → 도착 → 작업 시작 → 출발)을 재현할 수 있다.

사전 준비
  adb forward tcp:8088 tcp:8088
  가상 콘솔은 브라우저가 1초마다 보내는 /browser/session 신호가 있어야 활성 상태가 된다.
  이 스크립트가 실행 중에는 같은 신호를 직접 보내므로 브라우저를 열어 두지 않아도 된다.
  주행한 만큼(직선거리 × 1.35) 계기판 누적 거리도 올린다.

사용 예
  python demo_drive.py goto 37.39250 127.11050          # 현재 위치에서 그 좌표 근처(30m 앞)까지 주행
  python demo_drive.py park                              # 기어 P, 속도 0
  python demo_drive.py door open|close                   # 운전석 문
  python demo_drive.py leave 400                         # 기어 D로 바꾸고 북쪽으로 400m 이동
  python demo_drive.py visit 37.36750 127.10850          # goto → park → 문 열고 닫기 → (작업) → leave

옵션: --step 120 (한 번에 움직일 m), --dt 1.0 (초), --kmh 40
"""
import argparse
import json
import math
import os
import subprocess
import sys
import threading
import time
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")

ADB = os.environ.get("ADB") or os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe")
CONSOLE = "http://127.0.0.1:8088"
STATE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".demo_pos.json")
START = (37.41272, 127.09468)  # 에뮬레이터 기본 위치(판교 창업로)


def heartbeat_forever():
    while True:
        try:
            req = urllib.request.Request(CONSOLE + "/browser/session?client=demo-drive", method="POST")
            urllib.request.urlopen(req, timeout=2).read()
        except Exception:
            pass
        time.sleep(0.8)


def start_heartbeat():
    threading.Thread(target=heartbeat_forever, daemon=True).start()
    # 콘솔이 활성으로 바뀔 때까지 잠깐 기다린다.
    for _ in range(20):
        try:
            with urllib.request.urlopen(CONSOLE + "/vehicle/state", timeout=2) as r:
                if json.load(r)["controls"].get("emulator_active"):
                    return
        except Exception:
            pass
        time.sleep(0.25)
    print("! 가상 콘솔이 활성으로 바뀌지 않았어요(adb forward tcp:8088 tcp:8088 확인)")


def odometer():
    try:
        with urllib.request.urlopen(CONSOLE + "/vehicle/state", timeout=2) as r:
            return float(json.load(r)["odometer"]["traveled_distance_km"])
    except Exception:
        return None


def put(path):
    try:
        req = urllib.request.Request(CONSOLE + path, method="PUT")
        with urllib.request.urlopen(req, timeout=3) as r:
            body = r.read().decode("utf-8", "replace")
            if "emulator_inactive" in body:
                print("! 가상 콘솔이 비활성 상태예요. 브라우저로 http://127.0.0.1:8088 을 열어 주세요.")
    except Exception as e:  # 콘솔이 없어도 위치 이동은 계속
        print("! 콘솔 요청 실패:", path, e)


def geo(lat, lng):
    subprocess.run([ADB, "-e", "emu", "geo", "fix", f"{lng:.6f}", f"{lat:.6f}", "50", "10"], stdout=subprocess.DEVNULL)
    save_pos(lat, lng)


def load_pos():
    try:
        with open(STATE_FILE, encoding="utf-8") as f:
            p = json.load(f)
            return p["lat"], p["lng"]
    except Exception:
        return START


def save_pos(lat, lng):
    with open(STATE_FILE, "w", encoding="utf-8") as f:
        json.dump({"lat": lat, "lng": lng}, f)


def dist_m(a, b):
    r = 6371000.0
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def drive(to, step, dt, kmh, stop_short_m=30.0):
    put("/vehicle/gear?gear=Drive")
    put(f"/vehicle/controls?speed={kmh}&accelerator=25&brake=0")
    a = load_pos()
    total = dist_m(a, to)
    if total <= stop_short_m:
        geo(*to)
        return
    odo = odometer()
    prev = a
    n = max(1, int(math.ceil((total - stop_short_m) / step)))
    for i in range(1, n + 1):
        f = min(1.0, i * step / total)
        p = (a[0] + (to[0] - a[0]) * f, a[1] + (to[1] - a[1]) * f)
        geo(*p)
        if odo is not None:
            odo += dist_m(prev, p) * 1.35 / 1000.0
            put(f"/vehicle/odometer?traveled_distance_km={odo:.2f}")
        prev = p
        left = dist_m(p, to)
        print(f"  {p[0]:.5f},{p[1]:.5f}  남은 {left:,.0f}m" + (f"  계기판 {odo:,.1f}km" if odo else ""), flush=True)
        time.sleep(dt)
        if left <= stop_short_m:
            break
    put("/vehicle/controls?speed=0&accelerator=0&brake=40")


def park():
    put("/vehicle/controls?speed=0&accelerator=0&brake=40")
    put("/vehicle/gear?gear=Park")


def door(open_):
    put(f"/vehicle/body?door_fl={'true' if open_ else 'false'}")


def leave(meters, dt, kmh):
    a = load_pos()
    to = (a[0] + meters / 111000.0, a[1])
    drive(to, step=max(50.0, meters / 4), dt=dt, kmh=kmh, stop_short_m=0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["goto", "park", "door", "leave", "visit", "reset", "where"])
    ap.add_argument("args", nargs="*")
    ap.add_argument("--step", type=float, default=120.0)
    ap.add_argument("--dt", type=float, default=1.0)
    ap.add_argument("--kmh", type=int, default=40)
    ap.add_argument("--work", type=float, default=3.0, help="visit에서 작업 시간(초)")
    o = ap.parse_args()
    if o.cmd != "where":
        start_heartbeat()

    if o.cmd == "reset":
        geo(*START)
        park()
        door(False)
        # 시연용 지난주 예시 기록(마지막 52,291.4km)과 이어지도록 계기판을 맞춘다.
        put("/vehicle/odometer?traveled_distance_km=52310")
    elif o.cmd == "where":
        print(load_pos())
    elif o.cmd == "goto":
        drive((float(o.args[0]), float(o.args[1])), o.step, o.dt, o.kmh)
    elif o.cmd == "park":
        park()
    elif o.cmd == "door":
        door(o.args[0] == "open")
    elif o.cmd == "leave":
        leave(float(o.args[0]) if o.args else 400.0, o.dt, o.kmh)
    elif o.cmd == "visit":
        drive((float(o.args[0]), float(o.args[1])), o.step, o.dt, o.kmh)
        time.sleep(1.5)
        park()
        time.sleep(1.5)
        door(True)
        time.sleep(1.0)
        door(False)
        time.sleep(o.work)
        leave(400.0, o.dt, o.kmh)


if __name__ == "__main__":
    main()
