# 퍼스트30 (First30)

**사고 직후 30초, 무엇을 해야 할지 차가 먼저 알려주는 Pleos Connect 동반자 앱.**

차로변경 접촉사고가 나면 충돌 전 차량 기록(방향지시등·핸들·브레이크·속도·진행 방향, 최근 20초)을 봉인하고,
안전 행동 → 기록 보존 → 예상 과실 범위(참고치) → 현장에서 할 말 → 다음 단계를 30초 안에 음성과 화면으로 안내합니다.

> 예상 과실은 손해보험협회 과실비율 인정기준의 참고치이며, 확정 과실은 보험사·분쟁심의위원회가 정합니다.
> 숫자는 차가 충돌 순간을 직접 감지했고(에어백·충격 감지) 차량 기록이 사고 유형을 뒷받침할 때만 냅니다.
> 굽은 길·차로가 어긋난 곳을 따라간 움직임은 기록상 진로변경과 똑같이 보이고, 곡선이 바뀌는 곳의 진로변경은 차로 유지처럼 보이기도 해서,
> 앱은 **"그때 (오른쪽) 차로로 진로를 바꾸던 중이었나요?"** 하나를 묻고 **기록과 답이 맞을 때만** 계산합니다 —
> 진로변경 같은 움직임 + "예" → 내 차 진로변경, 차로 유지 + "아니요" → 상대 차 진로변경. 진술 문장만으로는 계산하지 않습니다.
> 회피 조향·정체 저속·기록 결측·방향 기록 지연, 음성·버튼으로만 신고해 충돌 시각을 모를 때는 숫자 대신 "판단 보류"와
> 모아야 할 증거 목록을 보여줍니다. 운전자 진술만으로는 어떤 가감 요소도 확정하지 않습니다.
> 퍼스트30은 다친 척이나 과장 신고를 돕는 안내를 하지 않습니다.

## 웹 시연 화면

https://first30-app.github.io/first30/demo/index.html — 브라우저에서 바로 여는 시연 화면(30초 안내·차량 기록·확인 질문·판단 과정). 설치가 필요 없습니다.

## APK 위치

[`apk/팀명_퍼스트30_release.apk`](apk/) — 서명된 release 빌드 (versionName 1.0.0, minSdk 28, APK Signature Scheme v1·v2·v3).

## 실행 방법

### APK 설치와 시연

```bash
adb install apk/팀명_퍼스트30_release.apk
adb shell am start -n com.first30.app/.MainActivity      # 또는 앱 목록에서 "퍼스트30"
```

1. 시작 화면에서 사고 장면(시나리오 12종)을 고릅니다. 예: 두 번째 장면 **진로변경 같은 움직임 · 진술 없음 → 앱이 먼저 확인 질문** (아래 줄에 `02 · 충격 감지 · 일반도로`)
2. **사고 났어** 를 누르면 기록이 봉인되고 30초 안내가 시작됩니다(왼쪽 링·단계, 오른쪽 카드, 아래 자막과 음성).
3. 확인 카드의 질문 "그때 (왼쪽/오른쪽) 차로로 진로를 바꾸던 중이었나요?"에 **예 / 아니요** 로 답하면
   그 답과 차량 기록이 맞을 때 예상 과실(참고치)이 나오고, 맞지 않으면 판단 보류와 증거 목록이 나옵니다.
   왼쪽 단계 목록을 눌러 지난 카드를 다시 볼 수 있습니다.
4. "확인 질문 답을 시나리오대로 미리 넣기"를 켜면 답이 정해진 장면은 질문 없이 그 답으로 바로 계산합니다
   (두 번째 장면처럼 앱이 먼저 묻는 장면이나 음성 신고 장면은 그대로 질문하거나 보류합니다).

화면 확인용 바로 시작(`-S` 는 떠 있는 앱을 끄고 다시 여는 옵션 — 없으면 앱이 열린 방식에 따라 시나리오가 무시되거나 화면이 하나 더 쌓일 수 있습니다. 시나리오 id 는 `android/core/src/main/kotlin/com/first30/core/scenarios/Scenarios.kt`):

```bash
adb shell am start -S -n com.first30.app/.MainActivity --es scenario confirm_needed
adb shell am start -S -n com.first30.app/.MainActivity --es scenario airbag --ez preAnswer true
```

### 소스에서 빌드

필요: JDK 17, Android SDK (compileSdk 35), Node.js 18 이상(엔진·시연 화면).

```bash
cd android
./gradlew :app:assembleRelease        # → app/build/outputs/apk/release/
./gradlew :app:exportReleaseApk       # assembleRelease 후 ../apk/<팀명>_퍼스트30_release.apk 로 복사
                                      #   팀명은 android/gradle.properties 의 submission.teamName
./gradlew :core:test :app:testDebugUnitTest   # Kotlin 엔진·앱 테스트

cd ..
npm test              # JavaScript 엔진 테스트 (= node --test test/engine.test.js)
npm run build:demo    # 브라우저 시연 화면 → demo/index.html
```

