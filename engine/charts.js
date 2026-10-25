// 퍼스트30 — 과실비율 인정기준 도표 데이터 (차로변경 사고 MVP 범위)
//
// 원칙
// - 기본과실·수정요소 숫자는 손해보험협회 과실비율 정보포털 원문에서 확인한 것만 수치로 반영한다.
// - 원문 대조가 끝나지 않은 도표는 verified:false 로 두고, 수정요소를 숫자로 계산하지 않는다.
// - 과실 표기는 언제나 "내 차 과실 %" 기준이다. A/B 는 도표의 차량 기호다.
'use strict';
(function (F) {
  const CHARTS = {
    '252': {
      id: '252',
      title: '후행 직진(A) 대 선행 진로변경(B)',
      summary: '같은 방향으로 가던 중, 앞서 진로를 바꾸는 B와 뒤에서 직진하던 A가 충돌한 사고',
      base: { A: 30, B: 70 },
      source: {
        name: '손해보험협회 자동차사고 과실비율 정보포털 · 도표 252',
        url: 'https://accident.knia.or.kr/myaccident-content?chartNo=252',
        checked: '2026-10-23',
        verified: true,
      },
      // party: 가산 대상 차량. delta: 가산 %p. group: 같은 그룹은 중복 적용하지 않음(현저한 과실·중과실).
      modifiers: [
        { id: 'A_notable', party: 'A', delta: 10, label: 'A 현저한 과실', group: 'A_negligence' },
        { id: 'A_gross', party: 'A', delta: 20, label: 'A 중과실', group: 'A_negligence' },
        { id: 'B_signal', party: 'B', delta: 10, label: 'B 진로변경 신호 불이행·지연',
          basis: '도로교통법 제38조 — 진로변경 지점 30m 전(고속도로 100m 전)부터 신호' },
        { id: 'B_buslane', party: 'B', delta: 10, label: 'B 버스·다인승 전용차로 위반' },
        { id: 'B_forbidden', party: 'B', delta: 20, label: 'B 진로변경 금지장소(백색 실선 등)' },
        { id: 'B_notable', party: 'B', delta: 10, label: 'B 현저한 과실', group: 'B_negligence' },
        { id: 'B_gross', party: 'B', delta: 20, label: 'B 중과실', group: 'B_negligence' },
      ],
    },
    '252-2': {
      id: '252-2',
      title: '좌우 차로에서 같은 차로로 동시 진로변경',
      summary: '왼쪽·오른쪽 차로의 두 차가 가운데 차로로 동시에 진로를 바꾸다 충돌한 사고',
      base: { A: 50, B: 50 },
      source: {
        name: '손해보험협회 과실분쟁 다발 사고 안내(굿모닝경제 보도) · 도표 252-2',
        url: 'https://www.goodkyung.com/news/articleView.html?idxno=239410',
        checked: '2026-10-23',
        verified: false,
        note: '기본과실 5:5만 확인했다. 수정요소 수치는 정보포털 원문 대조 후 반영한다.',
      },
      modifiers: [],
      pendingHints: [
        '방향지시등 불이행·지연',
        '정체 차로에서 대기하다 진로를 바꾼 경우(협회 안내상 10% 가산 가능)',
      ],
    },
  };

  // Jev Choice 선택지 → (도표, 내 차 역할)
  const CHART_OPTIONS = {
    c252_me_B: {
      chart: '252', me: 'B', other: 'A',
      label: '내 차가 앞서 진로변경 (도표 252, 내 차 B)',
      criteria: '내 차가 앞서 진로(차로)를 바꾸던 중, 같은 방향 뒤쪽에서 직진하던 상대 차와 충돌했다',
    },
    c252_me_A: {
      chart: '252', me: 'A', other: 'B',
      label: '상대 차가 앞서 진로변경 (도표 252, 내 차 A)',
      criteria: '내 차는 차로를 유지하며 직진했고, 상대 차가 앞쪽에서 내 차로로 진로를 바꾸다 충돌했다',
    },
    c252_2: {
      chart: '252-2', me: 'A', other: 'B',
      label: '양쪽 동시 진로변경 (도표 252-2)',
      criteria: '내 차와 상대 차가 서로 반대쪽 차로에서 같은 차로로 거의 동시에 진로를 바꾸다 충돌했다',
    },
    none: {
      chart: null,
      label: '해당 없음 · 근거 부족',
      criteria: '위의 차로변경 사고 유형에 해당하지 않거나(추돌·교차로·주차장 등), 주어진 사실만으로는 판단할 근거가 부족하다',
    },
  };

  F.CHARTS = CHARTS;
  F.CHART_OPTIONS = CHART_OPTIONS;
})(typeof module !== 'undefined' && typeof require === 'function' ? require('./ns.js') : (globalThis.First30 = globalThis.First30 || {}));
