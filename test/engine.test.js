'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const F = require('../engine/index.js');
require('../scenarios/scenarios.js');

const fixedNow = '2026-10-23T15:00:00.000Z';
const runLocal = (frames, ctx) => F.run(frames, { ...ctx, now: fixedNow }, F.localJudge);
// t=0 이 충돌 순간인 기록(충격 감지 트리거). 음성·버튼 신고는 각 테스트에서 trigger 를 바꿔 넣는다.
const urban = (driverStatement = '') => ({ trigger: 'impact', roadType: 'urban', navCautions: [], driverStatement });
const highway = (driverStatement = '') => ({ trigger: 'impact', roadType: 'highway', navCautions: [], driverStatement });
// "차로를 바꾸던 중이었나요?"에 "예"라고 답한 경우
const confirmed = (ctx) => ({ ...ctx, laneChangeConfirmed: true });
const keptLane = (ctx) => ({ ...ctx, laneChangeConfirmed: false }); // "그때 차로를 바꾸던 중이었나요?"에 "아니요"

// 곡선 도로: 반경 R(m)를 일정 속도로 달림. 핸들은 곡선만큼 일정하게 꺾여 있다.
function curveFrames(R, kph, dir = 1, fromS = -10) {
  const v = kph / 3.6;
  return F.synthFrames({ speed: kph, fromS }).map((f) => {
    const t = (f.t - fromS * 1000) / 1000;
    return { ...f, headingDeg: (72 + dir * ((v * t) / R) * (180 / Math.PI) + 360) % 360, steeringDeg: dir * 2.5 };
  });
}
// 결정적 의사 난수
function rng(seed) { let x = seed >>> 0; return () => { x = (x * 1664525 + 1013904223) >>> 0; return x / 2 ** 32; }; }

// ---------- 시나리오 ----------
for (const sc of F.SCENARIOS) {
  test(`시나리오 ${sc.id}: ${sc.title}`, async () => {
    const out = await runLocal(sc.frames, sc.ctx);
    const e = sc.expect;
    assert.equal(out.result.status, e.status, JSON.stringify(out.result));
    assert.equal(!!out.result.confirm, !!e.confirm, 'confirm');
    if (e.confirmed) {
      const yes = await runLocal(sc.frames, confirmed(sc.ctx));
      for (const k of ['status', 'option', 'likely', 'low', 'high']) assert.equal(yes.result[k], e.confirmed[k], k);
    }
    // 답이 있어야 숫자가 나오는 시나리오는, 답을 빼면 숫자 대신 확인 질문이 나와야 한다
    if (sc.ctx.laneChangeConfirmed != null && e.status === 'estimate') {
      const before = await runLocal(sc.frames, { ...sc.ctx, laneChangeConfirmed: undefined });
      assert.equal(before.result.status, 'abstain', 'before answer');
      assert.ok(before.result.confirm, 'question before answer');
    }
    if (e.option) assert.equal(out.result.option, e.option);
    if (e.likely != null) assert.equal(out.result.likely, e.likely, `likely ${JSON.stringify(out.result.modifiers)}`);
    if (e.low != null) assert.equal(out.result.low, e.low);
    if (e.high != null) assert.equal(out.result.high, e.high);
    if (e.verified != null) assert.equal(out.result.verified, e.verified);
    if (e.mode) assert.equal(out.flow.mode, e.mode);
    assert.match(out.seal.hash, /^[0-9a-f]{64}$/);
    assert.equal(out.flow.cards.length, 5);
    assert.equal(out.judgeError, null);
    assert.equal(out.result.judge.standIn, true);
  });
}

// ---------- 측정 ----------
test('차로변경 측정: 오른쪽, 측면 이동 1~2.5m, 핸들 일치', () => {
  const sc = F.SCENARIOS.find((s) => s.id === 'me_late_signal');
  const m = F.analyzeWindow(sc.frames, sc.ctx);
  assert.equal(m.laneChange.status, 'detected');
  assert.equal(m.laneChange.direction, 'RIGHT');
  assert.ok(m.laneChange.lateralM >= 1 && m.laneChange.lateralM <= 2.5, String(m.laneChange.lateralM));
  assert.equal(m.laneChange.steeringCorroborated, true);
  assert.equal(m.signal.status, 'late');
  assert.ok(m.signal.leadM < 30);
});

