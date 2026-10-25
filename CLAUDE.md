# 퍼스트30 (First30) — Claude Code 인수인계

현대자동차그룹 차량용 앱 개발 공모전(Pleos Connect) 출품작. 차로변경 사고 직후 30초 동안
안전 안내 → 충돌 전 차량 기록 봉인 → 예상 과실 범위 → 현장 말하기 → 다음 단계를 음성으로 안내한다.

## 지금 상태 (2026-10-25 기준)

- `engine/` 판단 엔진(JavaScript, 의존성 없음). `npm test`(= `node --test test/engine.test.js`) 98개 통과.
  곡선 진입·곡선 끝·짧은 굽은 길·차로 어긋남·같은 쪽 신호를 켠 채 굽은 길·차선 안 흔들림·회피 조향·GPS 방향 잡음·방향 지연·2Hz 프레임·
  핸들 부호 반대·결측·깜빡임 신호·느린 고속도로 진로변경·트리거 종류(음성·버튼·급감속)·늦게 온 에어백 트리거·운전자 확인 질문·
  진술 주어/부정/의도·빠른 도로 안전 안내·봉인 원본값 같은 경계 상황 테스트 포함.
  여섯 차례 독립 검토를 거쳤다(잘못된 숫자보다 판단 보류가 낫다는 원칙). 남은 한계는 `docs/DESIGN.md` 7장.
  6차 수정분(기록+답 일치 규칙, 회피 조향, 시작점 무릎, 신호 여유, 안전 안내 모드, 에어백 시각, 봉인)을 겨냥한 7차 검토는
  사용량 한도로 중간에 멈췄다 — 합성 시험(정직한 답 약 1만 4천 건, 무작위 도로의 실제 진로변경 6천 건)은 통과했지만, Kotlin 이식 전에
  독립 검토를 한 번 더 돌려 보길 권한다.
- `scenarios/` 10Hz 차량 신호 합성기와 사고 시나리오 12종 (테스트·시연 공용). `synthFrames({ fromS: -20, ... })` 로 20초 기록도 만든다.
- `demo/` 시연 화면. `node demo/build.js` → `demo/index.html`(브라우저로 바로 여는 단독 파일), `demo/artifact.html`(게시용 조각).
- `submission/` 공식 접수양식을 채운 기획서 docx와 채우는 스크립트(docx는 개인정보가 있어 .gitignore 로 뺐다).
- `docs/DESIGN.md` 설계 문서, `docs/CONTEST.md` 공식 요강 정리.
- **안드로이드 앱은 아직 없다.** 다음 작업이 Kotlin 앱이다.

## 판단 규칙 요약 (바꾸려면 DESIGN.md 3장과 테스트를 함께 고칠 것)

- 숫자는 `ctx.trigger` 가 'airbag' 또는 'impact'(충격 감지)일 때만 낸다. 'voice'·'button'·'decel'·그 밖·미지정은 충돌 시각을 모르는 것으로 보고
  진로변경을 측정하지 않는다(기록 봉인·안전 안내·증거 안내만).
- **숫자는 차량 기록과 확인 질문의 답이 같은 이야기를 할 때만 나온다.** 질문은 하나: `result.confirm.question`
  ("그때 오른쪽 차로로 진로를 바꾸던 중이었나요?" — 기록에 움직임이 없으면 "그때 차로를 바꾸던 중이었나요?"), 답은 `ctx.laneChangeConfirmed`.
  - "내 차 진로변경(B)": 기록에 진로변경 같은 움직임(`laneChange.status` 'detected' 또는 충돌 직전에 시작한 'starting', 방향 있음) + "예"(`true`).
    굽은 길·차로 어긋남과 구분이 안 되기 때문이다. 진술 문장(`self_lane_change` Noul)으로는 B를 열지 않는다(부정·질문·남의 말 오독).
  - "상대 진로변경(A)": 기록상 차로 유지('none') + "아니요"(`false`). 곡선이 바뀌는 곳의 느린 진로변경이 차로 유지처럼 보일 수 있어서다.
    충돌 직전 3초 안에 내 방향지시등이 켜져 있었거나(`signal.recentOn`), 방향지시등 기록이 없거나, 진술에서 자기 진로변경을 말했으면(`self_lane_change` ≥ 0.6) 답과 관계없이 보류.
  - 답이 없으면 `result.confirm = { question, direction, hint }` 과 함께 보류하고 안내 카드가 묻는다. 'starting' 이면 `hint`("피하려고 핸들을 꺾은 거라면 '아니요'")를 함께 보여 주고 읽는다.
    'starting' 에서 "예"면 B로 계산하되 신호 가산은 늘 범위에만. 그 무렵 브레이크를 밟았으면(`laneChange.reason === 'evasive'`) 묻지 않고 보류.