release 서명은 `android/keystore.properties`(저장소에 올리지 않음)를 읽습니다.

```properties
storeFile=keystore/first30-release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

이 파일이 없으면 `assembleRelease` 는 경고와 함께 debug 키로 서명되고(제출용 아님), `exportReleaseApk` 는 제출 파일을 만들지 않고 실패합니다.

## 테스트 환경과 필요한 설정

### Pleos Connect 에뮬레이터

1. Android Studio → **SDK Manager → SDK Update Sites** 에서 사이트 추가
   - 이름: `Pleos Connect System Image`
   - URL (Mac, Apple Silicon): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
   - URL (Windows / Linux): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
2. **SDK Platforms → API 34 → Pleos Connect system Image** 설치
3. **Device Manager → Automotive → Pleos Connect v2.1.0** 로 기기 생성
   - 시스템 이미지: API 34 "Pleos Connect v2.1.0 APIs"
   - Boot option: **Cold boot**, RAM **4GB**
4. 에뮬레이터를 켠 뒤 위 `adb install` 로 설치

추가로 일반 Android 휴대전화 에뮬레이터(API 36, 가로 화면)에서도 설치·실행을 확인했습니다.

### 빌드 환경

JDK 17 · AGP 8.13 · Kotlin 2.2 · Jetpack Compose · compileSdk/targetSdk 35 · minSdk 28 · R8 축소 사용

### 현재 버전의 범위

- 지금 APK는 **합성 차량 신호 시나리오를 재생**합니다(10Hz, 충돌 전 10초 — 링버퍼는 최근 20초까지 보관). 실제 차량 신호는 쓰지 않으므로 별도 권한이 필요 없습니다.
  사고 화면의 상태 표시에 **"시연 · 합성 기록"** 이 늘 붙어, 과실 숫자가 실제 차량 기록에서 나온 것이 아님을 알립니다.
  (Pleos 에뮬레이터의 Virtual Console 에는 에어백·충격 신호가 없어, 충돌 순간을 아는 장면은 시나리오 재생으로 보여 줍니다.)
- 다음 단계: Pleos Vehicle SDK(방향지시등·핸들 각도·브레이크·에어백·착석), 차량 속도, FusedLocation 으로 실제 신호를 받아 같은 링버퍼에 넣기.
- 판정 모델(TypeSafe Jev)은 연결하지 않은 상태로, 오프라인 규칙 판정기로 판정합니다(화면에 "규칙 기반 대체 판정"으로 표시).
  연결할 때도 앱에 API 키를 넣지 않고 중계 서버를 부르며, 3초 안에 답이 없으면 오프라인 판정으로 내려갑니다.

## 구성

| 폴더 | 내용 |
|---|---|
| `android/` | Pleos Connect 앱 (Kotlin, Gradle) |
| `android/core/` | 판단 엔진 Kotlin/JVM 이식 — 측정 → 사실 문장 → 판정 → 과실 계산 → 안내 카드 → 기록 봉인(SHA-256), 합성기·시나리오. JS 엔진과 같은 결과(골든 비교·명세 테스트) |
| `android/app/` | Compose 차량 화면 — 5단계 카드·30초 링·자막·확인 질문 예/아니요, 10Hz 링버퍼, 시나리오 신호원, 음성 안내(TTS), 봉인 저장 |
| `apk/` | 제출용 서명 release APK |
| `engine/` | 판단 엔진 원본 (JavaScript, 외부 의존성 없음) |
| `scenarios/` | 10Hz 차량 신호 합성기와 사고 시나리오 12종 |
| `test/` | 엔진 테스트 |
| `demo/` | 브라우저 시연 화면 (단일 HTML) |
| `docs/` | 설계 문서(`DESIGN.md`), 공모전 요강 정리(`CONTEST.md`) |

## 설계 원칙

- 측정·계산은 코드가, 해석만 판정 모델이 합니다. 원시 숫자 배열 대신 사실 문장을 넘깁니다.
- 근거가 부족하면 숫자 대신 "판단 보류"와 증거 체크리스트 — 잘못된 숫자보다 보류가 낫습니다.
- 차량 기록과 확인 질문의 답이 같은 이야기를 할 때만 과실 숫자를 내고, 진술만 있는 가감 요소는 범위(가능성)까지만 씁니다.
- 과실 범위에는 늘 참고치 고지를 함께 보여 줍니다.
- 봉인 기록에는 위치 좌표를 넣지 않고, 기록이 없던 값은 비워 둡니다.

자세한 판단 규칙과 문턱값, 남은 한계는 [`docs/DESIGN.md`](docs/DESIGN.md) 에 있습니다.

## 데이터 출처

- 과실비율 인정기준 도표 252 (진로변경 사고): 손해보험협회 과실비율정보포털 — https://accident.knia.or.kr
- 차량 신호 API: Pleos Connect 개발 문서 — https://document.pleos.ai
