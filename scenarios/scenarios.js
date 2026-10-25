// 퍼스트30 — 시연·테스트용 사고 시나리오 (차량 신호 합성기)
//
// 실제 차량에서는 Pleos Vehicle SDK(방향지시등·핸들·브레이크·에어백), FusedLocation(진행 방향),
// 차량 속성 PERF_VEHICLE_SPEED(속도)를 10Hz 링버퍼(최근 20초)로 모아 이 프레임을 채운다. (신호마다 실제 갱신 주기는 다르다)
// 여기서는 10Hz, 충돌 전 10초(spec.fromS 로 바꿀 수 있음) 버퍼를 물리적으로 그럴듯하게 합성한다.
// ctx.trigger: 'impact'(충격 감지) | 'airbag' 이면 t=0 이 충돌 순간이다. 'voice'·'button' 은 충돌 시각을 모른다.
// ctx.laneChangeConfirmed: 앱의 확인 질문("그때 (오른쪽) 차로로 진로를 바꾸던 중이었나요?")에 대한 답. 숫자는 기록과 답이 같은 이야기를 할 때만 나온다 —
//   기록에 진로변경 같은 움직임 + "예"(true) → 내 차 진로변경, 기록상 차로 유지 + "아니요"(false) → 상대 차 진로변경. 답 전에는 질문만 한다.
'use strict';
(function (F) {
  const HZ = 10;
  const LANE_W = 3.5; // m

  // 부드러운 차로변경 궤적: y(τ) = W(τ/D − sin(2πτ/D)/2π)
  function laneChangeAt(t, { startS, durS, dir }, speedKph) {
    const sign = dir === 'RIGHT' ? 1 : -1;
    const tau = t - startS;
    if (tau <= 0 || tau >= durS) return { headingDev: 0, steer: 0 };
    const v = Math.max(1, speedKph / 3.6);
    const dy = (LANE_W / durS) * (1 - Math.cos((2 * Math.PI * tau) / durS));
    const headingDev = (Math.atan(dy / v) * 180) / Math.PI;
    const steer = 6 * Math.sin((2 * Math.PI * tau) / durS);
    return { headingDev: sign * headingDev, steer: sign * steer };
  }

  function synth(spec) {
    const frames = [];
    const baseHeading = spec.baseHeading == null ? 72 : spec.baseHeading;
    const fromS = spec.fromS == null ? -10 : spec.fromS;
    for (let i = Math.round(fromS * HZ); i <= 0; i++) {
      const t = i / HZ;
      const speed = typeof spec.speed === 'function' ? spec.speed(t) : spec.speed;
      const lc = spec.laneChange ? laneChangeAt(t, spec.laneChange, speed) : { headingDev: 0, steer: 0 };
      const sig = spec.signal && t >= spec.signal.fromS && t <= (spec.signal.toS == null ? 0 : spec.signal.toS) ? spec.signal.dir : 'NONE';
      frames.push({
        t: Math.round(t * 1000),
        speedKph: Math.round(speed * 10) / 10,
        headingDeg: (baseHeading + lc.headingDev + 360) % 360,
        turnSignal: sig,
        steeringDeg: Math.round(lc.steer * 10) / 10,
        brake: !!(spec.brakeFromS != null && t >= spec.brakeFromS),
        airbag: !!(spec.airbag && t >= -0.05),
      });
    }
    return frames;
  }

  const brakingSpeed = (cruise, fromS, decelKphPerS) => (t) => (t < fromS ? cruise : Math.max(0, cruise - (t - fromS) * decelKphPerS));

  const SCENARIOS = [
    {
      id: 'me_late_signal',
      title: '내가 오른쪽 차로로 들어가다 충돌 · 깜빡이를 늦게 켬',
      ctx: { trigger: 'impact', roadType: 'urban', navCautions: [], driverStatement: '오른쪽 차로로 들어가다가 옆 차랑 부딪혔어', laneChangeConfirmed: true },
      spec: { speed: 50, laneChange: { startS: -2.2, durS: 4, dir: 'RIGHT' }, signal: { fromS: -2.6, dir: 'RIGHT' } },
      expect: { status: 'estimate', option: 'c252_me_B', likely: 80, low: 80, high: 80 },
    },
    {
      id: 'confirm_needed',
      title: '진로변경 같은 움직임 · 진술 없음 → 앱이 먼저 확인 질문',
      ctx: { trigger: 'impact', roadType: 'urban', navCautions: [], driverStatement: '' },
      spec: { speed: 60, laneChange: { startS: -2.4, durS: 4.5, dir: 'LEFT' } },
      // 굽은 길·차로 어긋남을 따라간 움직임도 기록이 같아 운전자 확인 전에는 숫자를 내지 않는다. "예" → 신호 없음 +10 → 80%
      expect: { status: 'abstain', confirm: true, confirmed: { status: 'estimate', option: 'c252_me_B', likely: 80, low: 80, high: 80 } },
    },
    {
      id: 'other_cutin_no_signal',
      title: '직진 중 상대가 끼어듦 · "깜빡이 없었다"고 진술',
      ctx: { trigger: 'impact', roadType: 'urban', navCautions: [], driverStatement: '오른쪽 차가 깜빡이도 안 켜고 갑자기 들어왔어', laneChangeConfirmed: false },
      spec: { speed: brakingSpeed(60, -0.8, 20), brakeFromS: -0.8 },
      expect: { status: 'estimate', option: 'c252_me_A', likely: 30, low: 20, high: 30 },
    },
    {
      id: 'other_cutin_unclear',
      title: '직진 중 상대가 끼어듦 · 상대 깜빡이 언급 없음',
      ctx: { trigger: 'impact', roadType: 'urban', navCautions: [], driverStatement: '오른쪽 차가 갑자기 들어왔어', laneChangeConfirmed: false },
      spec: { speed: brakingSpeed(55, -0.6, 18), brakeFromS: -0.6 },
      expect: { status: 'estimate', option: 'c252_me_A', likely: 30, low: 30, high: 30 },
    },
    {
      id: 'simultaneous',
      title: '양쪽에서 같은 차로로 동시에 진로변경',
      ctx: { trigger: 'impact', roadType: 'urban', navCautions: [], driverStatement: '옆 차랑 동시에 같은 차로로 들어가다가 부딪혔어', laneChangeConfirmed: true },
      spec: { speed: 45, laneChange: { startS: -2.2, durS: 4, dir: 'LEFT' }, signal: { fromS: -6, dir: 'LEFT' } },
      // 상대도 동시에 바꿨다는 건 진술뿐 → 252(내 차 B)로 계산하고 252-2(50:50)는 범위의 아래쪽 끝으로만
      expect: { status: 'estimate', option: 'c252_me_B', likely: 70, low: 50, high: 70 },
    },
    {
      id: 'highway_late_signal',
      title: '고속도로 · 100m 기준보다 늦게 깜빡이',
      ctx: { trigger: 'impact', roadType: 'highway', navCautions: [], driverStatement: '2차로로 들어가다 뒤차랑 닿았어', laneChangeConfirmed: true },
      spec: { speed: 100, laneChange: { startS: -2.2, durS: 4, dir: 'RIGHT' }, signal: { fromS: -4.4, dir: 'RIGHT' } },
      expect: { status: 'estimate', option: 'c252_me_B', likely: 80, low: 80, high: 80, mode: 'highway' },
    },
    {
      id: 'proper_signal',
      title: '깜빡이를 충분히 일찍 켜고 진로변경',
      ctx: { trigger: 'impact', roadType: 'urban', navCautions: [], driverStatement: '차선 바꾸는데 뒤에서 오던 차랑 부딪혔어', laneChangeConfirmed: true },
      spec: { speed: 40, laneChange: { startS: -2.2, durS: 4, dir: 'RIGHT' }, signal: { fromS: -7, dir: 'RIGHT' } },
      expect: { status: 'estimate', option: 'c252_me_B', likely: 70, low: 70, high: 70 },
    },
    {
      id: 'solid_line',
      title: '실선 구간에서 진로변경',
      ctx: { trigger: 'impact', roadType: 'urban', navCautions: ['LANE_CHANGE_CAMERA_ALERT'], driverStatement: '실선 구간이었는데 들어가다가 박았어', laneChangeConfirmed: true },
      spec: { speed: 50, laneChange: { startS: -2.2, durS: 4, dir: 'RIGHT' }, signal: { fromS: -6, dir: 'RIGHT' } },
      expect: { status: 'estimate', option: 'c252_me_B', likely: 70, low: 70, high: 90 },
    },
    {
      id: 'airbag',
      title: '에어백 전개 · 앞차가 갑자기 들어옴',
      ctx: { trigger: 'airbag', roadType: 'urban', navCautions: [], driverStatement: '앞에 차가 갑자기 들어와서 박았어', laneChangeConfirmed: false },
      spec: { speed: brakingSpeed(45, -1.2, 22), brakeFromS: -1.2, airbag: true },
      expect: { status: 'estimate', option: 'c252_me_A', likely: 30, low: 30, high: 30, mode: 'emergency' },
    },
    {
      id: 'voice_report',
      title: '음성으로 신고 · 충돌 순간을 차가 감지하지 못함',
      ctx: { trigger: 'voice', roadType: 'urban', navCautions: [], driverStatement: '오른쪽 차로로 들어가다가 옆 차랑 부딪혔어' },
      spec: { speed: 50, laneChange: { startS: -2.2, durS: 4, dir: 'RIGHT' }, signal: { fromS: -2.6, dir: 'RIGHT' } },
      // 충돌 시각을 모르면 기록 속 움직임이 사고 전인지 뒤인지 가릴 수 없다 → 숫자 없이 기록 봉인과 증거 안내만
      expect: { status: 'abstain' },
    },
    {
      id: 'unclear_slow',
      title: '정체 중 저속 접촉 · 진술 없음',
      ctx: { trigger: 'impact', roadType: 'urban', navCautions: [], driverStatement: '' },
      spec: { speed: 5 },
      expect: { status: 'abstain' },
    },
    {
      id: 'rear_end',
      title: '신호 대기 중 뒤차 추돌 (차로변경 사고 아님)',
      ctx: { trigger: 'impact', roadType: 'urban', navCautions: [], driverStatement: '신호 대기 중에 뒷차가 박았어' },
      spec: { speed: 0, brakeFromS: -10 },
      expect: { status: 'abstain' },
    },
  ];

  for (const s of SCENARIOS) s.frames = synth(s.spec);

  F.SCENARIOS = SCENARIOS;
  F.synthFrames = synth;
})(typeof module !== 'undefined' && typeof require === 'function' ? require('../engine/ns.js') : (globalThis.First30 = globalThis.First30 || {}));