- 'none' 은 진행 방향이 차로를 지킨 것처럼 보여도 핸들이 도로를 따라가는 각도에서 크게·오래 벗어나 있으면(곡선 끝의 같은 쪽 진로변경 등)
  `uncertain/steer_activity` 로 낮춘다. 곡선 맞춤이 충돌 2초 전 무렵까지 이어지지 않으면 곡선 뒤 곧은 구간에서 기준을 다시 잡고,
  없으면 `uncertain/road_change`. 진행 방향이 핸들보다 1.3초 넘게 늦으면(`quality.headingLagSec`) 그 사이 핸들이 움직였을 때 `uncertain/heading_lag`
  (잰 지연은 신호 선행 거리 여유에도 더한다). 'starting' 은 핸들 기록이 있어야 하고, 반대쪽 진로변경을 마무리하는 움직임으로 보이면 `uncertain/opposite_pair`.
- 방향지시등: 시작 무렵(시작 시점 오차 안)에 켰거나, 기록이 없거나(`unavailable`), 켠 시점이 기록 밖이면(`indeterminate`) +10은 범위에만.
- 에어백 트리거가 늦게 오면 트리거까지 이어진 에어백 켜짐 구간의 시작을 충돌 순간으로 다시 맞춘다(앞쪽으로만, 15초 안, `impact.anchoredMs`).
  그 구간이 기록 첫머리부터 켜져 있었으면 `impact.known = false`(`reason: 'airbag_before_buffer'`).
- 안내 모드 `flow.mode`: 'urban'(내비 일반도로 + 최근 최고 시속 65km 이하) | 'highway'(내비 고속도로, 또는 도로 모름 + 시속 80km 이상)
  | 'caution'(그 밖: 빠른 일반도로, 도로·속도 모름, 음성·버튼 신고인데 기록 내내 멈춰 있음 — 사진보다 대피 먼저) | 'emergency'.
  빠른 도로(highway·caution)에서는 확인 질문을 소리 내어 묻지 않는다(화면에만).

## 다음 작업: Pleos Connect 앱 (APK)

1. Android Studio 새 프로젝트 `android/` (Kotlin, minSdk 28, Gradle 8+, JDK 17). Pleos Connect 전용 AVD(Pleos_Connect_v2.1, Android 14)에서 구동.
   - 개발 환경 설정: https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator
   - Vehicle SDK 연동(Maven 저장소·의존성): https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/project-setup
   - 권한 선언: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission (쓰는 API의 최소 권한만)
2. 엔진 이식: `engine/engine.js` 의 `prepare → analyzeWindow → renderFacts → buildJevRequest → computeFault → companion → sealEvidence`
   (+ `local-judge.js`, `jev-client.js` 의 응답 검증)를 Kotlin `core` 패키지로 옮긴다.
   **JS 테스트 98개의 입력·기대값을 그대로 JUnit 테스트로 옮겨 같은 결과가 나오는지 먼저 맞춘다.**
   (`scenarios/scenarios.js` 의 합성 공식을 그대로 쓰면 프레임이 같아진다.) 설계와 문턱값은 `docs/DESIGN.md` 3장.
