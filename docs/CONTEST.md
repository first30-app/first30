# 공모전 요강 정리 — 현대자동차그룹 차량용 앱 개발 공모전 (2026)

공식 페이지와 공식 접수양식(`contest-application-consent.docx`)에서 직접 확인한 내용만 적었다.
바뀔 수 있으니 제출 전에 공식 페이지를 다시 확인할 것.

## 일정

| 항목 | 일시 |
|---|---|
| 접수 | 2026.10.21 ~ 2026.11.15 23:59 |
| 온라인 설명회 | 2026.10.28(수) 오후 |
| 1차 서류심사 결과 | 2026.11.23 발표, 20팀 선정 |
| 2차 PT 심사 | 2026.12.3 ~ 12.4, UX스튜디오 서울(강남대로 350) — 에뮬레이터 또는 실기기로 **실제 동작 시연 필수** |
| 최종 선정 발표 | 2026.12.18, 10팀 |
| 전시·시상 | 2027년 2월 현대자동차그룹 행사 — 최종 구동용 APK 제출, 전시 영상 협조, 현장에서 최종 순위 발표 |

## 접수 방법

- 이메일: `appdevchallenge@hyundai-partners.com`
- 제목: `[차량용 앱 개발 공모전] 팀명(개인은 성명)_대표자명`
- 첨부: 접수양식(기획서 겸 개인정보 수집·이용 동의서, 동의 체크·서명·날짜 포함)
- 개인 또는 최대 5인 팀 (팀은 대표자가 팀원 전원을 CC)
- 수정 제출: 제목 앞에 `[수정제출]`, **모든 파일을 다시** 첨부(서명 동의서 포함)

## 선택 제출물 (단계별 가산점)

1. 시연 영상
2. GitHub 저장소: 소스 + APK
   - APK는 `apk/` 폴더, 파일명 `팀명_앱이름_release.apk`, 실행 가능한 release 빌드
   - README.md: 앱 이름과 한 줄 소개 / 실행 방법 / 테스트 환경·필요한 설정 / APK 위치
   - 비공개 저장소 가능 — 공지된 심사용 계정을 Collaborator로 초대하고, 마감 전에 그 계정으로 접근되는지 확인
     (개인 계정 비공개 저장소는 Collaborator에게 쓰기 권한도 주어짐)
   - APK만 따로 내지 않고 저장소에 소스와 함께 올린다

## 숏폼 챌린지 (선택, 본상과 별도)

- 아이디어나 시연 화면을 30초~1분 세로 영상(Instagram 릴스 / YouTube Shorts)으로 개인·팀 계정에 게시
- 필수 해시태그 `#HMG차량용앱개발공모전` 1개를 포함해 해시태그 총 2~3개를 본문·설명란에
- 링크는 접수양식 8번에 기재. 우수 1팀 50만원 + 2027.02 행사 초청
- 블라인드 규칙상 개인 실명이 드러나는 계정은 피하는 편이 안전

## 심사 기준

차량 특화성 · 창의성 · 기술 활용성 · 사업성(타깃 고객, 예상 MAU, Retention, 확대 가능성)

## 블라인드 규칙

- 기획서 02 항목(아이디어 본문)에 개인정보·실명·소속을 쓰지 않는다.
- 공유 링크, 첨부자료, 저장소에도 개인정보·계정정보·개발자 실명이 드러나지 않게 한다.

## 권리

- 출품작의 지식재산권은 참가자에게 남는다.
- 주최측은 통상실시권을 가질 수 있고, 수상작에 대해 4개월 우선협상권을 둘 수 있다.

## Pleos SDK 확인 사항 (2026-10-29, 공식 사이트·document.pleos.ai 기준)

설명회(10/28) 다시보기·질의응답은 아직 공개된 곳을 찾지 못했다(공지: "다시보기를 제공할 예정"). 아래는 개발 문서에서 확인한 것.

- **Maven**: `https://nexus-playground.pleos.ai/repository/maven-releases/` (인증 없음). `ai.pleos.playground:Vehicle:2.3.2`,
  `NaviHelper:3.0.5`, `TextToSpeech:3.3.1`, `SpeechToText:3.3.1`, `FusedLocation:1.1.3`. minSdk 28. 샘플 앱은 compileSdk 35.
  권한은 매니페스트 선언만(`pleos.car.permission.CAR_*`, 실행 중 승인 창 없음). 배포 시 Pleos App Market 승인 범위 안에서만.
- **SDK 상태**: `create-my-project` 문서는 Vehicle State·NaviHelper만 "사용 가능", FusedLocation·TTS·STT·Vehicle Control은 "준비 중".
  릴리즈 노트(10/19)와 Maven에는 다섯 SDK가 모두 있다. TTS·STT 서버 모드는 My Project Client ID/Secret 등록 필요(ON_DEVICE는 불필요).