test('직진 차량은 차로 유지(none)로 측정하고, 사실 문장에 실제 측정값을 쓴다', () => {
  const sc = F.SCENARIOS.find((s) => s.id === 'other_cutin_no_signal');
  const m = F.analyzeWindow(sc.frames, sc.ctx);
  assert.equal(m.laneChange.status, 'none');
  assert.equal(m.laneChange.direction, null);
  assert.equal(m.brake.applied, true);
  assert.equal(m.brake.onsetSecBefore, 0.8);
  const facts = F.renderFacts(m, sc.ctx);
  assert.ok(facts.vehicle.some((s) => /차로를 유지했음 \(측면 이동 약 0m/.test(s)), facts.vehicle.join(' / '));
  assert.ok(!facts.vehicle.some((s) => /1m 미만/.test(s)));
});

test('곡선 도로(R800·60km/h, R3000·100km/h)는 차로변경으로 잡지 않는다', async () => {
  // 고속 주행은 느린 진로변경까지 가리려면 20초 기록이 필요하다 (앱 링버퍼 20초)
  for (const [R, kph, ctx, fromS] of [[800, 60, urban('오른쪽 차가 갑자기 들어왔어'), -10], [3000, 100, highway('옆 차가 끼어들었어'), -20]]) {
    const out = await runLocal(curveFrames(R, kph, 1, fromS), ctx);
    assert.equal(out.metrics.laneChange.status, 'none', `R${R}: ${JSON.stringify(out.metrics.laneChange)}`);
    assert.equal(out.metrics.road.curveCompensated, true);
    assert.equal(out.result.option, 'c252_me_A');
  }
});

test('1Hz로 갱신되는 GPS 방향 ±2° 잡음 직진은 차로변경으로 단정하지 않는다', async () => {
  const r = rng(7);
  let held = 72;
  const frames = F.synthFrames({ speed: 100 }).map((f, i) => {
    if (i % 10 === 0) held = 72 + (r() * 4 - 2);
    return { ...f, headingDeg: held };
  });
  const out = await runLocal(frames, highway('옆 차가 들어왔어'));
  assert.notEqual(out.metrics.laneChange.status, 'detected');
  assert.ok(out.result.status === 'abstain' || out.result.option === 'c252_me_A', JSON.stringify(out.result));
  assert.notEqual(out.result.option === 'c252_me_B' && out.result.status === 'estimate', true);
});

test('기록 앞부분의 방향 치우침(0.7°)이 가짜 차로변경을 만들지 않는다', () => {
  const frames = F.synthFrames({ speed: 100, fromS: -20 }).map((f) => ({ ...f, headingDeg: f.t < -18500 ? 72.7 : 72 }));
  const m = F.analyzeWindow(frames, highway());
  assert.equal(m.laneChange.status, 'none', JSON.stringify(m.laneChange));
  const near = F.synthFrames({ speed: 60 }).map((f) => ({ ...f, headingDeg: f.t < -8500 ? 72.7 : 72 }));
  assert.equal(F.analyzeWindow(near, urban()).laneChange.status, 'none');
});

test('시속 120km 완만한 진로변경(방향 변화 2° 미만)도 움직임으로 잡고, 확인 질문에 "예"면 신호 없음 +10 → 80%', async () => {
  const frames = F.synthFrames({ speed: 120, fromS: -20, laneChange: { startS: -4.5, durS: 7, dir: 'RIGHT' } });
  const out = await runLocal(frames, confirmed(highway('2차로로 들어가다 닿았어')));
  assert.equal(out.metrics.laneChange.status, 'detected', JSON.stringify(out.metrics.laneChange));
  assert.ok(out.metrics.laneChange.maxHeadingDeg < 2.5);
  assert.equal(out.result.option, 'c252_me_B');
  assert.equal(out.result.likely, 80); // 신호 없음 +10
  // 확인 답이 없으면(진술로 인정해도) 굽은 길과 구분할 수 없어 확인 질문
  const noAdmit = await runLocal(frames, highway('2차로로 들어가다 닿았어'));
  assert.equal(noAdmit.result.status, 'abstain');
  assert.equal(noAdmit.result.confirm.direction, 'RIGHT');
});

test('충돌 1.4초 전에 시작한 진로변경 + "상대가 들어왔다" 진술 → 직진(A)으로 단정하지 않고 판단 보류', async () => {
  const frames = F.synthFrames({ speed: 50, laneChange: { startS: -1.4, durS: 4, dir: 'RIGHT' } });
  const out = await runLocal(frames, urban('오른쪽 차가 깜빡이 없이 들어왔어'));
  assert.equal(out.metrics.laneChange.status, 'starting');
  assert.equal(out.result.status, 'abstain');
  assert.ok(out.result.reasons.some((r) => /회피|피하려는/.test(r)), out.result.reasons.join(' / '));
});

test('정체에서 출발해 바로 진로변경(10초 평균은 느림)도 진로변경 구간 속도로 판단한다', () => {
  const frames = F.synthFrames({ speed: (t) => (t < -3 ? 0 : 25), laneChange: { startS: -2.6, durS: 3, dir: 'LEFT' }, signal: { fromS: -3, dir: 'LEFT' } });
  const m = F.analyzeWindow(frames, urban());
  assert.equal(m.laneChange.status, 'detected', JSON.stringify(m.laneChange));
  assert.equal(m.laneChange.direction, 'LEFT');
});

test('진로변경을 마치고 3초 넘게 지나 추돌 → 252 적용하지 않고 판단 보류', async () => {
  const frames = F.synthFrames({ speed: 60, laneChange: { startS: -8.8, durS: 4, dir: 'RIGHT' }, signal: { fromS: -10, toS: -4, dir: 'RIGHT' } });
  const out = await runLocal(frames, urban('뒤차가 와서 박았어'));
  assert.equal(out.metrics.laneChange.status, 'completed', JSON.stringify(out.metrics.laneChange));
  assert.ok(out.metrics.laneChange.completedSecBefore > 2);
  assert.equal(out.result.status, 'abstain');
  assert.ok(out.result.reasons.some((r) => /추돌 사고인지/.test(r)));
});

test('저속(정체) 접촉은 측정하지 않고, 진술만으로 과실을 내지 않는다', async () => {
  const frames = F.synthFrames({ speed: 5 });
  const out = await runLocal(frames, urban('옆 차가 끼어들었어'));
  assert.equal(out.metrics.laneChange.status, 'too_slow');
  assert.equal(out.result.status, 'abstain');
});

// ---------- 방향지시등 ----------
test('고속도로는 신호 기준이 100m, 늦게 켜면 late', () => {
  const sc = F.SCENARIOS.find((s) => s.id === 'highway_late_signal');
  const m = F.analyzeWindow(sc.frames, sc.ctx);
  assert.equal(m.signal.requiredM, 100);
  assert.ok(m.signal.leadM >= 50 && m.signal.leadM < 100, String(m.signal.leadM));
  assert.equal(m.signal.status, 'late');
});

test('충분히 일찍 켠 신호는 ok', () => {
  const sc = F.SCENARIOS.find((s) => s.id === 'proper_signal');
  const m = F.analyzeWindow(sc.frames, sc.ctx);
  assert.equal(m.signal.status, 'ok');
  assert.ok(m.signal.leadM >= 30);
});

test('진로변경 시작 뒤에 켠 신호는 after(0m), 가산 반영', async () => {
  const frames = F.synthFrames({ speed: 50, laneChange: { startS: -2.2, durS: 4, dir: 'RIGHT' }, signal: { fromS: -1.0, dir: 'RIGHT' } });
  const out = await runLocal(frames, confirmed(urban()));
  assert.equal(out.metrics.signal.status, 'after');
  assert.equal(out.metrics.signal.leadM, 0);
  assert.ok(out.facts.vehicle.some((s) => /초 뒤에야/.test(s)));
  assert.equal(out.result.likely, 80);
});

test('켰다가 끄고 들어가면 none', () => {
  const frames = F.synthFrames({ speed: 50, laneChange: { startS: -2.2, durS: 4, dir: 'RIGHT' }, signal: { fromS: -8, toS: -5, dir: 'RIGHT' } });
  const m = F.analyzeWindow(frames, urban());
  assert.equal(m.signal.status, 'none');
});

test('깜빡임 단위(0.4초 켜짐/0.4초 꺼짐)로 들어온 방향지시등도 한 구간으로 본다', () => {
  const frames = F.synthFrames({ speed: 50, laneChange: { startS: -2.2, durS: 4, dir: 'RIGHT' } }).map((f) => {
    const on = f.t >= -7000 && Math.floor((f.t + 7000) / 400) % 2 === 0;
    return { ...f, turnSignal: on ? 'RIGHT' : 'NONE' };
  });
  const m = F.analyzeWindow(frames, urban());
  assert.equal(m.signal.status, 'ok', JSON.stringify(m.signal));
  assert.ok(m.signal.leadM > 60);
});

test('기준 거리 근처(측정 오차 범위)는 ok/late 로 단정하지 않고 borderline → 범위에만 반영', async () => {
  // 고속도로 100km/h, 실제 선행 거리 약 97m
  const frames = F.synthFrames({ speed: 100, laneChange: { startS: -2.2, durS: 4, dir: 'RIGHT' }, signal: { fromS: -5.7, dir: 'RIGHT' } });
  const out = await runLocal(frames, confirmed(highway()));
  assert.equal(out.metrics.signal.status, 'borderline', JSON.stringify(out.metrics.signal));
  const sig = out.result.modifiers.find((x) => x.id === 'B_signal');
  assert.equal(sig.decision, 'possible');
  assert.equal(out.result.low, 70);
  assert.equal(out.result.high, 80);
  const sentence = out.facts.vehicle.find((s) => /방향지시등을 켰음/.test(s));
  assert.match(sentence, /안팎/);
});

test('도로 유형을 모르면 30m~100m 사이 신호 거리는 단정하지 않는다', () => {
  const frames = F.synthFrames({ speed: 60, laneChange: { startS: -2.2, durS: 4, dir: 'RIGHT' }, signal: { fromS: -5.5, dir: 'RIGHT' } });
  const m = F.analyzeWindow(frames, { trigger: 'impact', driverStatement: '' });
  assert.equal(m.roadType, 'unknown');
  assert.equal(m.signal.status, 'borderline');
  const facts = F.renderFacts(m, {});
  assert.ok(facts.place.includes('도로 유형 정보 없음'));
});

test('기록 시작부터 켜져 있고 거리가 기준 미만이면 indeterminate → 신호 가산은 범위에만 (70~80)', async () => {
  const frames = F.synthFrames({ speed: 10, laneChange: { startS: -3, durS: 6, dir: 'LEFT' }, signal: { fromS: -10, dir: 'LEFT' } });
  const m = F.analyzeWindow(frames, urban());
  assert.equal(m.laneChange.status, 'detected');
  assert.equal(m.signal.onAtBufferStart, true);
  assert.equal(m.signal.status, 'indeterminate');
  const judged = await F.localJudge({}, { metrics: m, ctx: { laneChangeConfirmed: true } });
  const r = F.computeFault(judged.answers, m, { laneChangeConfirmed: true });
  const sig = r.modifiers.find((x) => x.id === 'B_signal');
  assert.equal(sig.decision, 'possible');
  assert.deepEqual([r.low, r.likely, r.high], [70, 70, 80]);
});

// ---------- 브레이크 ----------
test('브레이크를 밟았다가 뗀 뒤 충돌하면 밟은 시점과 뗀 시점을 함께 쓴다', () => {
  const frames = F.synthFrames({ speed: 50 }).map((f) => ({ ...f, brake: f.t >= -1200 && f.t <= -400 }));
  const m = F.analyzeWindow(frames, urban());
  assert.deepEqual([m.brake.applied, m.brake.onsetSecBefore, m.brake.releasedSecBefore], [true, 1.2, 0.4]);
  const facts = F.renderFacts(m, urban());
  assert.ok(facts.vehicle.includes('충돌 1.2초 전에 브레이크를 밟았다가 충돌 0.4초 전에 뗐음'));
});

// ---------- 결측·순서 ----------
test('방향 결측이 적으면 보간해서 같은 결과, 10%를 넘으면 측정 불가 → 판단 보류', async () => {
  const sc = F.SCENARIOS.find((s) => s.id === 'me_late_signal');
  const few = sc.frames.map((f, i) => (i % 25 === 3 ? { ...f, headingDeg: null } : f));
  const a = await runLocal(few, sc.ctx);
  assert.equal(a.metrics.laneChange.status, 'detected');
  assert.equal(a.result.likely, 80);
  const many = sc.frames.map((f, i) => (i % 5 === 0 ? { ...f, headingDeg: i % 2 ? undefined : NaN } : f));
  const b = await runLocal(many, sc.ctx);
  assert.equal(b.metrics.laneChange.status, 'unmeasurable');
  assert.equal(b.result.status, 'abstain');
});

test('null 방향을 0도로 계산해 가짜 차로변경을 만들지 않는다', () => {
  const frames = F.synthFrames({ speed: 60 }).map((f, i) => (i === 40 ? { ...f, headingDeg: null } : f));
  const m = F.analyzeWindow(frames, urban());
  assert.equal(m.laneChange.status, 'none');
});

test('프레임 순서가 뒤집혀도 같은 측정값·같은 봉인 지문', async () => {
  const sc = F.SCENARIOS[0];
  const rev = [...sc.frames].reverse();
  const m1 = F.analyzeWindow(sc.frames, sc.ctx), m2 = F.analyzeWindow(rev, sc.ctx);
  assert.deepEqual(m1, m2);
  const s1 = await F.sealEvidence(sc.frames, m1, { ...sc.ctx, now: fixedNow });
  const s2 = await F.sealEvidence(rev, m2, { ...sc.ctx, now: fixedNow });
  assert.equal(s1.hash, s2.hash);
});

test('프레임이 3개 미만이어도 안전 카드와 봉인은 나오고, 과실은 판단 보류', async () => {
  const out = await runLocal([{ t: -100, speedKph: 40, headingDeg: 72 }, { t: 0, speedKph: 38, headingDeg: 72, airbag: true }], urban());
  assert.equal(out.flow.cards[0].kind, 'safety');
  assert.equal(out.flow.mode, 'emergency');
  assert.match(out.seal.hash, /^[0-9a-f]{64}$/);
  assert.equal(out.result.status, 'abstain');
});

// ---------- 과실 계산 ----------
test('현저한 과실·중과실은 차량 기록만으로 평가하지 않는다(not_assessed)', () => {
  const m = F.analyzeWindow(F.SCENARIOS.find((s) => s.id === 'proper_signal').frames, urban());
  const answers = {
    chart: { choice: 'c252_me_B', probabilities: { c252_me_B: 0.9, c252_me_A: 0.05, c252_2: 0.03, none: 0.02 } },
    evidence_sufficient: { noul: 0.9 }, self_lane_change: { noul: 0.9 }, other_signal_fail: { noul: 0.1 }, forbidden_zone: { noul: 0.1 }, bus_lane: { noul: 0.1 },
  };
  const r = F.computeFault(answers, m, { laneChangeConfirmed: true });
  for (const id of ['A_notable', 'A_gross', 'B_notable', 'B_gross']) {
    assert.equal(r.modifiers.find((x) => x.id === id).decision, 'not_assessed');
  }
  assert.equal(r.likely, 70);
});

test('진술만 근거인 가감 요소는 Jev가 높게 답해도 "반영"하지 않고 범위에만 넣는다', () => {
  const m = F.analyzeWindow(F.SCENARIOS.find((s) => s.id === 'other_cutin_no_signal').frames, urban());
  const answers = {
    chart: { choice: 'c252_me_A', probabilities: { c252_me_A: 0.95, none: 0.05 } },
    evidence_sufficient: { noul: 0.9 }, other_signal_fail: { noul: 0.95 }, forbidden_zone: { noul: 0.95 }, bus_lane: { noul: 0.95 },
  };
  const r = F.computeFault(answers, m, { navCautions: [], laneChangeConfirmed: false });
  assert.ok(r.modifiers.every((e) => e.decision !== 'applied'), JSON.stringify(r.modifiers));
  assert.equal(r.likely, 30);
  assert.equal(r.low, 0);
});

test('내비 구간 안내가 있어도 판정 모델 해석은 "반영"하지 않는다 — 범위는 0~100 으로 제한', () => {
  const m = F.analyzeWindow(F.SCENARIOS.find((s) => s.id === 'other_cutin_no_signal').frames, urban());
  const answers = {
    chart: { choice: 'c252_me_A', probabilities: { c252_me_A: 0.95, none: 0.05 } },
    evidence_sufficient: { noul: 0.9 }, other_signal_fail: { noul: 0.95 }, forbidden_zone: { noul: 0.95 }, bus_lane: { noul: 0.95 },
  };
  const r = F.computeFault(answers, m, { navCautions: ['LANE_CHANGE_CAMERA_ALERT', 'BUS_LANE_VIOLATION_ENFORCEMENT'], laneChangeConfirmed: false });
  for (const id of ['B_forbidden', 'B_buslane', 'B_signal']) assert.equal(r.modifiers.find((x) => x.id === id).decision, 'possible', id);
  assert.equal(r.modifiers.find((x) => x.id === 'B_forbidden').basis.includes('nav'), true);
  assert.equal(r.likely, 30);
  assert.equal(r.low, 0);      // 30 - 10 - 20 - 10 → 0
});

test('차량 기록과 판정 유형이 어긋나면 판단 보류', () => {
  const mLC = F.analyzeWindow(F.SCENARIOS.find((s) => s.id === 'me_late_signal').frames, urban());
  const mKeep = F.analyzeWindow(F.SCENARIOS.find((s) => s.id === 'other_cutin_unclear').frames, urban());
  const ans = (choice) => ({ chart: { choice, probabilities: { [choice]: 0.9, none: 0.1 } }, evidence_sufficient: { noul: 0.9 } });
  assert.equal(F.computeFault(ans('c252_me_A'), mLC, {}).status, 'abstain');
  assert.equal(F.computeFault(ans('c252_me_B'), mKeep, {}).status, 'abstain');
  assert.equal(F.computeFault(ans('c252_2'), mKeep, {}).status, 'abstain');
});

test('유형 확률이 낮으면 판단 보류', () => {
  const m = F.analyzeWindow(F.SCENARIOS.find((s) => s.id === 'me_late_signal').frames, urban());
  const answers = { chart: { choice: 'c252_me_B', probabilities: { c252_me_B: 0.45, c252_2: 0.4, none: 0.15 } }, evidence_sufficient: { noul: 0.9 } };
  const r = F.computeFault(answers, m, {});
  assert.equal(r.status, 'abstain');
  assert.ok(r.evidenceChecklist.length >= 3);
});

test('2순위 해석(역할 포함 이름)과 "해당 없음" 가능성을 함께 보여준다', () => {
  const m = F.analyzeWindow(F.SCENARIOS.find((s) => s.id === 'me_late_signal').frames, urban());
  const answers = {
    chart: { choice: 'c252_me_B', probabilities: { c252_me_B: 0.62, c252_2: 0.1, none: 0.28 } },
    evidence_sufficient: { noul: 0.9 }, self_lane_change: { noul: 0.9 }, other_signal_fail: { noul: 0.1 }, forbidden_zone: { noul: 0.1 }, bus_lane: { noul: 0.1 },
  };
  const r = F.computeFault(answers, m, { laneChangeConfirmed: true });
  assert.equal(r.status, 'estimate');
  assert.ok(r.caveats.some((c) => /해당하지 않을 가능성도 28%/.test(c)));
  const r2 = F.computeFault({ ...answers, chart: { choice: 'c252_me_B', probabilities: { c252_me_B: 0.65, c252_me_A: 0.3, none: 0.05 } } }, m, { laneChangeConfirmed: true });
  assert.equal(r2.alternative.title, F.CHART_OPTIONS.c252_me_A.label);
});

test('동시 진로변경(252-2)은 진술 근거라 252(내 차 B)로 계산하고 50%를 범위 아래쪽 끝으로만 넣는다', async () => {
  const sc = F.SCENARIOS.find((s) => s.id === 'simultaneous');
  const out = await runLocal(sc.frames, sc.ctx);
  assert.equal(out.result.judgedOption, 'c252_2');
  assert.equal(out.result.option, 'c252_me_B');
  const card = out.flow.cards.find((c) => c.kind === 'estimate');
  assert.match(card.title, /50~70%/);
  assert.ok(card.lines.some((l) => /가능성: 상대 차도 동시에 진로변경/.test(l)));
  // "~하는 동시에"는 동시 진로변경이 아니다
  for (const st of ['깜빡이 켜는 동시에 들어갔어요', '브레이크 밟는 동시에 부딪혔어요']) {
    assert.equal(F.parseStatement(st).simultaneous, false, st);
    const o = await runLocal(sc.frames, confirmed({ ...sc.ctx, driverStatement: st }));
    assert.equal(o.result.low, 70, st);
  }
  assert.equal(F.parseStatement('제가 들어가는데 옆 차도 동시에 들어왔어요').simultaneous, true);
});

// ---------- 대체 판정기(진술 해석) ----------
test('진술 해석: 주어·부정·신호등 혼동·교차로/주차장', () => {
  const P = F.parseStatement;
  let c = P('내가 깜빡이 안 켜고 들어갔어');
  assert.equal(c.selfNoSignal, true); assert.equal(c.otherNoSignal, false); assert.equal(c.selfLC, true);
  c = P('오른쪽 차가 깜빡이도 안 켜고 갑자기 들어왔어');
  assert.equal(c.otherNoSignal, true); assert.equal(c.otherLC, true);
  assert.equal(P('신호가 없었어').otherNoSignal, false);
  assert.equal(P('교차로에서 신호등이 없었는데 옆에서 차가 나왔어').otherType, true);
  assert.equal(P('오른쪽 차가 들어왔어. 실선은 아니고 점선이었어').solidLine, false);
  assert.equal(P('실선 구간이었는데 들어가다가 박았어').solidLine, true);
  assert.equal(P('버스전용차로는 아니었어').busLane, false);
  assert.equal(P('동시에 들어간 건 아니고 내가 먼저 들어갔어').simultaneous, false);
  assert.equal(P('옆 차랑 동시에 같은 차로로 들어가다가 부딪혔어').simultaneous, true);
  assert.equal(P('상대가 깜빡이를 안 켰는지 잘 모르겠어').otherNoSignal, false);
  assert.equal(P('끼어들려다가 부딪혔어').selfLC, true);
  assert.equal(P('옆 차가 차선 바꾸다가 박았어').otherLC, true);
});

test('진술 해석 결과: 자기 진술과 기록이 어긋나면 보류, 교차로·주차장은 도표 밖', async () => {
  const straight = F.SCENARIOS.find((s) => s.id === 'other_cutin_unclear').frames;
  for (const st of ['내가 깜빡이 안 켜고 들어갔어', '주차장에서 나오는데 옆 차가 들어왔어', '교차로에서 신호등이 없었는데 옆에서 차가 나왔어']) {
    const out = await runLocal(straight, urban(st));
    assert.equal(out.result.status, 'abstain', st);
  }
});

test('대체 판정기의 진술 Noul 은 0.6 이하 → 진술 키워드만으로 과실이 0%가 되지 않는다', async () => {
  const straight = F.SCENARIOS.find((s) => s.id === 'other_cutin_unclear').frames;
  const out = await runLocal(straight, keptLane(urban('상대가 깜빡이 없이 실선에서 버스전용차로로 끼어들었어')));
  for (const k of ['other_signal_fail', 'forbidden_zone', 'bus_lane']) assert.ok(out.judged.answers[k].noul <= 0.6);
  assert.ok(out.result.modifiers.every((e) => e.decision !== 'applied'));
  assert.equal(out.result.likely, 30);
});

// ---------- Jev 연동 ----------
test('Jev 요청 모양: Choice 선택지 4개(none 포함), Noul 5개(본인 진로변경 인정 포함), 원시 숫자 배열 없음', () => {
  const sc = F.SCENARIOS[0];
  const m = F.analyzeWindow(sc.frames, sc.ctx);
  const req = F.buildJevRequest(F.renderFacts(m, sc.ctx));
  assert.deepEqual(Object.keys(req.questions.chart.criteria).sort(), ['c252_2', 'c252_me_A', 'c252_me_B', 'none']);
  assert.match(req.questions.chart.instructions, /none을 고르세요/);
  const nouls = Object.values(req.questions).filter((q) => q.type === 'noul');
  assert.equal(nouls.length, 5);
  assert.ok(req.questions.self_lane_change);
  assert.ok(req.state.vehicle_facts.every((s) => typeof s === 'string'));
  assert.ok(JSON.stringify(req).length < 4500);
});

test('Jev 응답 검증: 선택지 밖 답·프로토타입 키·확률 키 오류는 거부', () => {
  const req = F.buildJevRequest({ vehicle: [], place: [], driverStatement: '' });
  const okNoul = { evidence_sufficient: { noul: 0.5 }, self_lane_change: { noul: 0.5 }, other_signal_fail: { noul: 0.5 }, forbidden_zone: { noul: 0.5 }, bus_lane: { noul: 0.5 } };
  assert.throws(() => F.validateJevAnswers(req, { answers: { ...okNoul, chart: { choice: 'zzz', probabilities: {} } } }));
  assert.throws(() => F.validateJevAnswers(req, { answers: { ...okNoul, chart: { choice: 'constructor', probabilities: { constructor: 1 } } } }));
  assert.throws(() => F.validateJevAnswers(req, { answers: { ...okNoul, chart: { choice: 'none', probabilities: { none: 0.5, hack: 0.5 } } } }));
  assert.throws(() => F.validateJevAnswers(req, { answers: { ...okNoul, chart: { choice: 'none', probabilities: { none: 1.5 } } } }));
  assert.doesNotThrow(() => F.validateJevAnswers(req, { answers: { ...okNoul, chart: { choice: 'none', probabilities: { none: 0.9, c252_me_A: 0.1 } } } }));
});

test('Jev 호출 실패 시 대체 판정기로 내려가고, 결과와 안내 카드에 표시한다', async () => {
  const sc = F.SCENARIOS[0];
  const failing = F.makeJevJudge({ apiKey: 'x', fetchImpl: async () => ({ ok: false, status: 503 }) });
  const out = await F.run(sc.frames, { ...sc.ctx, now: fixedNow }, failing);
  assert.match(out.judgeError, /503/);
  assert.equal(out.judged.model, 'local-standin');
  assert.deepEqual([out.result.judge.standIn, out.result.judge.fallback], [true, true]);
  assert.equal(out.result.likely, 80);
  const card = out.flow.cards.find((c) => c.kind === 'estimate');
  assert.ok(card.lines.some((l) => /규칙 기반 대체 판정.*연결 실패/.test(l)));
  const strict = await F.run(sc.frames, { ...sc.ctx, now: fixedNow }, failing, { onJudgeFailure: 'abstain' });
  assert.equal(strict.result.status, 'abstain');
});

test('Jev 정상 응답을 그대로 쓴다 (모의 fetch) — 상대 신호는 진술 근거라 범위로', async () => {
  const sc = F.SCENARIOS.find((s) => s.id === 'other_cutin_unclear');
  let sent = null;
  const fake = async (url, init) => {
    sent = { url, init };
    return { ok: true, json: async () => ({
      provider: 'TypeSafe',
      answers: {
        chart: { type: 'choice', choice: 'c252_me_A', probabilities: { c252_me_A: 0.91, none: 0.05, c252_2: 0.03, c252_me_B: 0.01 }, confidence: 0.88 },
        evidence_sufficient: { type: 'noul', noul: 0.82 },
        self_lane_change: { type: 'noul', noul: 0.05 },
        other_signal_fail: { type: 'noul', noul: 0.86 },
        forbidden_zone: { type: 'noul', noul: 0.04 },
        bus_lane: { type: 'noul', noul: 0.03 },
      },
    }) };
  };
  const out = await F.run(sc.frames, { ...sc.ctx, now: fixedNow }, F.makeJevJudge({ apiKey: 'k', fetchImpl: fake }));
  assert.equal(sent.url, 'https://api.typesafe.ai/v1/systemone');
  assert.equal(sent.init.headers.Authorization, 'Bearer k');
  assert.equal(JSON.parse(sent.init.body).model, 'jev-latest');
  assert.equal(out.judgeError, null);
  assert.equal(out.judged.model, 'jev-latest'); // 응답에 model 이 없으면 요청 값으로 채운다
  assert.equal(out.result.judge.standIn, false);
  assert.deepEqual([out.result.low, out.result.likely, out.result.high], [20, 30, 30]);
});

// ---------- 봉인 ----------
test('같은 기록은 같은 봉인 지문, 한 프레임만 바뀌어도 다른 지문', async () => {
  const sc = F.SCENARIOS[0];
  const m = F.analyzeWindow(sc.frames, sc.ctx);
  const a = await F.sealEvidence(sc.frames, m, { ...sc.ctx, now: fixedNow });
  const b = await F.sealEvidence(sc.frames, m, { ...sc.ctx, now: fixedNow });
  const tampered = sc.frames.map((f, i) => (i === 95 ? { ...f, turnSignal: f.turnSignal === 'NONE' ? 'RIGHT' : 'NONE' } : f));
  const c = await F.sealEvidence(tampered, m, { ...sc.ctx, now: fixedNow });
  assert.equal(a.hash, b.hash);
  assert.notEqual(a.hash, c.hash);
});

test('순수 JS SHA-256 대체 구현이 표준 결과와 같다', async () => {
  const { createHash } = require('node:crypto');
  for (const s of ['', 'abc', '퍼스트30 사고 기록', 'x'.repeat(55), 'y'.repeat(56), 'z'.repeat(1000)]) {
    const want = createHash('sha256').update(s, 'utf8').digest('hex');
    assert.equal(F.sha256Fallback(new TextEncoder().encode(s)), want, s.slice(0, 10));
  }
});

// ---------- 안전 안내 ----------
test('부상 신고만 있으면 "에어백" 음성이 나오지 않고, 고속도로 긴급 상황엔 대피 안내가 함께 나온다', async () => {
  const straight = F.SCENARIOS.find((s) => s.id === 'other_cutin_unclear').frames;
  const injured = await runLocal(straight, { ...urban(), injuryReported: true });
  assert.equal(injured.flow.mode, 'emergency');
  assert.doesNotMatch(injured.flow.cards[0].tts, /에어백/);
  const hwAirbag = await runLocal(straight.map((f) => ({ ...f, airbag: f.t >= -50 })), highway());
  assert.equal(hwAirbag.flow.mode, 'emergency');
  assert.ok(hwAirbag.flow.cards[0].lines.some((l) => /가드레일 밖/.test(l)));
  assert.match(hwAirbag.flow.cards[0].tts, /가드레일/);
});

test('과실 카드에는 현저한 과실·중과실 미반영과 참고치 고지가 항상 붙는다', async () => {
  for (const sc of F.SCENARIOS) {
    const out = await runLocal(sc.frames, sc.ctx);
    const card = out.flow.cards.find((c) => c.kind === 'estimate');
    if (!card) continue;
    assert.ok(card.lines.some((l) => /현저한 과실·중과실은 반영하지 않았어요/.test(l)), sc.id);
    assert.ok(card.lines.some((l) => /참고치/.test(l)), sc.id);
  }
});

test('안내 문구는 과장·허위 신고를 권하지 않는다', async () => {
  const banned = /(뒷목|아픈 ?척|다친 ?척|드러누|입원|진단서|합의금|보험금을? (더|많이)|한방병원|목 잡|과장해)/;
  const statements = ['', '옆 차가 들어왔어', '뒤차가 박았어 목이 아파', '보험금 많이 받으려면 어떻게 해'];
  for (const sc of F.SCENARIOS) {
    for (const st of statements) {
      const out = await runLocal(sc.frames, { ...sc.ctx, driverStatement: st });
      const text = JSON.stringify(out.flow).replace(F.SPEECH_PRINCIPLE, '');
      assert.doesNotMatch(text.replace(/운전자 본인 진술[^"]*"[^"]*"/g, ''), banned, `${sc.id} / ${st}`);
    }
  }
});

// ---------- 2차 검토 경계 상황 ----------
// 도로 곡률 k(t)[1/m]를 적분해 방향을 만든다. 핸들은 곡률에 비례해 꺾는다 (R800 에서 2.5°)
function roadFrames(kph, kOf, { steer = true } = {}) {
  const v = kph / 3.6; let h = 72;
  return F.synthFrames({ speed: kph }).map((f, i) => {
    const t = f.t / 1000;
    if (i > 0) h += (v * kOf(t) * 0.1) * 180 / Math.PI;
    return { ...f, headingDeg: (h + 360) % 360, steeringDeg: steer ? Math.round(kOf(t) * 2000 * 10) / 10 : null };
  });
}
const ramp = (t0, t1, k) => (t) => (t <= t0 ? 0 : t >= t1 ? k : (k * (t - t0)) / (t1 - t0));
const rampDown = (t0, t1, k) => (t) => (t <= t0 ? k : t >= t1 ? 0 : k * (1 - (t - t0) / (t1 - t0)));

test('곡선 진입·진출(차로 유지)은 "내 차 진로변경"으로 단정하지 않는다', async () => {
  const cases = [[100, ramp(-4, -2, 1 / 1500)], [100, ramp(-4, -2.5, 1 / 1000)], [80, ramp(-3.5, -2, 1 / 600)], [60, ramp(-3, -1.5, 1 / 400)],
    [100, rampDown(-4, -2, 1 / 1500)], [80, rampDown(-3.5, -2, 1 / 600)], [60, rampDown(-3, -1.5, 1 / 400)]];
  for (const [kph, k] of cases) {
    for (const steer of [true, false]) {
      const out = await runLocal(roadFrames(kph, k, { steer }), urban('오른쪽 차가 갑자기 들어왔어'));
      assert.notEqual(out.metrics.laneChange.status, 'detected', `${kph} steer=${steer}`);
      assert.ok(!(out.result.status === 'estimate' && out.result.option === 'c252_me_B'));
    }
  }
});

test('차선 안 흔들림(방향 ±0.3~1°, 주기 4~8초)은 어떤 위상에서도 "내 차 진로변경"이 되지 않는다', async () => {
  for (const kph of [60, 100, 120]) for (const [A, P] of [[0.5, 4], [0.8, 8], [1.0, 5], [0.6, 6]]) {
    for (let ph = 0; ph < 1; ph += 1 / 8) {
      const fr = F.synthFrames({ speed: kph }).map((f) => {
        const t = f.t / 1000;
        return { ...f, headingDeg: 72 + A * Math.sin(2 * Math.PI * (t / P + ph)), steeringDeg: Math.round(2 * A * Math.cos(2 * Math.PI * (t / P + ph)) * 10) / 10 };
      });
      const out = await runLocal(fr, highway('옆 차가 들어왔어'));
      assert.notEqual(out.metrics.laneChange.status, 'detected', `${kph} ±${A} P${P} ph${ph}`);
    }
  }
});

test('회피 조향(충돌 1~2초 전 한쪽으로 꺾으며 제동)은 진로변경으로 단정하지 않는다', async () => {
  for (const [kph, t0, peak] of [[60, -1.0, 4], [60, -1.4, 4], [60, -1.6, 4], [80, -2.0, 3], [50, -1.5, 6]]) {
    const fr = F.synthFrames({ speed: kph, brakeFromS: t0 + 0.2 }).map((f) => {
      const t = f.t / 1000;
      return { ...f, headingDeg: 72 - (t < t0 ? 0 : peak * Math.min(1, (t - t0) / 0.6)), steeringDeg: t < t0 ? 0 : -8 * Math.min(1, (t - t0) / 0.3) };
    });
    const out = await runLocal(fr, urban('오른쪽 차가 깜빡이도 안 켜고 갑자기 들어와서 왼쪽으로 피했는데 박았어'));
    assert.equal(out.result.status, 'abstain', `${kph} ${t0}`);
  }
});

test('핸들 기록이 없으면 진행 방향만으로 진로변경을 확정하지 않는다 (신호 거리 부풀림도 막힘)', async () => {
  const fr = F.synthFrames({ speed: 100, laneChange: { startS: -3, durS: 6, dir: 'RIGHT' }, signal: { fromS: -5.9, dir: 'RIGHT' } }).map((f) => ({ ...f, steeringDeg: null }));
  const out = await runLocal(fr, highway('들어가다 닿았어'));
  assert.equal(out.metrics.laneChange.status, 'uncertain');
  assert.equal(out.metrics.laneChange.reason, 'no_steering');
  assert.equal(out.result.status, 'abstain');
});

test('기록이 8초보다 짧으면 차로변경을 측정하지 않는다', async () => {
  const sc = F.SCENARIOS.find((s) => s.id === 'me_late_signal');
  const out = await runLocal(sc.frames.filter((f) => f.t >= -3000), sc.ctx);
  assert.equal(out.metrics.laneChange.status, 'unmeasurable');
  assert.equal(out.metrics.laneChange.reason, 'short_buffer');
  assert.equal(out.result.status, 'abstain');
  assert.ok(!out.facts.vehicle.some((x) => /차로를 유지했음/.test(x)));
});

test('방향지시등·브레이크 채널이 없거나 숫자 enum 이면 "켜지 않음"으로 단정하지 않는다', async () => {
  const sc = F.SCENARIOS.find((s) => s.id === 'proper_signal');
  for (const v of [null, undefined]) {
    const out = await runLocal(sc.frames.map((f) => ({ ...f, turnSignal: v, brake: v })), sc.ctx);
    assert.equal(out.metrics.signal.status, 'unavailable');
    // 신호를 켰는지 모르면 70% 하나로 단정하지 않고 70~80% 범위로
    assert.equal(out.result.modifiers.find((x) => x.id === 'B_signal').decision, 'possible');
    assert.deepEqual([out.result.low, out.result.likely, out.result.high], [70, 70, 80]);
    assert.ok(out.facts.vehicle.includes('방향지시등 기록이 없어 신호를 켰는지 확인할 수 없음'));
    assert.ok(out.facts.vehicle.includes('브레이크 기록이 없어 확인할 수 없음'));
    assert.ok(!out.facts.vehicle.some((x) => /켜지 않/.test(x)));
  }
  const num = await runLocal(sc.frames.map((f) => ({ ...f, turnSignal: f.turnSignal === 'RIGHT' ? 2 : 0 })), sc.ctx);
  assert.equal(num.metrics.signal.status, 'unavailable');
  const lower = await runLocal(sc.frames.map((f) => ({ ...f, turnSignal: f.turnSignal === 'RIGHT' ? 'right' : 'off' })), sc.ctx);
  assert.equal(lower.metrics.signal.status, 'ok');
});

test('음성·버튼·급감속·알 수 없는 트리거는 충돌 시각을 모른다 → 진로변경을 측정하지 않고 숫자도 내지 않는다', async () => {
  // 직진 중 충돌 → 2초 뒤 오른쪽 신호를 켜고 갓길로 이동 → 정차 → "사고 났어" (사고 뒤 이동이 사고 전 진로변경처럼 보이는 흐름)
  const fr = F.synthFrames({ speed: (t) => (t < -6 ? 60 : Math.max(0, 60 - (t + 6) * 25)), fromS: -20,
    laneChange: { startS: -12, durS: 4, dir: 'RIGHT' }, signal: { fromS: -12.5, dir: 'RIGHT' } });
  for (const trigger of ['voice', 'button', 'decel', 'VOICE', 'speech', 'gsensor', undefined]) {
    const out = await runLocal(fr, { ...urban('오른쪽 차가 갑자기 들어왔어요'), trigger });
    assert.equal(out.metrics.impact.known, false, String(trigger));
    assert.equal(out.metrics.laneChange.reason, 'impact_unknown');
    assert.equal(out.result.status, 'abstain');
    assert.ok(out.result.reasons.some((r) => /사고 뒤 갓길로 옮긴 것인지/.test(r)));
    // 사실 문장·봉인값에 진로변경·신호 판단을 남기지 않는다
    assert.ok(!out.facts.vehicle.some((x) => /움직이고 있었음|방향지시등을 켰음|켜고 있었음/.test(x)), out.facts.vehicle.join(' / '));
    assert.equal(out.seal.payload.metrics.laneChange.status, 'unmeasurable');
    assert.equal(out.seal.payload.trigger, trigger ? String(trigger).toLowerCase() : 'unknown');
    const sealCard = out.flow.cards.find((c) => c.kind === 'evidence');
    assert.match(sealCard.lines[0], /^신고 전 20초 차량 기록을 봉인했어요/);
  }
  // 에어백·충격 감지만 충돌 시각으로 믿는다 (대소문자 무관)
  const sc = F.SCENARIOS.find((s) => s.id === 'me_late_signal');
  for (const trigger of ['impact', 'AIRBAG', ' Impact ']) {
    const out = await runLocal(sc.frames, { ...sc.ctx, trigger });
    assert.equal(out.result.status, 'estimate', trigger);
    assert.match(out.flow.cards.find((c) => c.kind === 'evidence').lines[0], /^충돌 전 10초/);
  }
});

test('진술 해석 2: 제 차·저희 차, 뒤에 오는 주어, "아닌/아님", 얼버무림', () => {
  const P = F.parseStatement;
  let c = P('제 차가 깜빡이 없이 들어갔어요');
  assert.deepEqual([c.selfLC, c.selfNoSignal, c.otherNoSignal, c.otherLC], [true, true, false, false]);
  assert.equal(P('저희 차가 차선을 바꾸다가 부딪혔어요').selfLC, true);
  c = P('끼어든 건 저예요'); assert.deepEqual([c.selfLC, c.otherLC], [true, false]);
  c = P('깜빡이를 늦게 켠 건 저예요'); assert.deepEqual([c.selfLateSignal, c.otherLateSignal], [true, false]);
  c = P('옆 차가 들어온 게 아니라 제가 들어갔어요'); assert.deepEqual([c.selfLC, c.otherLC], [true, false]);
  c = P('차선 변경하던 옆 차가 저를 박았어요'); assert.deepEqual([c.otherLC, c.selfLC], [true, false]);
  assert.equal(P('오른쪽 차가 들어왔어요. 실선이 아닌 점선이었어요').solidLine, false);
  assert.equal(P('오른쪽 차가 들어왔어요. 실선이 아닌 점선이었어요').otherLC, true);
  assert.equal(P('옆 차가 들어왔어요 실선 아님').solidLine, false);
  assert.equal(P('옆 차가 들어왔어요 실선 아님').otherLC, true);
  assert.equal(P('버스전용차로가 아닌 일반 차로였어요').busLane, false);
  assert.equal(P('상대가 깜빡이를 안 켰다는 건 아니고요 잘 모르겠어요').otherNoSignal, false);
});

test('고속도로 안내는 대피와 부딪히지 않고, 긴급 상황엔 10초에 과실을 말하지 않는다', async () => {
  const straight = F.SCENARIOS.find((s) => s.id === 'other_cutin_unclear').frames;
  const hw = await runLocal(straight, highway(''));
  const txt = JSON.stringify(hw.flow);
  assert.doesNotMatch(txt, /사진 먼저 찍고|노면 표시\(실선\/점선\) 사진/);
  const em = await runLocal(straight.map((f) => ({ ...f, airbag: f.t >= -50 })), highway('옆 차가 들어왔어'));
  assert.equal(em.flow.cards[2].kind, 'rescue');
  assert.doesNotMatch(em.flow.cards[2].tts, /퍼센트/);
  const fault = em.flow.cards.find((c) => c.kind === 'estimate' || c.kind === 'abstain');
  assert.equal(fault.at, 25);
  assert.doesNotMatch(fault.tts, /퍼센트/);
});

test('동승자가 있으면 안전 안내에 함께 넣는다', async () => {
  const out = await runLocal(F.SCENARIOS[0].frames, { ...F.SCENARIOS[0].ctx, occupants: 1 });
  assert.ok(out.flow.cards[0].lines.some((l) => /동승자/.test(l)));
});

test('판정 모델에 보내는 진술에서 차량 번호·전화번호를 지운다', () => {
  const m = F.analyzeWindow(F.SCENARIOS[0].frames, urban());
  const facts = F.renderFacts(m, urban('12가 3456 차가 들어왔어요 제 번호는 010-1234-5678'));
  assert.doesNotMatch(facts.driverStatement, /3456|1234-5678/);
  assert.match(facts.driverStatement, /\[차량번호\].*\[전화번호\]/);
});

test('판정 모델 실패 + 보류 모드에서는 "대체 판정을 썼어요" 문구를 붙이지 않는다', async () => {
  const sc = F.SCENARIOS[0];
  const failing = F.makeJevJudge({ apiKey: 'x', fetchImpl: async () => ({ ok: false, status: 500 }) });
  const out = await F.run(sc.frames, { ...sc.ctx, now: fixedNow }, failing, { onJudgeFailure: 'abstain' });
  const card = out.flow.cards.find((c) => c.kind === 'abstain');
  assert.ok(!card.lines.some((l) => /대체 판정을 썼어요/.test(l)));
});

// ---------- 3차 검토 경계 상황 ----------
test('짧은 굽은 길·교차로 차로 어긋남(방향지시등 없음)은 "내 차 진로변경"으로 단정하지 않는다', async () => {
  // 시속 60km, 충돌 3.5~1초 전에 약 3° 꺾이는 굽은 길 (핸들은 꺾었다 되돌림)
  const bend = roadFrames(60, (t) => (t >= -3.5 && t < -1 ? 1 / 800 : 0));
  const o1 = await runLocal(bend, urban('옆 차가 들어왔어요'));
  assert.notEqual(o1.metrics.laneChange.status, 'detected', JSON.stringify(o1.metrics.laneChange));
  assert.ok(!(o1.result.status === 'estimate' && o1.result.option === 'c252_me_B'));
  // 차로가 옆으로 약 3m 어긋나는 교차로 (S자, 핸들 왼쪽→오른쪽)
  const jog = F.synthFrames({ speed: 40 }).map((f) => {
    const t = f.t / 1000;
    const dev = t >= -3 && t < -1.5 ? 4 * Math.sin(Math.PI * (t + 3) / 1.5) : t >= -1.5 && t < 0 ? -4 * Math.sin(Math.PI * (t + 1.5) / 1.5) : 0;
    const st = t >= -3 && t < -1.5 ? 5 * Math.cos(Math.PI * (t + 3) / 1.5) : t >= -1.5 && t < 0 ? -5 * Math.cos(Math.PI * (t + 1.5) / 1.5) : 0;
    return { ...f, headingDeg: 72 + dev, steeringDeg: Math.round(st * 10) / 10 };
  });
  const o2 = await runLocal(jog, urban('옆 차가 들어왔어요'));
  assert.ok(!(o2.result.status === 'estimate' && o2.result.option === 'c252_me_B'));
});

test('S자 회피(먼저 제동, 왼쪽으로 피했다 바로잡음)는 진로변경으로 단정하지 않는다', async () => {
  const fr = F.synthFrames({ speed: 60, brakeFromS: -2.4 }).map((f) => {
    const t = f.t / 1000;
    const dev = t >= -1.8 && t < -0.6 ? -3 * Math.sin(Math.PI * (t + 1.8) / 1.2) : 0;
    const st = t >= -1.8 && t < -0.6 ? -6 * Math.cos(Math.PI * (t + 1.8) / 1.2) : 0;
    return { ...f, headingDeg: 72 + dev, steeringDeg: Math.round(st * 10) / 10 };
  });
  const out = await runLocal(fr, urban('오른쪽 차가 갑자기 들어와서 피했어요'));
  assert.ok(!(out.result.status === 'estimate' && out.result.option === 'c252_me_B'), JSON.stringify(out.metrics.laneChange));
});

test('느린 고속도로 진로변경(7.5초, 신호 켬)이 "차로 유지"로 바뀌지 않는다', async () => {
  const fr = F.synthFrames({ speed: 100, laneChange: { startS: -6.5, durS: 7.5, dir: 'RIGHT' }, signal: { fromS: -9, dir: 'RIGHT' } });
  const out = await runLocal(fr, highway('옆 차가 들어왔어요'));
  assert.notEqual(out.metrics.laneChange.status, 'none');
  assert.ok(!(out.result.status === 'estimate' && out.result.option === 'c252_me_A'));
});

test('도로 유형을 몰라도 시속 80km 이상이면 고속도로 안전 안내(대피 먼저)', async () => {
  const out = await runLocal(F.synthFrames({ speed: 100 }), { driverStatement: '' });
  assert.equal(out.flow.mode, 'highway');
  assert.match(out.flow.cards[0].title, /비트박스/);
  assert.doesNotMatch(JSON.stringify(out.flow), /사진부터 찍고/);
});

test('핸들 부호가 반대로 들어오면 핸들 기록을 쓰지 않고 그 사실을 남긴다', async () => {
  const sc = F.SCENARIOS.find((s) => s.id === 'me_late_signal');
  const out = await runLocal(sc.frames.map((f) => ({ ...f, steeringDeg: -f.steeringDeg })), sc.ctx);
  assert.equal(out.metrics.quality.steeringSign, 'reversed');
  assert.notEqual(out.metrics.laneChange.status, 'detected');
  assert.ok(out.facts.vehicle.some((x) => /부호가 반대로/.test(x)));
});

test('2Hz 프레임에서도 방향지시등 선행 거리가 크게 틀리지 않는다', () => {
  const frames = F.synthFrames({ speed: 50, laneChange: { startS: -2.2, durS: 4, dir: 'RIGHT' }, signal: { fromS: -5.2, dir: 'RIGHT' } })
    .filter((f) => f.t % 500 === 0);
  const m = F.analyzeWindow(frames, urban());
  assert.equal(m.laneChange.status, 'detected', JSON.stringify(m.laneChange));
  assert.ok(Math.abs(m.signal.leadM - 42) <= 8, String(m.signal.leadM)); // 실제 약 42m
  assert.equal(m.signal.status, 'ok');
});

test('진술 해석 3: 쉼표 뒤 주어, "안 했어요", "제가 아니라", 얼버무림, 위반 정황 없는 전용차로', () => {
  const P = F.parseStatement;
  assert.equal(P('끼어들었어요, 제가').selfLC, true);
  assert.equal(P('끼어들었어요, 제가').otherLC, false);
  assert.equal(P('저는 차선 변경 안 했어요').selfLC, false);
  const c = P('차선 바꾼 건 제가 아니라 상대예요');
  assert.deepEqual([c.otherLC, c.selfLC], [true, false]);
  assert.equal(P('실선이었는지 모르겠어요').solidLine, false);
  assert.equal(P('실선 넘어간 거 아니에요').solidLine, false);
  assert.equal(P('버스가 전용차로에서 나오다가 부딪혔어요').busLane, false);
  assert.equal(P('상대가 버스전용차로를 타고 오다가 끼어들었어').busLane, true);
  assert.equal(P('동시에 차선을 바꿨어요').simultaneous, true);
});

// ---------- 4차 검토 경계 상황 ----------
// 도로 곡률을 받아 진행 방향·핸들을 만드는 물리 모형 (핸들 = 조향비 15 × 축거 2.7m × 곡률 + 언더스티어)
function physFrames({ kph, fromS = -10, roadK = () => 0, signal = null, lc = null }) {
  const v = kph / 3.6;
  const steerOf = (k) => (2.7 * k + (2 * Math.PI / 180 / 9.81) * v * v * k) * 15 * 180 / Math.PI;
  let h = 72;
  return F.synthFrames({ speed: kph, fromS, signal, laneChange: lc }).map((f, i, arr) => {
    const t = f.t / 1000;
    if (i > 0) h += v * roadK(t) * ((f.t - arr[i - 1].t) / 1000) * 180 / Math.PI;
    const lcDev = f.headingDeg - 72;
    return { ...f, headingDeg: (h + lcDev + 360) % 360, steeringDeg: Math.round((f.steeringDeg + steerOf(roadK(t))) * 10) / 10 };
  });
}
const bump = (t0, t1, k) => (t) => (t <= t0 || t >= t1 ? 0 : k * Math.sin(Math.PI * (t - t0) / (t1 - t0)));

test('같은 쪽 신호를 켠 채 굽은 길을 따라간 "차로 유지"는 어떤 진술에도 "내 차 진로변경" 숫자가 나오지 않는다', async () => {
  // 시속 60km, 충돌 3.5~1초 전 오른쪽으로 약 3° 굽은 길, 오른쪽 신호는 10초 전부터(끼어들 틈 대기) 또는 0.5초 전
  const k = (3 * Math.PI / 180) / (60 / 3.6) / (2.5 * 2 / Math.PI);
  for (const signal of [{ fromS: -10, dir: 'RIGHT' }, { fromS: -0.5, dir: 'RIGHT' }]) {
    const fr = physFrames({ kph: 60, roadK: bump(-3.5, -1, k), signal });
    for (const st of ['왼쪽 차가 갑자기 들어왔어요', '옆 차가 들어왔어요', '', '저는 차선 안 바꿨고 깜빡이만 켜 놨어요', '차선 바꾸려고 깜빡이 켜고 기다리는데 박았어요']) {
      const out = await runLocal(fr, urban(st));
      assert.ok(!(out.result.status === 'estimate' && out.result.option === 'c252_me_B'), `${signal.fromS} "${st}" ${JSON.stringify(out.metrics.laneChange)}`);
    }
    // 핸들이 가운데로 돌아왔는데 방향이 새 값에 머물러 있으면 굽은 길로 본다
    assert.equal((await runLocal(fr, urban(''))).metrics.laneChange.reason, 'bend_hold');
  }
});

test('"내 차 진로변경"은 운전자 확인이 있어야 숫자가 나온다 — 없으면 확인 질문, "아니요"면 보류', async () => {
  const sc = F.SCENARIOS.find((s) => s.id === 'me_late_signal');
  const ask = await runLocal(sc.frames, { ...sc.ctx, laneChangeConfirmed: undefined, driverStatement: '옆 차선 차랑 부딪혔어' });
  assert.equal(ask.result.status, 'abstain');
  assert.match(ask.result.confirm.question, /오른쪽 차로로 진로를 바꾸던 중이었나요/);
  const card = ask.flow.cards.find((c) => c.kind === 'abstain');
  assert.ok(card.confirm);
  assert.match(card.title, /확인이 필요해요/);
  assert.match(card.tts, /예 또는 아니요/);
  assert.ok(ask.facts.vehicle.some((x) => /굽은 길이나 차로가 어긋난 곳/.test(x)));
  const yes = await runLocal(sc.frames, confirmed({ ...sc.ctx, driverStatement: '' }));
  assert.deepEqual([yes.result.status, yes.result.option, yes.result.likely], ['estimate', 'c252_me_B', 80]);
  assert.ok(yes.result.caveats.some((c) => /"그때 오른쪽 차로로 진로를 바꾸던 중이었나요\?"에 "예"/.test(c)), yes.result.caveats.join(' / '));
  assert.equal(yes.request.state.driver_confirmation, '운전자 확인 답변: "그때 오른쪽 차로로 진로를 바꾸던 중이었나요?"에 "예"');
  const no = await runLocal(sc.frames, { ...sc.ctx, laneChangeConfirmed: false });
  assert.equal(no.result.status, 'abstain');
  assert.equal(no.result.confirm, null);
  assert.ok(no.result.reasons.some((r) => /"아니요"라고 답했음/.test(r)), no.result.reasons.join(' / '));
  assert.equal(no.request.state.driver_confirmation, '운전자 확인 답변: "그때 오른쪽 차로로 진로를 바꾸던 중이었나요?"에 "아니요"');
  // 질문은 기록 내용을 앞세우지 않고 그대로 묻는다 (유도 질문 방지)
  assert.equal(card.tts, '그때 오른쪽 차로로 진로를 바꾸던 중이었나요? 예 또는 아니요로 답해 주세요.');
  // 고속도로에서는 대피가 먼저 — 질문은 대피 뒤로 미룬다
  const hw = F.SCENARIOS.find((s) => s.id === 'highway_late_signal');
  const hwAsk = await runLocal(hw.frames, { ...hw.ctx, laneChangeConfirmed: undefined, driverStatement: '' });
  const hwCard = hwAsk.flow.cards.find((c) => c.kind === 'abstain');
  assert.ok(hwCard.confirm);
  assert.match(hwCard.tts, /대피한 곳에서 기다리고/);
  assert.doesNotMatch(hwCard.tts, /답해 주세요/); // 대피한 운전자를 차로 다시 부르지 않는다
});

test('"상대 진로변경(A)"은 충돌 직전 내 방향지시등이 켜져 있거나 본인이 진로변경을 말하면 내지 않는다', async () => {
  const sc = F.SCENARIOS.find((s) => s.id === 'other_cutin_unclear');
  const withSignal = sc.frames.map((f) => ({ ...f, turnSignal: f.t >= -2000 ? 'LEFT' : 'NONE' }));
  const a = await runLocal(withSignal, sc.ctx);
  assert.equal(a.metrics.laneChange.status, 'none');
  assert.equal(a.result.status, 'abstain');
  assert.ok(a.result.reasons.some((r) => /방향지시등이 켜져 있어/.test(r)));
  assert.ok(a.facts.vehicle.some((x) => /왼쪽 방향지시등이 켜져 있었음/.test(x)));
  for (const st of ['제가 차선 바꾸는데 옆 차도 들어왔어요', '저희가 들어가는데 옆에서도 동시에 들어오더라고요']) {
    const b = await runLocal(sc.frames, urban(st));
    assert.equal(b.result.status, 'abstain', st);
  }
  const c = await runLocal(sc.frames, confirmed(sc.ctx));
  assert.equal(c.result.status, 'abstain');
});

test('실제 진로변경을 "차로 유지(A)"로 읽지 않는다 — 느린 고속도로 진로변경·차선 안 흔들림·짧은 기록', async () => {
  // (a) 시속 130km, 9초 진로변경을 마친 순간 충돌, 10초 기록 → 기록이 짧아 "유지"로 단정하지 않는다
  const a = F.synthFrames({ speed: 130, laneChange: { startS: -9, durS: 9, dir: 'RIGHT' } });
  const oa = await runLocal(a, highway('옆 차가 들어왔어요'));
  assert.notEqual(oa.result.option === 'c252_me_A' && oa.result.status === 'estimate', true, JSON.stringify(oa.metrics.laneChange));
  // (b) 20초 기록, 시속 120km, 7초 진로변경의 절반에서 충돌, 차선 안 흔들림 ±0.3m (흔들림 위상 8가지)
  for (let ph = 0; ph < 1; ph += 0.125) {
    const v = 120 / 3.6, P = 5, A = Math.atan((0.3 * 2 * Math.PI / P) / v) * 180 / Math.PI;
    const fr = physFrames({ kph: 120, fromS: -20, lc: { startS: -3.5, durS: 7, dir: 'RIGHT' } }).map((f) => {
      const t = f.t / 1000;
      return { ...f, headingDeg: f.headingDeg + A * Math.cos(2 * Math.PI * (t / P + ph)) };
    });
    const ob = await runLocal(fr, highway('옆 차가 들어왔어요'));
    assert.ok(!(ob.result.status === 'estimate' && ob.result.option === 'c252_me_A'), `ph${ph} ${JSON.stringify(ob.metrics.laneChange)}`);
  }
});

test('진로변경 직전의 차선 안 흔들림이 시작점을 앞당겨 신호 거리를 줄이지 않는다', async () => {
  // 시속 36km, 2.9초짜리 빠른 진로변경, 신호는 진로변경 약 43m 전에 켬, 그 앞에 차선 안 흔들림
  const v = 10, P = 4, A = Math.atan((0.12 * 2 * Math.PI / P) / v) * 180 / Math.PI; // 차선 안 ±0.12m 흔들림 (방향 ±1.1°)
  const fr = F.synthFrames({ speed: 36, laneChange: { startS: -2.7, durS: 2.9, dir: 'RIGHT' }, signal: { fromS: -7, dir: 'RIGHT' } }).map((f) => {
    const t = f.t / 1000;
    return { ...f, headingDeg: f.headingDeg + (t < -2.7 ? A * Math.sin(2 * Math.PI * t / P) : 0) };
  });
  const out = await runLocal(fr, confirmed(urban()));
  assert.equal(out.metrics.laneChange.status, 'detected', JSON.stringify(out.metrics.laneChange));
  assert.equal(out.metrics.signal.status, 'ok', JSON.stringify(out.metrics.signal));
  assert.ok(Math.abs(out.metrics.signal.leadM - 43) <= 6, String(out.metrics.signal.leadM)); // 실제 약 43m
  assert.equal(out.result.likely, 70);
});

test('사실 문장: 충돌 직전에 끝난 움직임은 "…까지", 시작 단계는 진로변경으로 단정하지 않는다', async () => {
  const done = F.synthFrames({ speed: 60, laneChange: { startS: -5.4, durS: 3.6, dir: 'RIGHT' }, signal: { fromS: -8, dir: 'RIGHT' } });
  const o1 = await runLocal(done, urban());
  assert.equal(o1.metrics.laneChange.status, 'detected');
  assert.ok(o1.facts.vehicle.some((x) => /초 전부터 .*초 전까지 내 차가 오른쪽으로/.test(x)), o1.facts.vehicle.join(' / '));
  const start = F.synthFrames({ speed: 50, laneChange: { startS: -1.2, durS: 4, dir: 'RIGHT' } });
  const o2 = await runLocal(start, urban());
  assert.equal(o2.metrics.laneChange.status, 'starting');
  assert.ok(!o2.facts.vehicle.some((x) => /진로를 바꿨음/.test(x)), o2.facts.vehicle.join(' / '));
  assert.ok(o2.facts.vehicle.some((x) => /그 움직임 동안 오른쪽 방향지시등은 켜지 않았음/.test(x)));
});

test('도로 유형을 모를 때: 신고 시점에 달리고 있어도 최근 속도로 고속도로 안내, 속도도 모르면 대피 먼저', async () => {
  const moving = await runLocal(F.synthFrames({ speed: 100 }), { trigger: 'voice', driverStatement: '' });
  assert.equal(moving.flow.mode, 'highway');
  const nothing = await runLocal([], { trigger: 'button', driverStatement: '' });
  assert.equal(nothing.flow.mode, 'caution');
  assert.match(nothing.flow.cards[0].title, /먼저 안전한 곳으로/);
  assert.ok(nothing.flow.cards[0].lines.some((l) => /사진보다 대피가 먼저/.test(l)));
  assert.doesNotMatch(JSON.stringify(nothing.flow), /사진부터 찍고/);
});

test('진술 해석 4: 의도·명사형·"적 없어요"·얼버무림·위반한 차 구분', async () => {
  const P = F.parseStatement;
  let c = P('차로 변경하려고 깜빡이만 켜고 기다리는데 옆 차가 들어왔어요');
  assert.deepEqual([c.selfLC, c.otherLC], [false, true]);
  assert.equal(P('끼어들기 단속 구간이었어요').otherLC, false);
  assert.equal(P('진로변경 금지 구간인데 상대가 들어왔어요').selfLC, false);
  c = P('저는 끼어든 적 없어요'); assert.deepEqual([c.selfLC, c.selfDeny], [false, true]);
  assert.equal(P('제가 차선을 바꾼 적은 없어요').selfLC, false);
  assert.equal(P('상대가 깜빡이를 안 켰다고 말하긴 어려워요').otherNoSignal, false);
  assert.equal(P('제 차가 밀려서 옆 차로로 넘어갔어요').selfLC, false);
  assert.equal(P('2차로에서 3차로로 옮기는 중이었어요').selfLC, true);
  c = P('저도 깜빡이 안 켰고 상대도 안 켰어요'); assert.deepEqual([c.selfNoSignal, c.otherNoSignal], [true, true]);
  assert.equal(P('저 차가 안 켰어요 깜빡이').selfNoSignal, false);
  assert.equal(P('버스전용차로로 달리던 택시가 갑자기 나왔어요').busLaneBy, 'other');
  assert.equal(P('실선 넘어서 들어온 건 상대예요').solidLineBy, 'other');
  // 내가 진로를 바꾼 사고에서 "상대가 전용차로·실선 위반"은 내 과실 가산이 아니다
  const sc = F.SCENARIOS.find((s) => s.id === 'proper_signal');
  for (const st of ['버스전용차로로 달리던 택시가 있었는데 제가 차선 바꾸다가 부딪혔어요', '실선 넘어서 들어온 건 상대예요. 저도 차선 바꾸던 중이었어요']) {
    const out = await runLocal(sc.frames, confirmed(urban(st)));
    assert.equal(out.result.status, 'estimate', st);
    assert.ok(out.result.modifiers.every((e) => !['B_buslane', 'B_forbidden'].includes(e.id) || e.decision === 'not_applied'), `${st} ${JSON.stringify(out.result.modifiers)}`);
  }
});

test('진술 해석 5: 남의 말 옮기기·가정·질문·따옴표 인용은 "본인 진로변경 인정"이 아니다', async () => {
  const P = F.parseStatement;
  for (const st of ['상대가 "제가 차선 바꿨어요"라고 했어요', '제가 차선 바꿨으면 제 잘못이겠죠', '제가 들어갔다면 제가 박았겠죠',
    '상대 운전자는 제가 끼어들었다고 우겨요', '제가 끼어들었다고 하는데 아니에요', '경찰이 제가 차선 바꾼 거냐고 물었어요']) {
    assert.equal(P(st).selfLC, false, st);
  }
  assert.equal(P('제가 차선을 바꿨는데 뒤차가 박았어요').selfLC, true);
  assert.equal(P('상대가 자기가 차선 바꿨다고 하더라고요').selfLC, false);
  // 인정이 아니면 기록에 진로변경 움직임이 있어도 숫자 대신 확인 질문
  const sc = F.SCENARIOS.find((s) => s.id === 'me_late_signal');
  const out = await runLocal(sc.frames, urban('상대 운전자는 제가 끼어들었다고 우겨요'));
  assert.equal(out.result.status, 'abstain');
  assert.ok(out.result.confirm);
});

// ---------- 시연 화면 빌드 ----------
test('시연 화면 빌드: 단독 실행용 index.html 과 게시용 조각이 만들어진다', () => {
  const { execFileSync } = require('node:child_process');
  const fs = require('node:fs');
  const path = require('node:path');
  execFileSync(process.execPath, [path.join(__dirname, '..', 'demo', 'build.js')]);
  const html = fs.readFileSync(path.join(__dirname, '..', 'demo', 'index.html'), 'utf8');
  assert.match(html, /^<!doctype html>/i);
  assert.match(html, /<html lang="ko">/);
  assert.match(html, /<meta charset="utf-8">/i);
  assert.match(html, /name="viewport"/);
  const frag = fs.readFileSync(path.join(__dirname, '..', 'demo', 'artifact.html'), 'utf8');
  assert.doesNotMatch(frag, /<!doctype|<html|<head>|<body/i);
  assert.match(frag, /<title>퍼스트30<\/title>/);
});

// ---------- 5차 검토 경계 상황 ----------
// 도로 곡률 + 진로변경 곡률을 함께 넣는 물리 모형 (진로변경 핸들도 속도에 맞게 계산, 100Hz 적분 → 10Hz 프레임)
function physDrive({ kph, fromS = -20, roadK = () => 0, lc = null, signal = null, weave = null }) {
  const v = kph / 3.6;
  const steerOf = (k) => (2.7 * k + (2 * Math.PI / 180 / 9.81) * v * v * k) * 15 * 180 / Math.PI;
  // 차선 안 흔들림: 옆 진폭 A(m), 주기 P(s), 위상 ph — 방향과 핸들을 함께 바꾼다
  const wv = (t) => {
    if (!weave) return [0, 0];
    const w = (2 * Math.PI) / weave.P;
    return [(Math.atan((weave.A * w * Math.cos(w * t + weave.ph)) / v) * 180) / Math.PI, (-weave.A * w * w * Math.sin(w * t + weave.ph)) / (v * v)];
  };
  const lcAt = (t) => {
    if (!lc) return [0, 0];
    const tau = t - lc.startS, D = lc.durS, W = lc.W || 3.5, s = lc.dir === 'RIGHT' ? 1 : -1;
    if (tau <= 0 || tau >= D) return [0, 0];
    const dy = (W / D) * (1 - Math.cos((2 * Math.PI * tau) / D));
    const ay = ((2 * Math.PI * W) / (D * D)) * Math.sin((2 * Math.PI * tau) / D);
    return [(s * Math.atan(dy / v) * 180) / Math.PI, (s * ay) / (v * v)];
  };
  const frames = [];
  let road = 0;
  for (let i = Math.round(fromS * 100); i <= 0; i++) {
    const t = i / 100;
    if (i > Math.round(fromS * 100)) road += (v * roadK(t) * 0.01 * 180) / Math.PI;
    if (i % 10 !== 0) continue;
    const [dev, k] = lcAt(t);
    const [wd, wk] = wv(t);
    frames.push({
      t: Math.round(t * 1000), speedKph: kph, headingDeg: (72 + road + dev + wd + 360) % 360,
      turnSignal: signal && t >= signal.fromS ? signal.dir : 'NONE', steeringDeg: Math.round(steerOf(roadK(t) + k + wk) * 10) / 10,
      brake: false, airbag: false,
    });
  }
  return frames;
}

test('곡선이 끝나는 곳에서 같은 쪽으로 진로를 바꾸던 중 충돌 → "상대 진로변경(A)" 숫자를 내지 않는다 (일정한 곡선의 차로 유지는 그대로 A)', async () => {
  // 오른쪽 곡선이 충돌 3~5초 전에 끝나는데 그때 오른쪽 진로변경이 이어지면 진행 방향은 곡선이 계속되는 것처럼 보인다
  for (const [kph, R, te, D] of [[60, 1500, -3, 8], [75, 1500, -3, 7], [75, 1500, -3, 8], [75, 2000, -3, 8], [75, 2500, -4, 8], [75, 2500, -3, 8], [60, 1000, -3, 7]]) {
    const fr = physDrive({ kph, roadK: rampDown(te - 2, te, 1 / R), lc: { startS: -0.5 * D, durS: D, dir: 'RIGHT' } });
    const out = await runLocal(fr, urban('옆 차가 들어왔어요'));
    assert.ok(!(out.result.status === 'estimate' && out.result.option === 'c252_me_A'), `${kph} R${R} ${te} D${D}: ${JSON.stringify(out.metrics.laneChange)}`);
  }
  const keep = physDrive({ kph: 75, roadK: () => 1 / 1500 });
  const k = await runLocal(keep, keptLane(urban('옆 차가 들어왔어요')));
  assert.deepEqual([k.result.status, k.result.option, k.metrics.road.curveCompensated], ['estimate', 'c252_me_A', true]);
  // 답 전에는 "상대 진로변경"도 숫자 대신 확인 질문 — 정직하게 "예"(진로를 바꾸던 중)라고 답하면 기록과 어긋나 보류
  const ask = await runLocal(keep, urban('옆 차가 들어왔어요'));
  assert.deepEqual([ask.result.status, ask.result.confirm && ask.result.confirm.question], ['abstain', '그때 차로를 바꾸던 중이었나요?']);
  assert.equal((await runLocal(keep, confirmed(urban('옆 차가 들어왔어요')))).result.status, 'abstain');
});

test('도로 모양이 바뀌어 기준이 없으면(road_change) 숫자 없이 보류하고, 사실 문장에 빈 값이 나오지 않는다', async () => {
  const fr = physDrive({ kph: 60, roadK: rampDown(-5, -3, 1 / 1000), lc: { startS: -3.5, durS: 7, dir: 'RIGHT' } });
  for (const st of ['옆 차가 들어왔어요', '제가 차선 바꾸다가 부딪혔어요']) {
    const out = await runLocal(fr, urban(st));
    assert.equal(out.result.status, 'abstain', st);
    if (out.metrics.laneChange.reason === 'road_change') {
      assert.ok(out.facts.vehicle.some((x) => /도로 모양이 바뀌어/.test(x)));
    }
    assert.doesNotMatch(out.facts.vehicle.join(' '), /null|undefined|NaN/);
  }
});

test('에어백 트리거가 늦게 와도 기록상 에어백이 처음 켜진 순간을 충돌 시각으로 본다', async () => {
  // 시속 60km 직진 중 10초 전 충돌·에어백 전개 → 시속 40km로 갓길(오른쪽)로 옮기는 중에 앱이 에어백 상태를 보고 트리거
  const fr = F.synthFrames({ speed: (t) => (t <= -10 ? 60 : 40), fromS: -20, laneChange: { startS: -2.5, durS: 4, dir: 'RIGHT' } })
    .map((f) => ({ ...f, airbag: f.t >= -10000 }));
  const out = await runLocal(fr, { trigger: 'airbag', roadType: 'urban', navCautions: [], driverStatement: '' });
  assert.equal(out.metrics.impact.anchoredMs, -10000);
  assert.equal(out.metrics.impactSpeedKph, 60);
  assert.notEqual(out.metrics.laneChange.status, 'detected'); // 사고 뒤 갓길로 옮긴 움직임을 사고 전 진로변경으로 읽지 않는다
  assert.equal(out.flow.mode, 'emergency');
});

test('봉인은 기록이 없던 값을 null 로 남긴다 (꺼짐·안 밟음으로 채우지 않는다)', async () => {
  const sc = F.SCENARIOS.find((s) => s.id === 'proper_signal');
  const fr = sc.frames.map((f, i) => ({ ...f, turnSignal: null, brake: i % 2 ? null : f.brake, airbag: undefined }));
  const out = await runLocal(fr, sc.ctx);
  assert.equal(out.seal.payload.version, 4);
  const last = out.seal.payload.frames.slice(-2);
  assert.ok(last.every((f) => f.ts === null && f.ab === null));
  assert.ok(last.some((f) => f.b === null) && last.some((f) => f.b === false));
});

test('빠른 도로 안전 안내: 부상·에어백이면 대피 뒤 119, 일반도로라도 시속 80km 이상이면 대피 먼저', async () => {
  const hw = F.SCENARIOS.find((s) => s.id === 'highway_late_signal');
  const em = await runLocal(hw.frames, { ...hw.ctx, injuryReported: true });
  const s = em.flow.cards[0];
  assert.equal(em.flow.mode, 'emergency');
  assert.match(s.lines[0], /대피/);
  assert.ok(s.tts.indexOf('대피') < s.tts.indexOf('119'), s.tts);
  const fast = await runLocal(F.synthFrames({ speed: 100 }), urban('옆 차가 들어왔어요'));
  assert.equal(fast.flow.mode, 'caution');
  assert.match(fast.flow.cards[0].title, /먼저 안전한 곳으로/);
  const ask = await runLocal(hw.frames, { ...hw.ctx, roadType: 'urban', laneChangeConfirmed: undefined, driverStatement: '' });
  const card = ask.flow.cards.find((c) => c.kind === 'abstain');
  assert.ok(card.confirm);
  assert.doesNotMatch(card.tts, /답해 주세요/); // 빠른 도로에서는 질문을 소리 내어 묻지 않는다
  assert.ok(ask.result.evidenceChecklist.some((x) => /대피한 뒤/.test(x)));
});

test('위치 기반 진행 방향이 핸들보다 늦게 반응해도(1.5초, 1Hz) 실제 진로변경을 "상대 진로변경(A)"으로 읽지 않는다', async () => {
  for (const [kph, D, ph] of [[70, 4, 0.65], [110, 6, 0.55], [50, 3, 0.85]]) {
    const base = physDrive({ kph, lc: { startS: -ph * D, durS: D, dir: 'RIGHT' } });
    // 1.5초 늦게, 1초에 한 번만 갱신되는 진행 방향
    let held = null, upd = -Infinity;
    const fr = base.map((f) => {
      const src = base.find((g) => g.t >= f.t - 1500) || base[0];
      if (f.t - upd >= 1000) { held = src.headingDeg; upd = f.t; }
      return { ...f, headingDeg: held };
    });
    const out = await runLocal(fr, urban('옆 차가 들어왔어요'));
    assert.ok(!(out.result.status === 'estimate' && out.result.option === 'c252_me_A'), `${kph} D${D} ${ph}: ${JSON.stringify(out.metrics.laneChange)}`);
  }
});

test('충돌 직전에 시작한 움직임(starting)도 "예"라고 답하면 내 진로변경으로 계산하되, 신호 가산은 범위에만 넣는다', async () => {
  const late = F.synthFrames({ speed: 50, laneChange: { startS: -1.0, durS: 4, dir: 'RIGHT' }, signal: { fromS: -1.5, dir: 'RIGHT' } });
  const ask = await runLocal(late, urban(''));
  assert.equal(ask.metrics.laneChange.status, 'starting');
  assert.equal(ask.result.status, 'abstain');
  assert.match(ask.result.confirm.question, /오른쪽/);
  const yes = await runLocal(late, confirmed(urban('')));
  assert.deepEqual([yes.result.status, yes.result.option], ['estimate', 'c252_me_B']);
  const sig = yes.result.modifiers.find((e) => e.id === 'B_signal');
  assert.equal(sig.decision, 'possible');
  assert.deepEqual([yes.result.low, yes.result.high], [70, 80]);
  // 그쪽 신호를 한 번도 켜지 않았어도 범위에만 (피하려고 꺾은 경우도 신호는 켜지 않으므로)
  const none = F.synthFrames({ speed: 50, laneChange: { startS: -1.0, durS: 4, dir: 'RIGHT' } });
  const y2 = await runLocal(none, confirmed(urban('')));
  assert.equal(y2.result.modifiers.find((e) => e.id === 'B_signal').decision, 'possible');
  assert.deepEqual([y2.result.low, y2.result.high], [70, 80]);
  // 그 무렵 브레이크를 밟았으면(피하려는 조작일 수 있음) 묻지 않고, "예"라고 해도 숫자를 내지 않는다
  const ev = F.synthFrames({ speed: 50, laneChange: { startS: -1.0, durS: 4, dir: 'RIGHT' }, brakeFromS: -1.3 });
  const evAsk = await runLocal(ev, urban(''));
  assert.equal(evAsk.metrics.laneChange.reason, 'evasive');
  assert.equal(evAsk.result.confirm, null);
  assert.equal((await runLocal(ev, confirmed(urban('')))).result.status, 'abstain');
});

test('진술 해석이 틀려도 "내 차 진로변경" 숫자는 나오지 않는다 — 숫자는 확인 질문의 "예"로만', async () => {
  const sc = F.SCENARIOS.find((s) => s.id === 'confirm_needed'); // 기록에 진로변경 같은 움직임, 확인 전
  for (const st of ['제가 넘어갔다니 말도 안 돼요', '차선을 바꿨던 것 같기도 하고 잘 모르겠어요', '아까 전에 차선 바꿨는데 그 뒤로 계속 직진했어요',
    '차선 바꾸라는 안내가 나왔어요', '차선 바꾸지도 않았는데 옆 차가 와서 박았어요', '제가 차선 바꾸다가 부딪혔어요']) {
    const out = await runLocal(sc.frames, urban(st));
    assert.equal(out.result.status, 'abstain', st);
    assert.ok(out.result.confirm, st);
  }
});

// ---------- 6차 검토 경계 상황 ----------
test('진로변경 앞 차선 안 흔들림(방향 선반)이 시작점을 앞당겨 제때 켠 신호를 "늦게 켬"으로 읽지 않는다', async () => {
  // 시속 60km, 5.8초 진로변경의 70% 지점에서 충돌, 신호는 실제 시작 55m 전(기준 30m 충족), 앞에 ±0.25m 흔들림
  const startS = -0.7 * 5.8;
  const fr = physDrive({ kph: 60, lc: { startS, durS: 5.8, dir: 'RIGHT', W: 3.3 }, signal: { fromS: startS - 55 / (60 / 3.6), dir: 'RIGHT' },
    weave: { A: 0.25, P: 8.2, ph: 3.5 } });
  const out = await runLocal(fr, confirmed(urban('')));
  assert.ok(Math.abs(out.metrics.laneChange.startT - startS * 1000) <= 600, String(out.metrics.laneChange.startT));
  assert.equal(out.metrics.signal.status, 'ok');
  assert.equal(out.result.likely, 70);
});

test('시작 무렵에 켠 신호(시작 시점 오차 안)는 "늦게 켬"으로 단정하지 않고 범위로', async () => {
  const fr = F.synthFrames({ speed: 50, laneChange: { startS: -2.2, durS: 4, dir: 'RIGHT' }, signal: { fromS: -2.0, dir: 'RIGHT' } });
  const out = await runLocal(fr, confirmed(urban('')));
  assert.equal(out.metrics.signal.status, 'borderline');
  assert.equal(out.result.modifiers.find((e) => e.id === 'B_signal').decision, 'possible');
  assert.deepEqual([out.result.low, out.result.high], [70, 80]);
});

test('진행 방향이 핸들보다 2초 늦으면 신호 선행 거리 판정 여유에 그 지연을 더한다', async () => {
  // 시속 100km 고속도로, 실제로는 기준(100m)보다 짧은 90m 전에 켰는데 방향 지연 때문에 128m로 재진다 → "충족"으로 단정하지 않는다
  const startS = -0.8 * 6;
  const base = physDrive({ kph: 100, lc: { startS, durS: 6, dir: 'RIGHT' }, signal: { fromS: startS - 90 / (100 / 3.6), dir: 'RIGHT' } });
  const fr = base.map((f) => ({ ...f, headingDeg: (base.find((g) => g.t >= f.t - 2000) || base[0]).headingDeg }));
  const out = await runLocal(fr, confirmed(highway('')));
  assert.ok(out.metrics.quality.headingLagSec >= 1.5, String(out.metrics.quality.headingLagSec));
  assert.notEqual(out.metrics.signal.status, 'ok');
  assert.notEqual(out.result.modifiers.find((e) => e.id === 'B_signal').decision, 'not_applied');
});

test('도로 유형을 모르거나, 음성 신고인데 기록 내내 멈춰 있었으면 "사진 먼저" 안내를 하지 않는다', async () => {
  for (const kph of [30, 60, 79]) {
    const out = await runLocal(F.synthFrames({ speed: kph }), { trigger: 'impact', navCautions: [], driverStatement: '' });
    assert.equal(out.flow.mode, 'caution', String(kph));
    assert.doesNotMatch(JSON.stringify(out.flow), /사진부터 찍고|사진 먼저 찍고/);
  }
  const still = await runLocal(F.synthFrames({ speed: 0, fromS: -20 }), { trigger: 'voice', roadType: 'urban', navCautions: [], driverStatement: '' });
  assert.equal(still.flow.mode, 'caution');
  const fastUrban = await runLocal(F.synthFrames({ speed: 75 }), urban(''));
  assert.equal(fastUrban.flow.mode, 'caution');
  assert.equal((await runLocal(F.synthFrames({ speed: 60 }), urban(''))).flow.mode, 'urban');
  // 속도로만 고속도로라고 가늠했으면 "고속도로예요"라고 단정해 말하지 않는다
  const inferred = await runLocal(F.synthFrames({ speed: 100 }), { trigger: 'impact', navCautions: [], driverStatement: '' });
  assert.equal(inferred.flow.mode, 'highway');
  assert.doesNotMatch(inferred.flow.cards[0].tts, /고속도로예요/);
  assert.match(inferred.flow.cards[4].lines.join(' '), /안전해진 뒤/);
});

test('판정이 "내 차 진로변경"이어도 기록에 방향이 없으면 빈칸 문장 없이 보류한다', () => {
  const m = F.analyzeWindow(F.SCENARIOS.find((s) => s.id === 'other_cutin_unclear').frames, urban());
  for (const answer of [undefined, false]) {
    const r = F.computeFault({ chart: { choice: 'c252_me_B', probabilities: { c252_me_B: 0.9, none: 0.1 } }, evidence_sufficient: { noul: 0.9 } }, m, { laneChangeConfirmed: answer });
    assert.equal(r.status, 'abstain');
    assert.equal(r.confirm, null);
    assert.doesNotMatch(r.reasons.join(' '), /내 차가 으로|그때 {2}|undefined|null/);
  }
});

test('에어백 시각 맞춤은 트리거까지 이어진 에어백 구간으로만, 앞쪽으로만 한다', async () => {
  const fr = F.synthFrames({ speed: 60, fromS: -20, laneChange: { startS: -2.8, durS: 4, dir: 'RIGHT' } });
  const ctx = { trigger: 'airbag', roadType: 'urban', navCautions: [], driverStatement: '' };
  // 동떨어진 짧은 에어백 값(잡음)은 쓰지 않는다
  const spurious = await runLocal(fr.map((f) => ({ ...f, airbag: f.t === -12000 || f.t >= 0 })), ctx);
  assert.equal(spurious.metrics.impact.anchoredMs, undefined);
  assert.equal(spurious.metrics.laneChange.status, 'detected');
  // 트리거 뒤에 켜진 에어백으로 앞당기지 않는다
  const later = await runLocal(fr.map((f) => ({ ...f, airbag: false })).concat([{ t: 600, speedKph: 40, headingDeg: 80, turnSignal: 'NONE', steeringDeg: 0, brake: true, airbag: true }]), ctx);
  assert.equal(later.metrics.impact.anchoredMs, undefined);
  assert.equal(later.metrics.impact.known, true);
  // 기록 첫머리부터 켜져 있었으면 전개 시각이 기록 밖 → 충돌 시각을 모름
  const before = await runLocal(fr.map((f) => ({ ...f, airbag: true })), ctx);
  assert.equal(before.metrics.impact.known, false);
  assert.equal(before.result.status, 'abstain');
  assert.match(before.result.reasons.join(' '), /에어백이 터진 시각이 기록 범위 밖/);
});

test('ctx 가 null 이어도 안전 안내와 봉인은 나오고, 같은 시각 프레임이 섞여 들어와도 지문은 같다', async () => {
  const sc = F.SCENARIOS.find((s) => s.id === 'proper_signal');
  const out = await F.run(sc.frames, null, F.localJudge);
  assert.equal(out.flow.cards.length, 5);
  assert.ok(out.seal && /^[0-9a-f]{64}$/.test(out.seal.hash));
  const dup = [...sc.frames, { ...sc.frames[50], steeringDeg: 3.3 }];
  const m = F.analyzeWindow(dup, sc.ctx);
  const a = await F.sealEvidence(dup, m, { ...sc.ctx, now: fixedNow });
  const b = await F.sealEvidence([...dup].reverse(), m, { ...sc.ctx, now: fixedNow });
  assert.equal(a.hash, b.hash);
  assert.equal(F.analyzeWindow([...dup].reverse(), sc.ctx).laneChange.startT, m.laneChange.startT);
});