3. 신호 수집기(10Hz 링버퍼, **최근 20초** — 고속에서 느린 진로변경까지 가리려면 14초 이상이 필요하다. 엔진은 받은 버퍼 전체를 쓴다).
   엔진 입력 `ctx`: `trigger`('airbag'|'impact'|'decel'|'voice'|'button'), `roadType`('urban'|'highway'), `navCautions`, `driverStatement`,
   `laneChangeConfirmed`(확인 질문 답 true/false, 묻기 전엔 넣지 않음), `occupants`(운전석 말고 착석 좌석 수), `injuryReported`, `now`(봉인 시각).
   **핸들 각도는 필수**다 — 없거나 부호가 반대면 엔진이 진로변경 움직임을 확정하지 않는다.
   방향지시등은 문자열 'LEFT'/'RIGHT'/'NONE'(또는 'OFF')/'HAZARD' 로 바꿔 넣는다(숫자 enum 그대로 넣으면 "기록 없음" 처리).
   Vehicle SDK typed API 이름(문서 sitemap 에서 확인):
   - 방향지시등 `registerTurnSignalState` / `getCurrentTurnSignalState`
   - 핸들 각도 `registerSteeringWheelAngle`
   - 브레이크 `registerBrakePedalStatus`
   - 에어백 전개 `registerSeatAirbagDeployed` / `hasSeatAirbagBeenDeployed` → 충돌 트리거 'airbag'
   - 좌석 착석 `registerSeatOccupancy` (동승자 분기)
   - 사용 흐름: check…Capabilities → getCurrent… → register…(listener, area) → unregister…(같은 listener·area) → `Vehicle.release()`
   - **Vehicle SDK 목록에 차량 속도 API가 없다.** 속도는 AAOS `CarPropertyManager` `PERF_VEHICLE_SPEED`(권한 `android.car.permission.CAR_SPEED`)로 받고,
     안 되면 FusedLocation 위치 변화로 계산한다. 10/28 온라인 설명회에서 확인할 것.
   - FusedLocation: `registerFusedLocationCallback` (lat/lon/heading/altitude/confidence/timestamp — speed 필드 없음).
     **갱신 주기와 지도 맞춤 여부를 AVD에서 확인할 것** — 1Hz 수준일 수 있고, 방향이 도로에 맞춰진(map-matched) 값이면 진로변경이 보이지 않는다.
     링버퍼는 10Hz로 돌리고 방향은 마지막 값을 유지한다.
   - 방향지시등 값이 레버 상태인지 램프 깜빡임인지 확인(엔진은 1초 이하 꺼짐을 잇는다). 핸들 각도 부호를 오른쪽 + 로 맞출 것.
   - NaviHelper: 내비 상태, 주의 지점 타입(LANE_CHANGE_CAMERA_ALERT, BUS_LANE_*, CUT_IN_VIOLATION_ENFORCEMENT 등) → `ctx.navCautions`, 고속도로 여부 → `ctx.roadType`
   - SpeechToText: "사고 났어" 트리거 + 진술 받아쓰기 → `ctx.driverStatement`, 확인 질문의 예/아니요 → `ctx.laneChangeConfirmed`
   - TextToSpeech: 카드별 `tts` 문장 읽기
4. 충돌 트리거: 에어백 전개('airbag') / **충격 감지('impact')** / 음성 "사고 났어"('voice') / 화면 버튼('button').
   **경미한 접촉은 에어백이 터지지 않으므로 충격 감지가 관건이다.** 머리 장치 가속도 센서(Android `SensorManager` `TYPE_ACCELEROMETER`)로
   접촉 충격(짧은 큰 가속도 변화)을 감지할 수 있는지 AVD·실차·설명회에서 먼저 확인할 것. 감지하면 그 시각을 t=0 으로 프레임을 맞춰
   `trigger: 'impact'` 로 넘긴다. 급감속만으로는 충돌 시각이 아니므로 'decel' 은 숫자를 내지 않는다(사고 확인 질문의 계기로만 쓴다).