- **차량 속도**: Vehicle 2.3.2에서 `VehicleSpeedListener` 삭제. 공식 FAQ는 AAOS `CarPropertyManager` `PERF_VEHICLE_SPEED`(m/s)를 쓰라고 한다.
  권한은 Android 문서상 `android.car.permission.CAR_SPEED`, Pleos 샘플 매니페스트에는 `pleos.car.permission.CAR_SPEED` — AVD에서 확인.
  FusedLocation `LocationData` 에는 속도 필드가 없다.
- **핸들 각도**: `registerSteeringWheelAngle` 은 "front-wheel steering angle in degrees. Leftward … negative. … not the rotation angle of
  the steering wheel". 엔진 문턱값은 핸들 회전각 기준이라 앱에서 조향비(기본 15)를 곱한다(`PleosUnits.steeringWheelDeg`).
  Virtual Console 핸들 슬라이더는 −540~540° 라 콘솔 값과 API 값의 관계를 AVD에서 확인할 것.
- **방향지시등**: `TurnSignalValue` OFF/LEFT/RIGHT(비상등 없음). 레버인지 램프 깜빡임인지는 문서에 없다.
- **에어백**: `hasSeatAirbagBeenDeployed` / `registerSeatAirbagDeployed`(`CAR_SAFETY`, normal). 충돌·충격 전용 API는 없다.
- **가속도 센서(충격 감지)**: 문서에 사용 가능하다는 기록이 없고, "차량의 물리적 제어나 센서에 직접 접근해서는 안 됩니다",
  "제한될 수 있는 기능: … 일부 센서(자이로, 근접 등)" 문구가 있다. → 'impact' 트리거는 실차·AVD 확인 전까지 구현하지 않는다.
- **에뮬레이터**: AVD "Pleos Connect v2.1.0"(API 34), Cold boot 필수(quick boot면 VHAL 안 됨), RAM 4GB.
  Virtual Console(`adb forward tcp:8088 tcp:8088` → `http://127.0.0.1:8088/`)로 기어·브레이크·핸들·방향지시등·도어 등을 바꾼다.
  **차량 속도 카드와 에어백 카드는 없다**(크루즈 목표 속도만). 주행 규제: `adb shell cmd hmg_car_service --service drivingstate driving_regulation on|off`.
- **제출 추가 확인**: 마감 11/15(일) 23:59, 1차 발표 11/23, 2차 PT 12/3~4(오프라인, 실제 동작 시연 필수). 심사용 GitHub 계정 이름은 공지에 없다.

## 아직 확인할 것 (문의: appdevchallenge@hyundai-partners.com)

1. 에뮬레이터에서 `PERF_VEHICLE_SPEED` 값을 바꿀 수 있는가(Extended controls Car data 등), 필요한 권한은 무엇인가?
2. **충돌 감지에 쓸 수 있는 신호(가장 중요)**: 에어백 전개 외에 가속도(G센서, Android `SensorManager`)·ADAS 경고·충돌 이벤트를 앱이 받을 수 있는가?
   에뮬레이터에서 에어백 전개를 흉내 낼 방법이 있는가?
   퍼스트30은 충돌 순간을 차가 직접 감지했을 때만 과실 숫자를 내므로, 경미한 접촉의 충격 감지 가능 여부가 실사용 범위를 정한다.
   차선 인식(차선 이탈 경고) 정보를 받을 수 있으면 "굽은 길 vs 진로변경" 확인 질문 없이도 판단할 수 있다.
   FusedLocation 의 진행 방향이 도로에 맞춰진(map-matched) 값인지, 갱신 주기(1Hz?)와 핸들 대비 지연이 얼마인지도 확인.
3. 앱에서 외부 서버(판정 모델 중계 서버) 호출이 허용되는가, 네트워크 권한 제한이 있는가?
4. 블랙박스(빌트인 캠) 영상 구간에 앱이 접근하거나 저장을 요청할 수 있는가?
5. 기존 현대차그룹 사고 지원 서비스와 겹치는 부분이 있으면 연동을 제안해도 되는가?
6. 접수양식의 입력 상자(콘텐츠 컨트롤) 태그 이름이 보이는 제목과 다르다
   (`vehicle_scenario` 상자에 "3. 핵심 기능", `key_features` 상자에 "4. 차량 시나리오", `reference_files` 상자에 "6. 기대 효과"가 들어가 있음).
   보이는 제목대로 채웠는데 그대로 내도 되는지 확인.
