# 나그네 · 방문 업무용 차량 비서

하루 방문 일정만 넣어두면 차가 경로를 깔고, 고객에게 도착 시간을 알리고, 퇴근할 때 운행기록부까지 써주는 Pleos Connect 앱입니다.
이름은 고객 집에 들렀다가 다음 길로 떠나며 하루를 길 위에서 보내는 방문 기사를 나그네에 빗댄 것입니다.

- 시연 영상(1분 50초): (링크)
- APK: [`apk/나그네_release.apk`](apk/나그네_release.apk)

현대자동차그룹 차량용 앱 개발 공모전 1차 제출용 저장소입니다.

## 무엇을 하나요

| 기능 | 하는 일 | 쓰는 차량 신호 |
|---|---|---|
| 동선 자동 세팅 | "출발" 한 번에 방문지를 내비에 넣음. 경유지가 최대 2곳이라 3곳씩 끊어 넣고, 묶음을 마치면 다음 3곳을 이어 붙임 | NaviHelper `requestRouteWithWaypoints` |
| 도착 예정 알림 | 남은 시간이 10분 아래면 고객 알림(1차는 발송 기록까지). 주행 중 "늦어요" 버튼으로 다시 안내 | NaviHelper 남은 시간, 차량 위치 |
| 방문 자동 체크인 | 방문지에 들어오면 도착, P + 운전석 문 열림이면 작업 시작, P가 아닌 채 방문지를 벗어나면 작업 완료 | Vehicle `Gear`, `Door`, 내비 도착 이벤트, 위치 |
| 운행기록부 | 출발·도착·운행 마감 때 계기판 거리를 남겨 국세청 「업무용승용차 운행기록부」 항목으로 하루 한 줄 작성, 업무사용비율 계산 | Vehicle `Odometer` |
| 주행 중 한눈 화면 | 주행 중엔 다음 고객·남은 시간·알림 여부만, 조작은 "늦어요" 하나 | `CarUxRestrictionsManager` |

그 밖에 일정 편집(추가·순서 변경·삭제, 주소로 위치 찾기), 사용자·차량 정보 입력, 내비와 1:1로 나눈 세로 창 지원이 있습니다.

## 실행 방법

1. Android Studio에서 Pleos Connect Emulator 2.1.0을 설치합니다.
   SDK Manager → SDK Update Sites에 `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`(Windows/Linux)을 추가하고, API 34의 Pleos Connect System Image를 설치합니다.
   AVD는 Automotive, RAM 4GB, **Cold boot**로 만듭니다(Quick boot면 차량 서비스가 안 붙습니다).
2. 에뮬레이터를 켜고 APK를 설치합니다. 차량 권한은 설치만 하면 허용되고, 위치 권한은 처음 실행할 때 묻습니다.
   ```bash
   adb install -r apk/나그네_release.apk
   ```
3. 앱 목록에서 **나그네**를 실행하고 위치 권한을 허용합니다. 허용하면 앱이 한 번 닫혔다가 바로 다시 열립니다(아래 "알려 둘 점").

### 버튼만으로 하루 둘러보기 (스크립트 없이)

에뮬레이터는 차가 움직이지 않으므로, 정차 화면의 버튼으로 하루를 끝까지 넘겨 볼 수 있습니다.

1. **출발 · 경로 넣기** → 내비에 경유지 2곳 + 목적지가 들어갑니다(내비가 앞으로 나오면 앱 목록에서 나그네로 돌아옵니다).
2. 오른쪽 카드에서 **도착 처리 → 작업 시작 → 작업 완료**를 누르면 다음 고객으로 넘어갑니다. 3번째 방문을 마치면 다음 3곳이 내비에 들어갑니다.
3. 7곳을 마치면 **운행 마감 · 기록부에 남기기** → 운행기록부 화면이 열립니다(지난주 기록은 시연용 예시).
4. **새 하루 시작**으로 처음부터 다시 볼 수 있습니다. 위쪽 **일정** 버튼으로 방문을 더하거나 순서를 바꿀 수 있습니다.

주행 중 화면은 기어를 D로 바꾸면 나타납니다. `adb forward tcp:8088 tcp:8088` 후 브라우저에서 `http://127.0.0.1:8088`(Pleos Connect Virtual Console)을 열고 Gear Selection에서 D를 고릅니다.