5. 화면: `demo/src.html` 의 차량 화면(5단계 카드 + 30초 링 + 자막 + 확인 질문 예/아니요 버튼)을 Compose로 옮긴다.
   `flow.cards[i].confirm` 이 있으면 예/아니요를 받아 `ctx.laneChangeConfirmed` 를 넣고 `run` 을 다시 부른다.
   주행 중엔 음성 위주, 상세는 정차 후. 빠른 도로 모드(highway·caution)에서는 질문을 소리 내어 묻지 않고 화면에만 둔다(카드 tts 가 그렇게 돼 있다).
6. Jev 연동: 앱에 API 키를 넣지 말 것. 중계 서버(예: Cloudflare Worker)에 `TYPESAFE_API_KEY` 를 두고 앱은 중계 서버를 부른다.
   요청/응답 형식은 `engine/jev-client.js` 주석 참고(질문 6개: chart, evidence_sufficient, self_lane_change, other_signal_fail, forbidden_zone, bus_lane).
   3초 안에 답이 없으면 로컬 규칙 판정기(`engine/local-judge.js`)로 내려가고 화면에 표시한다.
   판정 모델 실패 시 숫자를 아예 내지 않으려면 `run(..., { onJudgeFailure: 'abstain' })` (제품 결정 필요).
   `model` 값: 공개 자료상 `jev-latest` 는 롤링 별칭이다. 재현성을 위해 공식 문서에서 고정 버전 이름을 확인해 고정할 것.
   Kotlin 커뮤니티 SDK: https://github.com/ufec/typesafe-sdk-kotlin (비공식, JitPack)
7. 봉인: `sealEvidence` 가 돌려주는 `canonical`(직렬화 본문)과 `hash` 를 함께 앱 내부 저장소에 보관한다(엔진은 저장하지 않는다).
   위치 좌표는 봉인에 넣지 않는다. 형식 버전 4 — 기록이 없던 값은 null 로 남긴다(꺼짐·안 밟음으로 채우지 않는다).
   `ctx.now`(봉인 시각)는 사고 한 건에 한 번만 정해 같은 기록이면 다시 계산해도 지문이 같게 한다.

## 제출 규칙 (공식 요강에서 확인)

- GitHub 저장소에 소스 + APK. APK는 `apk/` 폴더, 파일명 `팀명_앱이름_release.apk`(개인은 성명 자리), **실행 가능한 release 버전**.
  용량 초과 시 GitHub Releases 에 올리고 그 주소를 제출.
- README.md 필수 항목: 앱 이름과 한 줄 소개 / 실행 방법 / 테스트 환경과 필요한 설정 / APK 위치 또는 Release 링크.
- 비공개 저장소면 공지된 심사용 GitHub 계정을 Collaborator로 초대(쓰기 권한도 주어짐에 주의).
- **공유 링크·첨부자료·저장소에 개인정보, 계정정보, 개발자 실명이 드러나지 않게.** 커밋 author 이름도 확인할 것.
- 1차 합격(20팀) 시 12/3~4 PT에서 에뮬레이터 또는 기기로 실제 동작 시연 필수.

## 원칙 (바꾸지 말 것)

- 측정·계산은 코드, 해석만 판정 모델. 원시 숫자 배열을 모델에 넣지 않는다(사실 문장으로).
- 확률이 낮거나 근거가 부족하면 숫자 대신 "판단 보류" + 증거 체크리스트.
- 과실 범위는 참고치라는 고지를 항상 함께 보여준다.
- 다친 척·과장 신고를 돕는 문구는 절대 넣지 않는다(테스트로 막고 있음).
- 차량 기록이 사고 유형을 뒷받침하고(`recordConflict`), 확인 질문의 답이 그 기록과 맞을 때만 숫자를 낸다(`driverCheck` — 움직임+"예" → B, 차로 유지+"아니요" → A).
- 진술만 있는 가감 요소는 "가능성"(범위)까지만.
- 도표 수치는 손해보험협회 정보포털 원문으로 확인한 것만 숫자로 쓴다. 252-2 수정요소는 아직 미확인(verified:false).