### 에뮬레이터에서 차를 움직여 보기

에뮬레이터의 GPS 신호는 내비 앱 검증을 통과하지 못해 **내비 위치가 움직이지 않습니다**. 나그네는 도착을 내비 이벤트에만 기대지 않고 차량 위치로 직접 판단하므로, 아래 스크립트로 위치·기어·문·계기판을 바꿔 하루 흐름을 재현할 수 있습니다(Python 3, 표준 라이브러리만 사용).

```bash
adb forward tcp:8088 tcp:8088          # Pleos Connect Virtual Console
python tools/demo_drive.py reset        # 시작 위치(판교), 기어 P
python tools/demo_drive.py visit 37.39250 127.11050   # 1번 방문: 주행 → P → 문 열고 닫기 → 출발
```

- `goto <위도> <경도>`, `park`, `door open|close`, `leave <m>`도 따로 쓸 수 있습니다.
- 가상 콘솔은 브라우저가 보내는 `/browser/session` 신호가 있어야 활성화되는데, 스크립트가 이 신호를 직접 보냅니다.
- 앱 동작을 adb로 걸 수도 있습니다: `adb shell am start -n app.nageune/.MainActivity --es action start|arrive|work|finish|skip|close|log|day|reset`

시연 영상은 `tools/demo_video.py`(자동 녹화)와 `tools/make_video.py`(자막·배속 편집, ffmpeg·Pillow 필요)로 만들었습니다.

## 빌드

```bash
./gradlew testDebugUnitTest   # 단위 테스트 37개
./gradlew assembleDebug
./gradlew assembleRelease     # 서명하려면 keystore.properties 필요(저장소에 없음)
```

Pleos SDK(Vehicle 2.3.2, NaviHelper 3.0.5)는 Pleos Playground Maven 저장소에서 받습니다(`settings.gradle.kts`). SDK 파일 자체는 이 저장소에 넣지 않았습니다.

## 테스트 환경

| 항목 | 버전 |
|---|---|
| OS | Windows 11 |
| Android Studio | 빌드 AI-253 |
| Emulator 이미지 | Pleos Connect v2.1.0 (Android 14, API 34, x86_64) |
| JDK | 17 (Android Studio 내장 JBR) |
| Gradle / AGP / Kotlin | 8.14.3 / 8.13.0 / 2.1.0 |
| compileSdk / targetSdk / minSdk | 36 / 34 / 28 |

## 코드 구조

```
app/src/main/java/app/nageune/
  trip/      방문 흐름 판단(TripEngine), 운행기록 계산(DriveLog) — 차량·SDK와 떨어진 순수 Kotlin, 단위 테스트 대상
  car/       Vehicle SDK(기어·문·계기판), NaviHelper, 위치, 주행 제한, 주소 검색 연결
  data/      오늘 일정·운행기록 저장(JSON)
  ui/        Compose 화면(정차·주행·운행기록부·일정 편집·내 정보)
  DayViewModel.kt, MainActivity.kt, TripService.kt(위치 포그라운드 서비스)
app/src/test/  TripEngineTest, DriveLogTest
tools/       시연용 스크립트
```

## 알려 둘 점

- 고객 이름·주소는 모두 시연용 가명입니다. 지난주 운행기록(11/2~11/6)은 시연 모드에서만 보이는 예시입니다.
- 고객 알림은 1차 제출본에서 발송 기록만 남깁니다. 실제 문자 발송은 서버가 필요해 2차에서 붙입니다.
- 주소 검색은 OpenStreetMap Nominatim을 씁니다(버튼을 누를 때만, 주소만 보냄). 정식 서비스에서는 국내 지도 API로 바꿀 예정입니다.
- Pleos 홈 화면은 권한 창이 한 번 떴던 앱 화면(작업)을 주행 중에 계속 "주행 중에는 사용할 수 없습니다"로 가립니다(에뮬레이터 2.1에서 확인). 그래서 위치 권한을 받은 직후 앱을 새 작업으로 다시 엽니다. `adb install -g`로 설치하면 권한 창 자체가 뜨지 않습니다.
- 글꼴 Asta Sans는 42dot의 SIL Open Font License 1.1 글꼴입니다(`app/src/main/assets/licenses/AstaSans-OFL.txt`).
