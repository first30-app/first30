// 퍼스트30 — 사고 직후 30초 엔진
//
// 역할 분담 (TypeSafe 문서의 "code owns the simulation, Jev owns the evaluation" 원칙을 따른다)
//   1) 코드: 차량 신호를 측정·계산한다 (차로변경 움직임, 방향지시등 선행 거리, 브레이크, 충돌 속도).
//   2) 코드: 측정값을 사람이 읽는 "사실 문장"으로 바꾼다 (원시 숫자를 그대로 모델에 넣지 않는다).
//   3) Jev: 사실 문장 + 운전자 진술을 보고 사고 유형(Choice)과 해석이 필요한 요소(Noul)만 판단한다.
//   4) 코드: 기본과실 ± 수정요소를 계산하고, 차량 기록이 유형을 뒷받침하지 못하거나 확신이 낮으면
//      숫자 대신 "판단 보류"를 낸다.
//
// 숫자를 내는 조건 (하나라도 빠지면 판단 보류)
//   - 충돌 순간을 차가 직접 감지했다 (trigger 'airbag' | 'impact'). 음성·버튼 신고는 충돌 시각을 모른다.
//   - 차량 기록이 사고 유형을 뒷받침한다 (recordConflict).
//   - "내 차 진로변경(B)"은 기록에 보인 방향을 넣은 확인 질문("그때 오른쪽 차로로 진로를 바꾸던 중이었나요?")에
//     운전자가 "예"라고 답해야 한다(ctx.laneChangeConfirmed === true). 굽은 길·차로가 어긋난 곳을 따라간 움직임은
//     진행 방향·핸들 기록만으로는 진로변경과 똑같이 보이고, 진술 문장은 부정·질문·남의 말을 잘못 읽을 수 있기 때문이다.
//   - "상대 진로변경(A)"은 기록이 차로 유지를 보이고, 방향지시등 기록이 있으며 충돌 직전 3초 안에 켜져 있지 않고,
//     운전자가 자기 진로변경을 말하거나 "예"라고 답하지 않아야 한다.
//
// 좌표·부호 약속
//   headingDeg : 진북 기준 시계 방향 0~360 (FusedLocation heading 과 같은 기준)
//   steeringDeg: 오른쪽(시계 방향)이 + (차량마다 부호가 다를 수 있어 어댑터에서 맞춘다)
//   t          : 충돌 시점(음성·버튼이면 신고 시점)이 0 인 밀리초. 그 전 프레임은 음수.
'use strict';
(function (F) {
  const THRESH = Object.freeze({
    YES: 0.8,              // Noul 이 이 이상이면 수정요소 적용 (진술 외 근거가 있을 때만)
    MAYBE: 0.4,            // 이 이상이면 범위에만 반영
    CHART_MIN: 0.6,        // 사고 유형 선택 확률이 이보다 낮으면 판단 보류
    EVIDENCE_MIN: 0.4,     // "근거 충분" Noul 이 이보다 낮으면 판단 보류
    ALT_MIN: 0.25,         // 2순위 해석을 함께 보여줄 최소 확률
    SELF_LC: 0.6,          // 진술에서 자기 진로변경을 말함(Noul) — "상대 진로변경(A)" 숫자를 막는 데만 쓴다

    LANE_KEEP_LAT_M: 0.5,  // 최근 LOCAL_SEC 동안 측면 이동이 이보다 작고
    LANE_KEEP_DEG: 1,      // 진행 방향 변화도 이보다 작으면 "차로 유지"
    LOCAL_SEC: 8,          //   차로 유지 판정에 보는 충돌 전 구간
    LANE_LAT_M: 0.9,       // 차로변경으로 볼 최소 측면 이동
    LANE_LAT_MAX_M: 4.5,   // 차로 하나(약 3~3.5m)+여유. 넘으면 단순 차로변경으로 단정하지 않는다
    LANE_DEG: 1,           // 차로변경으로 볼 최소 진행 방향 변화
    STEER_DEV_DEG: 2,      // 기준 조향각 대비 이만큼 이상 같은 쪽으로 (차선 안 보정 조향 1~1.5°보다 크게)
    STEER_RUN_MS: 500,     //   이 시간 이상 이어지면 핸들 조작으로 본다
    STEER_START_DEG: 0.5,  // 시작점을 핸들 조작 쪽으로 당길 때 쓰는 작은 문턱 (최대 1.5초)
    STEER_RETURN_RATIO: 0.5, // 충돌 직전 조향이 최대 조향의 이 비율 아래로 돌아와 있어야 진로변경 움직임(곡선 진입과 구분)
    HOLD_MIN_MS: 600,      // 굽은 길: 핸들이 가운데로 돌아온 채 진행 방향이 새 값에 머문 시간이
    HOLD_RATIO: 0.25,      //   max(0.6초, 방향이 바뀐 시간 × 0.25) 이상이면 굽은 길을 따라간 움직임으로 본다
    EVASIVE_SEC: 2.5,      // 충돌 2.5초 안에 시작한 조향 + 그 무렵(1.5초 전부터) 브레이크 → 회피일 수 있어 단정하지 않는다
    MIN_BUFFER_SEC: 8,     // 충돌 전 기록이 이보다 짧으면 차로변경을 측정하지 않는다
    FAST_KPH: 80,          // 이 속도 이상이면 느린 진로변경(최대 9초 안팎)을 고려한다
    MIN_BUFFER_FAST_SEC: 14, //   빠를 때 "차로 유지"로 단정하려면 이만큼의 기록이 필요하다
    GUARD_SEC: 6,          // 도로 방향 기준은 충돌 직전 이 시간(빠르면 GUARD_SEC_FAST)을 뺀 앞부분에서만 잡는다
    GUARD_SEC_FAST: 9,
    MIN_SPEED_KPH: 8,      // 진로변경 구간 평균 속도가 이보다 느리면 측정하지 않는다
    ACTIVE_SEC: 2,         // 충돌 전 이 시간 안에 진로변경이 진행 중이어야 진로변경 사고로 본다
    STARTING_SEC: 1.2,     // 충돌 직전 이 시간 안에 시작된 조작은 진로변경 시작인지 회피인지 구분하지 않는다
    REF_SEC: 4,            // 도로 방향(기준 방향) 추정에 쓰는 조용한 구간 길이
    REF_STEER_SPREAD_DEG: 10, // 기준 구간 안 핸들 움직임 허용 폭 (차선 안 보정 조향은 저속에서 크다)
    CURVE_MIN_SEC: 6,      // 곡선 보정은 이 시간 이상 일정하게 돌고 있었을 때만
    REF_NOISE_DEG: 1,      // 기준 구간의 방향 흔들림이 이보다 크면 방향 기록을 믿지 않는다
    REF_TRIM_DEG: 0.8,     // 직선 기준 방향: 중앙값 ±0.8° 안의 값만 평균 (차선 안 흔들림은 평균으로, 다른 움직임은 빼고)
    YAW_MIN_DEG_S: 0.3,    // 기준 구간 회전 속도가 이 이상이고 통계적으로 뚜렷하면 곡선 도로로 보고 빼 준다
    MISSING_MAX: 0.1,      // 속도·방향 결측 비율이 이보다 크면 측정 불가
    SIGNAL_GAP_MS: 1000,   // 방향지시등 깜빡임 사이 꺼짐이 이 이하이면 켜진 것으로 본다
    SIGNAL_MARGIN_SEC: 0.3,// 신호 선행 거리 경계 판정 여유 = 속도 × 0.3초 (시작 시점 추정 오차)
    SIGNAL_MARGIN_MIN_M: 3,
    RECENT_SIGNAL_SEC: 3,  // 충돌 전 이 시간 안에 방향지시등이 켜져 있었으면 "차로 유지(A)"로 단정하지 않는다
    LAG_MAX_SEC: 1.3,      // 진행 방향이 핸들보다 이만큼 이상 늦게 반응하면(위치 기반 방향의 지연) 충돌 직전 움직임이 방향 기록에 아직 안 보인다
    AIRBAG_SYNC_MS: 500,   // 에어백 전개 프레임이 t=0 에서 이보다 멀면 전개 시각을 충돌 시각으로 다시 맞춘다
    STEER_ACT_DEG: 1.0,    // "차로 유지"로 보여도 핸들이 도로를 따라갈 때의 각도에서 이만큼 넘게(곧은 기준)
    STEER_ACT_CURVE_DEG: 0.8, //   (곡선 기준이면 이만큼 넘게, 또는 평소 흔들림의 2.5배 넘게)
    STEER_ACT_MS: 500,     //   이 시간 넘게 벗어나 있었으면 단정하지 않는다 (곡선이 끝나는 곳의 같은 쪽 진로변경 등)
    PHOTO_MAX_KPH: 65,     // 안전 안내: 일반도로라도 최근 최고 속도가 이보다 빠르면 "사진보다 대피 먼저"(caution) 안내
    STILL_KPH: 10,         //   음성·버튼 신고인데 기록 내내 이보다 느리면(사고 뒤 멈춰 있던 기록) 그 속도로 도로를 가늠하지 않는다
    AIRBAG_MAX_SHIFT_MS: 15000, // 에어백 시각 맞춤은 트리거보다 앞으로만, 이 시간 안에서만 한다
  });
  // 충돌 순간을 차가 직접 감지한 트리거만 믿는다. 급감속('decel')·음성·버튼·그 밖의 값은 충돌 시각을 모른다.
  const IMPACT_TRIGGERS = Object.freeze(['airbag', 'impact']);

  // ---------- 수학 유틸 ----------
  const rad = (d) => (d * Math.PI) / 180;
  const deg = (r) => (r * 180) / Math.PI;
  function angDiff(a, b) { // a - b 를 -180~180 으로
    let d = ((a - b) % 360 + 540) % 360 - 180;
    if (d === -180) d = 180;
    return d;
  }
  function circularMean(degs) {
    let s = 0, c = 0;
    for (const d of degs) { s += Math.sin(rad(d)); c += Math.cos(rad(d)); }
    return (deg(Math.atan2(s, c)) + 360) % 360;
  }
  const round = (x, n = 0) => { const p = 10 ** n; return Math.round(x * p) / p; };
  const sec = (ms) => round(Math.abs(ms) / 1000, 1);
  const num = (x) => (typeof x === 'number' && Number.isFinite(x) ? x : null);
  const mean = (a) => (a.length ? a.reduce((s, x) => s + x, 0) / a.length : 0);
  const median = (a) => { if (!a.length) return 0; const s = [...a].sort((x, y) => x - y); const k = s.length >> 1; return s.length % 2 ? s[k] : (s[k - 1] + s[k]) / 2; };
  // 중앙값 둘레(±half)의 값만 평균한다 — 차선 안 흔들림은 고르게 평균하고, 앞선 진로변경 같은 큰 치우침은 뺀다.
  // half 는 값의 퍼짐(MAD 기반 표준편차)의 2배를 0.3~0.8° 안에서 쓴다 (흔들림이 작으면 작은 치우침도 뺀다)
  function robustCenter(values, half = null) {
    if (!values.length) return 0;
    let c = median(values);
    if (half == null) {
      const sigma = 1.4826 * median(values.map((x) => Math.abs(x - c)));
      half = Math.max(0.3, Math.min(THRESH.REF_TRIM_DEG, 2 * sigma));
    }
    for (let it = 0; it < 3; it++) {
      const near = values.filter((x) => Math.abs(x - c) <= half);
      if (!near.length) break;
      c = mean(near);
    }
    return c;
  }

  // ---------- 0. 입력 정리 ----------
  // 방향지시등 값: 알아볼 수 있는 문자열만 쓴다. 숫자 enum·null 은 "알 수 없음"(어댑터에서 문자열로 바꿔 넣는다).
  function normSignal(v) {
    if (typeof v !== 'string') return null;
    const s = v.trim().toUpperCase();
    if (s === 'LEFT' || s === 'L') return 'LEFT';
    if (s === 'RIGHT' || s === 'R') return 'RIGHT';
    if (s === 'NONE' || s === 'OFF') return 'NONE';
    if (s === 'BOTH' || s === 'HAZARD' || s === 'EMERGENCY') return 'HAZARD';
    return null;
  }

  // 결측값은 앞뒤 유효값으로 보간한다 (방향은 각도 차이로). 양 끝은 가까운 유효값으로 채운다.
  function interpolate(arr, key, angular) {
    const valid = [];
    arr.forEach((f, i) => { if (f[key] != null) valid.push(i); });
    if (!valid.length) return false;
    for (let i = 0; i < arr.length; i++) {
      if (arr[i][key] != null) continue;
      const prevI = valid.filter((j) => j < i).pop();
      const nextI = valid.find((j) => j > i);
      if (prevI == null) arr[i][key] = arr[nextI][key];
      else if (nextI == null) arr[i][key] = arr[prevI][key];
      else {
        const a = arr[prevI], b = arr[nextI];
        const w = (arr[i].t - a.t) / (b.t - a.t);
        arr[i][key] = angular ? (a[key] + w * angDiff(b[key], a[key]) + 360) % 360 : a[key] + w * (b[key] - a[key]);
      }
    }
    return true;
  }
  // 상태값(방향지시등·브레이크) 결측은 직전 값을 유지한다
  function holdLast(arr, key, fallback) {
    let lastV = null;
    for (const f of arr) { if (f[key] == null) f[key] = lastV == null ? fallback : lastV; else lastV = f[key]; }
  }

  // 깜빡임(램프 켜짐/꺼짐) 단위로 들어온 방향지시등을 한 구간으로 잇는다
  function bridgeSignal(pre) {
    for (const dir of ['LEFT', 'RIGHT']) {
      let lastOn = -1;
      for (let i = 0; i < pre.length; i++) {
        if (pre[i].turnSignal !== dir) continue;
        if (lastOn >= 0 && i - lastOn > 1 && pre[i].t - pre[lastOn].t <= THRESH.SIGNAL_GAP_MS + 100) {
          const between = pre.slice(lastOn + 1, i);
          if (between.every((f) => f.turnSignal === 'NONE')) between.forEach((f) => { f.turnSignal = dir; f.bridged = true; });
        }
        lastOn = i;
      }
    }
  }

  // 같은 시각의 프레임이 여러 개면 내용으로 순서를 정해, 들어온 순서와 관계없이 같은 결과가 나오게 한다
  const byTimeThenContent = (a, b) => (a.t - b.t) || (stableStringify(a) < stableStringify(b) ? -1 : stableStringify(a) > stableStringify(b) ? 1 : 0);
  function prepare(frames) {
    const raw = (Array.isArray(frames) ? frames : []).filter((f) => f && num(f.t) != null);
    raw.sort(byTimeThenContent);
    const dedup = [];
    for (const f of raw) { if (dedup.length && dedup[dedup.length - 1].t === f.t) dedup.pop(); dedup.push(f); }
    const all = dedup.map((f) => ({
      t: f.t,
      speedKph: num(f.speedKph) == null ? null : Math.max(0, f.speedKph),
      headingDeg: num(f.headingDeg) == null ? null : ((f.headingDeg % 360) + 360) % 360,
      steeringDeg: num(f.steeringDeg),
      turnSignal: normSignal(f.turnSignal),
      brake: typeof f.brake === 'boolean' ? f.brake : null,
      airbag: f.airbag === true,
    }));
    const n = all.length || 1;
    const ratio = (key) => all.filter((f) => f[key] != null).length / n;
    let maxGapMs = 0;
    for (let i = 1; i < all.length; i++) maxGapMs = Math.max(maxGapMs, all[i].t - all[i - 1].t);
    // 방향 값이 실제로 갱신되는 간격 (1Hz 위치 갱신을 10Hz로 채우면 같은 값이 이어진다).
    // 값이 바뀐 시각들 사이 간격의 중앙값 — 일정하게 직진한 긴 구간(3초 넘는 간격)은 빼고 본다.
    const changeTs = [];
    for (let i = 1; i < all.length; i++) if (all[i].headingDeg != null && all[i - 1].headingDeg != null && all[i].headingDeg !== all[i - 1].headingDeg) changeTs.push(all[i].t);
    const gaps = changeTs.slice(1).map((t, i) => t - changeTs[i]).filter((g) => g <= 3000);
    const quality = {
      frames: all.length,
      headingUpdateMs: gaps.length ? Math.round(median(gaps)) : null,
      speedMissing: round(1 - ratio('speedKph'), 3),
      headingMissing: round(1 - ratio('headingDeg'), 3),
      steeringAvailable: ratio('steeringDeg') >= 1 - THRESH.MISSING_MAX,
      signalAvailable: ratio('turnSignal') >= 1 - THRESH.MISSING_MAX,
      brakeAvailable: ratio('brake') >= 1 - THRESH.MISSING_MAX,
      maxGapMs,
    };
    const hasSpeed = interpolate(all, 'speedKph', false);
    const hasHeading = interpolate(all, 'headingDeg', true);
    if (quality.steeringAvailable) interpolate(all, 'steeringDeg', false);
    quality.hasSpeed = hasSpeed;
    if (!hasSpeed) all.forEach((f) => { f.speedKph = 0; });
    holdLast(all, 'turnSignal', 'NONE');
    holdLast(all, 'brake', false);
    quality.hasHeading = hasHeading;
    bridgeSignal(all);
    return { pre: all, quality };
  }

  // 충돌 시각: 에어백 전개·충격 감지 트리거면 t=0 이 충돌 순간이다.
  // 음성("사고 났어")·버튼·급감속·그 밖의 값은 충돌 뒤(또는 충돌과 무관하게) 들어오므로 충돌 시각을 모른다고 본다.
  function impactOf(ctx = {}) {
    const src = typeof ctx.trigger === 'string' ? ctx.trigger.trim().toLowerCase() : '';
    return { source: src || 'unknown', known: IMPACT_TRIGGERS.includes(src) };
  }

  // 속도 맥락 (안전 안내용): 최근 4초 평균, 버퍼 안 최고 속도(1초 이동평균)
  function speedContext(pre, quality) {
    if (!pre.length || (quality && quality.hasSpeed === false)) return { recentSpeedKph: null, peakSpeedKph: null, endSpeedKph: null };
    const last = pre[pre.length - 1];
    const recent = mean(pre.filter((f) => f.t >= last.t - 4000).map((f) => f.speedKph));
    let peak = 0;
    for (let i = 0, a = 0; i < pre.length; i++) {
      while (pre[i].t - pre[a].t > 1000) a++;
      peak = Math.max(peak, mean(pre.slice(a, i + 1).map((f) => f.speedKph)));
    }
    return { recentSpeedKph: round(recent, 0), peakSpeedKph: round(peak, 0), endSpeedKph: round(last.speedKph, 0) };
  }

  // ---------- 1. 측정 ----------
  function fitWindow(ref, t0) {
    const h0 = ref[0].headingDeg;
    const xs = ref.map((f) => (f.t - t0) / 1000);
    const ys = ref.map((f) => angDiff(f.headingDeg, h0));
    const mx = mean(xs), my = mean(ys);
    let sxx = 0, sxy = 0;
    xs.forEach((x, i) => { sxx += (x - mx) ** 2; sxy += (x - mx) * (ys[i] - my); });
    const slope = sxx > 0 ? sxy / sxx : 0;
    const resid = ys.map((y, i) => y - (my + slope * (xs[i] - mx)));
    const sd = Math.sqrt(resid.reduce((s, r) => s + r * r, 0) / Math.max(1, ys.length - 2));
    const maxResid = Math.max(...resid.map(Math.abs));
    const steers = ref.map((f) => f.steeringDeg).filter((x) => x != null);
    const steerSpread = steers.length ? Math.max(...steers) - Math.min(...steers) : 0;
    return { h0, mx, my, sxx, slope, sd, maxResid, steers, steerSpread, lenMs: ref[ref.length - 1].t - ref[0].t };
  }

  // 도로 방향: 충돌 직전 guard 구간을 뺀 앞부분(기준 구간)에서만 잡는다 — 충돌 무렵의 움직임이 기준에 섞이지 않도록.
  // 기준 구간 앞에서부터 REF_SEC 초 구간을 0.5초씩 밀며 방향 흔들림과 핸들 움직임이 작은 첫 구간을 찾고,
  //  - 곡선: 그 구간을 늘려도 직선 회전에서 벗어나지 않고(6초 이상), 회전이 뚜렷하고, 핸들도 같은 쪽으로 꺾여 있고,
  //          앞뒤 절반이 같은 회전·핸들이고, **충돌 2초 전까지 곡선이 이어질 때만** 그 회전을 연장한다.
  //          곡선이 중간에 끝났으면(도로가 펴짐, 또는 곡선 위의 움직임) 곡선이 끝난 뒤의 곧은 구간에서 기준을 다시 찾고,
  //          없으면 도로 모양이 바뀐 것으로 보고(roadChange) 판단하지 않는다.
  //  - 직선: 그 구간을 기준 구간 끝까지 늘리다 한쪽 치우침이 시작되면 멈추고, 늘린 구간의 방향을 중앙값 둘레만 평균(robustCenter)
  // 기준 구간에 조용한 구간이 없으면 그 뒤에서 찾되 "차로 유지"는 단정하지 않고(lateRef), 어디에도 없으면 기준을 믿지 않는다(quiet=false).
  function roadBaseline(pre, guardMs = 0) {
    const t0 = pre[0].t, tEnd = pre[pre.length - 1].t;
    const len = THRESH.REF_SEC * 1000;
    const guard = Math.max(0, Math.min(guardMs, tEnd - t0 - len));
    const refEnd = tEnd - guard;
    const curveEnd = tEnd - Math.min(guard, 2000);
    // 굽은 길을 도는 중인 구간: 회전이 뚜렷하고(구간 동안 1° 이상) 핸들도 같은 쪽으로 꺾여 있다 (차선 안 흔들림은 핸들 평균이 0 근처)
    const isTurning = (w) => {
      const m = w.steers.length ? median(w.steers) : 0;
      return Math.abs(w.slope) >= THRESH.YAW_MIN_DEG_S && Math.abs(w.slope) * (w.lenMs / 1000) >= 1 && Math.abs(m) >= 0.3 && Math.sign(m) === Math.sign(w.slope);
    };
    const findQuiet = (from, to, flatOnly = false) => {
      for (let ws = from; ws + len <= to + 1; ws += 500) {
        const ref = pre.filter((f) => f.t >= ws && f.t <= ws + len);
        if (ref.length < 3) continue;
        const w = fitWindow(ref, t0);
        if (w.sd <= THRESH.REF_NOISE_DEG && w.maxResid <= 2 * THRESH.REF_NOISE_DEG && w.steerSpread <= THRESH.REF_STEER_SPREAD_DEG && (!flatOnly || !isTurning(w))) {
          return { ...w, ws, we: ws + len, quiet: true };
        }
      }
      return null;
    };
    // 곡선 맞춤은 직선 회전에서 벗어나면 저절로 멈추므로 충돌 2초 전까지 늘려 본다
    const tryCurve = (pick) => {
      let grown = pick;
      for (let we = pick.we + 500; we <= curveEnd; we += 500) {
        const ref = pre.filter((f) => f.t >= pick.ws && f.t <= we);
        const w = fitWindow(ref, t0);
        if (w.maxResid > 0.6 || w.steerSpread > 3) break;
        grown = { ...w, ws: pick.ws, we, quiet: true };
      }
      const se = grown.sxx > 0 ? grown.sd / Math.sqrt(grown.sxx) : Infinity;
      const steerMed = grown.steers.length ? median(grown.steers) : null;
      // 곡선은 회전 속도와 핸들 각도가 구간 내내 일정하다. 앞뒤 절반의 회전 속도·핸들 중앙값이 다르면
      // (예: 느린 진로변경의 앞쪽 절반) 곡선으로 보지 않는다.
      const steady = (() => {
        if (grown.lenMs < THRESH.CURVE_MIN_SEC * 1000 - 1) return false;
        const mid = grown.ws + (grown.we - grown.ws) / 2;
        const h1 = pre.filter((f) => f.t >= grown.ws && f.t <= mid), h2 = pre.filter((f) => f.t > mid && f.t <= grown.we);
        if (h1.length < 3 || h2.length < 3) return false;
        const s1 = fitWindow(h1, t0).slope, s2 = fitWindow(h2, t0).slope;
        const m1 = median(h1.map((f) => f.steeringDeg).filter((x) => x != null)), m2 = median(h2.map((f) => f.steeringDeg).filter((x) => x != null));
        return Math.abs(s1 - s2) <= Math.max(0.15, 0.35 * Math.abs(grown.slope)) &&
          Math.abs(m1 - m2) <= Math.max(0.5, 0.4 * Math.abs(steerMed || 0));
      })();
      const curved = steady &&
        Math.abs(grown.slope) >= THRESH.YAW_MIN_DEG_S && Math.abs(grown.slope) >= 3 * se &&
        steerMed != null && Math.sign(steerMed) === Math.sign(grown.slope) && Math.abs(steerMed) >= 0.3;
      return { grown, curved, reached: grown.we >= curveEnd - 500 };
    };
    // 직선: 조용한 구간을 endT 까지 늘리되, 1초 평균한 방향이 지금까지의 기준(중앙값 둘레 평균)에서
    // REF_TRIM_DEG 넘게 벗어나거나 1초 평균한 핸들이 중앙값에서 3° 넘게 벗어나면 멈춘다.
    // 차선 안 흔들림은 통과해 길게 평균하고, 느린 진로변경 같은 한쪽 치우침이 시작되면 거기서 멈춘다.
    const flatFrom = (pick, endT, extra) => {
      const ref = pre.filter((f) => f.t >= pick.ws && f.t <= Math.max(endT, pick.we));
      const devs = ref.map((f) => angDiff(f.headingDeg, pick.h0));
      const sm = (vals, i) => {
        let a = i, b = i;
        while (a > 0 && ref[i].t - ref[a - 1].t <= 500) a--;
        while (b < ref.length - 1 && ref[b + 1].t - ref[i].t <= 500) b++;
        const xs = vals.slice(a, b + 1).filter((x) => x != null);
        return xs.length ? mean(xs) : null;
      };
      const steerVals = ref.map((f) => f.steeringDeg);
      const devS = devs.map((_, i) => sm(devs, i));
      const steerS = steerVals.map((_, i) => sm(steerVals, i));
      let n = ref.filter((f) => f.t <= pick.we).length;
      let c = robustCenter(devs.slice(0, n));
      while (n < ref.length) {
        const steerMed = median(steerVals.slice(0, n).filter((x) => x != null));
        const k = n;
        if (Math.abs(devS[k] - c) > THRESH.REF_TRIM_DEG || (steerS[k] != null && Math.abs(steerS[k] - steerMed) > 3)) break;
        n++;
        if (n % 5 === 0 || n === ref.length) c = robustCenter(devs.slice(0, n));
      }
      c = robustCenter(devs.slice(0, n));
      const steers = steerVals.slice(0, n).filter((x) => x != null);
      const h = (pick.h0 + c + 360) % 360;
      return {
        at: () => h, yawRateDegS: 0, curved: false, noiseDeg: round(pick.sd, 2), quiet: true,
        refStartT: pick.ws, refEndT: ref[n - 1].t, guardMs: guard, steerBase: steers.length ? median(steers) : 0, ...extra,
      };
    };

    let pick = findQuiet(t0, refEnd);
    // 기준 구간 전체가 움직임(앞선 진로변경 등)으로 차 있으면, 그 뒤의 조용한 구간을 기준으로 쓴다.
    // 이때는 기준이 충돌 무렵에 있으므로 "차로 유지"는 단정하지 않는다(lateRef).
    let lateRef = false;
    if (!pick) {
      pick = findQuiet(refEnd - len + 500, tEnd);
      if (pick) lateRef = true;
    }
    if (!pick) {
      const ref = pre.filter((f) => f.t <= refEnd);
      return { ...baselineFrom({ ...fitWindow(ref.length >= 3 ? ref : pre, t0), ws: t0, we: refEnd, quiet: false }, t0, false), guardMs: guard };
    }
    const flatEnd = (p) => (lateRef ? p.we : refEnd);
    const cv = tryCurve(pick);
    if (cv.curved && cv.reached) return { ...baselineFrom(cv.grown, t0, true), guardMs: guard, lateRef };
    if (cv.curved || isTurning(pick)) {
      // 굽은 길이 기준 구간 안에서 끝났다 → 곡선이 끝난 뒤의 곧은 구간에서 기준을 다시 찾는다
      const after = cv.curved ? cv.grown.we : pick.we;
      const pick2 = findQuiet(after, lateRef ? tEnd : refEnd, true);
      if (pick2) return flatFrom(pick2, flatEnd(pick2), { lateRef, afterCurve: true });
      // 곧은 기준 구간이 없다: 충돌 무렵까지 도로 모양이 바뀌고 있었다 (곡선이 끝나는 중이거나 곡선 위의 움직임)
      return { ...flatFrom(pick, flatEnd(pick), { lateRef }), roadChange: true };
    }
    return flatFrom(pick, flatEnd(pick), { lateRef });
  }

  function baselineFrom(w, t0, curved) {
    const { h0, mx, my, steers } = w;
    const slope = curved ? w.slope : 0;
    return {
      at: (t) => (h0 + my + slope * ((t - t0) / 1000 - mx) + 360) % 360,
      yawRateDegS: round(slope, 2),
      curved: !!curved,
      noiseDeg: round(w.sd, 2),
      quiet: !!w.quiet,
      refStartT: w.ws,
      refEndT: w.we,
      steerBase: steers.length ? median(steers) : 0,
    };
  }

  // 버퍼 전체 방향의 중앙값 둘레 평균을 기준으로 쓰는 대안 (판정 민감도 확인용)
  function wholeBufferBaseline(pre, base) {
    const h0 = pre[0].headingDeg;
    const c = (h0 + robustCenter(pre.map((f) => angDiff(f.headingDeg, h0))) + 360) % 360;
    return { ...base, at: () => c, curved: false, refStartT: pre[0].t, refEndT: pre[pre.length - 1].t, wholeBuffer: true };
  }

  // 원래 차로 기준 측면 이동(오른쪽 +, m)과 도로 방향 대비 진행 방향 차이(도)
  function lateralSeries(pre, base) {
    if (!pre.length) return [];
    // 핸들 기준값은 버퍼 전체 중앙값 (차선 안 보정 조향은 평균이 0 근처, 곡선이면 곡선을 따라 꺾은 각도)
    const allSteer = pre.map((f) => f.steeringDeg).filter((x) => x != null);
    if (allSteer.length) base = { ...base, steerBase: median(allSteer) };
    let lat = 0;
    const dh0 = angDiff(pre[0].headingDeg, base.at(pre[0].t));
    const series = [{ t: pre[0].t, lat: 0, dh: dh0, sdev: pre[0].steeringDeg == null ? null : pre[0].steeringDeg - base.steerBase }];
    for (let i = 1; i < pre.length; i++) {
      const p = pre[i - 1], f = pre[i];
      const dt = (f.t - p.t) / 1000;
      const v = ((p.speedKph + f.speedKph) / 2) / 3.6;
      const dh = angDiff(f.headingDeg, base.at(f.t));
      lat += v * Math.sin(rad((series[i - 1].dh + dh) / 2)) * dt;
      series.push({ t: f.t, lat, dh, sdev: f.steeringDeg == null ? null : f.steeringDeg - base.steerBase });
    }
    // 문턱 비교에는 앞뒤 0.5초(시간 기준) 이동평균한 방향 차이(dhs)를 써서 순간 잡음·1Hz 계단에 덜 흔들리게 한다.
    // 핸들도 앞뒤 0.25초 이동평균(sdevs)으로 본다 (센서 잡음 때문에 조작 구간이 끊기지 않도록).
    for (let i = 0; i < series.length; i++) {
      let a = i, b = i;
      while (a > 0 && series[i].t - series[a - 1].t <= 500) a--;
      while (b < series.length - 1 && series[b + 1].t - series[i].t <= 500) b++;
      series[i].dhs = mean(series.slice(a, b + 1).map((s) => s.dh));
      if (series[i].sdev == null) { series[i].sdevs = null; continue; }
      let c = i, d = i;
      while (c > 0 && series[i].t - series[c - 1].t <= 250 && series[c - 1].sdev != null) c--;
      while (d < series.length - 1 && series[d + 1].t - series[i].t <= 250 && series[d + 1].sdev != null) d++;
      series[i].sdevs = mean(series.slice(c, d + 1).map((s) => s.sdev));
    }
    return series;
  }

  // 기준 구간 길이 결정: 빠르면 느린 진로변경까지 고려해 더 길게 뺀다
  function guardFor(pre) {
    const last = pre[pre.length - 1];
    const v10 = mean(pre.filter((f) => f.t >= last.t - 10000).map((f) => f.speedKph));
    const fast = v10 >= THRESH.FAST_KPH;
    return { fast, guardMs: (fast ? THRESH.GUARD_SEC_FAST : THRESH.GUARD_SEC) * 1000 };
  }

  // 차트용: 정리된 프레임, 도로 방향 기준, 측면 이동 시계열 (t=0 이 트리거 시각)
  function trace(frames) {
    const { pre: all } = prepare(frames);
    const pre = all.filter((f) => f.t <= 0);
    if (pre.length < 3 || pre.some((f) => f.headingDeg == null)) return { pre, base: null, series: [] };
    const base = roadBaseline(pre, guardFor(pre).guardMs);
    return { pre, base, series: lateralSeries(pre, base) };
  }

  // 같은 쪽 조향(기준 대비 문턱 이상)이 STEER_RUN_MS 이상 이어진 구간이 있는가
  function steeringRun(series, dirSign, fromT, toT, minDev = THRESH.STEER_DEV_DEG) {
    let runStart = null, best = 0, peak = 0;
    for (const s of series) {
      if (s.t < fromT || s.t > toT || s.sdevs == null) { runStart = null; continue; }
      peak = Math.max(peak, s.sdevs * dirSign);
      if (s.sdevs * dirSign >= minDev) {
        if (runStart == null) runStart = s.t;
        best = Math.max(best, s.t - runStart + 100);
      } else runStart = null;
    }
    return { ok: best >= THRESH.STEER_RUN_MS, durationMs: best, peakDeg: round(peak, 1) };
  }

  function emptyLaneChange(status, extra = {}) {
    return { status, detected: false, direction: null, lateralM: null, maxHeadingDeg: null, startT: null, endT: null,
      completedSecBefore: null, steeringCorroborated: null, steeringReturned: null, maneuverSpeedKph: null, reason: null, ...extra };
  }

  // "차로 유지"로 보이는 동안 핸들이 기준 구간(도로를 따라갈 때)의 각도에서 얼마나 크게·오래 벗어났는가.
  // 곡선이 끝나는 곳에서 같은 쪽으로 진로를 바꾸면 진행 방향은 곡선이 이어지는 것처럼 보이지만, 핸들은 곡선 각도에서 벗어난다.
  // 반환: 0.5초 넘게 이어진 편차 크기(그 동안 가장 작았던 |편차|의 최댓값)와 문턱
  function steerActivity(pre, base, fromT) {
    const ref = pre.filter((f) => f.t >= base.refStartT && f.t <= base.refEndT && f.steeringDeg != null).map((f) => f.steeringDeg);
    if (ref.length < 10) return null;
    const c = median(ref);
    const sigma = 1.4826 * median(ref.map((x) => Math.abs(x - c)));
    const loc = pre.filter((f) => f.t >= fromT);
    const sm = loc.map((f) => {
      const w = loc.filter((g) => Math.abs(g.t - f.t) <= 250 && g.steeringDeg != null).map((g) => g.steeringDeg);
      return w.length ? mean(w) - c : null;
    });
    let dev = 0;
    for (let i = 0; i < loc.length; i++) {
      let mn = Infinity, j = i;
      for (; j < loc.length && loc[j].t - loc[i].t <= THRESH.STEER_ACT_MS; j++) mn = Math.min(mn, sm[j] == null ? 0 : Math.abs(sm[j]));
      if (loc[j - 1].t - loc[i].t >= THRESH.STEER_ACT_MS - 100) dev = Math.max(dev, mn);
    }
    const thr = Math.max(base.curved ? THRESH.STEER_ACT_CURVE_DEG : THRESH.STEER_ACT_DEG, 2.5 * sigma);
    return { dev: round(dev, 2), thr: round(thr, 2), sigma: round(sigma, 2) };
  }

  // 차로변경 "움직임" 분류. 'detected' 는 진로변경과 같은 움직임이라는 뜻이고(굽은 길·차로 어긋남도 똑같이 보인다),
  // 실제 진로변경인지는 운전자 확인과 함께 판단한다(computeFault).
  function classifyLaneChange(pre, series, base, quality, brakeRuns, opts = {}) {
    const lc = classifyLaneChangeCore(pre, series, base, quality, brakeRuns, opts);
    if (lc.status === 'starting') {
      // 핸들 기록이 없으면 충돌 직전의 방향 변화만으로 움직임을 단정하지 않는다 (진로변경 움직임 판정과 같은 원칙)
      if (!quality.steeringAvailable) return { ...lc, status: 'uncertain', reason: 'no_steering' };
      // 그 움직임 무렵(시작 1.5초 전부터) 브레이크를 밟았으면 피하려는 조작일 수 있다 — 예/아니요 답으로도 가릴 수 없어 묻지 않는다
      if (lc.startT != null && (brakeRuns || []).some((r) => r.end >= lc.startT - 1500)) lc.reason = 'evasive';
    }
    // 충돌 직전에 시작한 움직임(starting)의 방향 점검: 바로 앞에서 반대쪽으로 크게 옆 이동했거나(반대쪽 진로변경을 마무리하며
    // 핸들을 되돌리는 중일 수 있음), 시작 뒤에도 차가 반대쪽으로 계속 움직였으면 방향을 단정하지 않는다.
    if (lc.status === 'starting' && lc.direction && lc.startT != null && series.length) {
      const last = pre[pre.length - 1];
      const sgn = lc.direction === 'RIGHT' ? 1 : -1;
      const local = series.filter((x) => x.t >= last.t - THRESH.LOCAL_SEC * 1000);
      const atStart = local.filter((x) => x.t <= lc.startT);
      const latS = atStart.length ? atStart[atStart.length - 1].lat : local[0].lat;
      const oppBefore = Math.max(0, ...atStart.map((x) => (x.lat - latS) * sgn));
      const oppAfter = -(series[series.length - 1].lat - latS) * sgn;
      // 바로 앞 5초 안에 반대쪽으로 꺾은 핸들 조작이 있었으면, 지금의 조작은 반대쪽 진로변경을 마무리하며 되돌리는 것일 수 있다
      // (느린 진로변경은 되돌리는 절반만 4초 가까이 걸린다)
      const oppSteer = quality.steeringAvailable && steeringRun(series, -sgn, lc.startT - 5000, last.t).ok;
      if (oppBefore >= THRESH.LANE_LAT_M || oppAfter >= 0.3 || oppSteer) {
        return { ...lc, status: 'uncertain', reason: 'opposite_pair', oppositeMoveM: round(Math.max(oppBefore, oppAfter), 1) };
      }
    }
    return lc;
  }
  function classifyLaneChangeCore(pre, series, base, quality, brakeRuns, opts = {}) {
    const last = pre[pre.length - 1];
    const bufferMs = last.t - pre[0].t;
    const recent = pre.filter((f) => f.t >= last.t - 4000);
    const recentSpeed = mean(recent.map((f) => f.speedKph));
    if (!quality.hasHeading || quality.headingMissing > THRESH.MISSING_MAX || quality.speedMissing > THRESH.MISSING_MAX) {
      return emptyLaneChange('unmeasurable', { reason: 'missing' });
    }
    if (bufferMs < THRESH.MIN_BUFFER_SEC * 1000) return emptyLaneChange('unmeasurable', { reason: 'short_buffer' });
    // 최근 LOCAL_SEC 동안의 움직임 (버퍼 앞쪽부터 쌓인 기준 오차가 섞이지 않도록 충돌 시점 기준으로 본다)
    const localMs = Math.min(THRESH.LOCAL_SEC * 1000, bufferMs);
    const local = series.filter((s) => s.t >= last.t - localMs);
    const latEnd = series[series.length - 1].lat;
    const latSpan = Math.max(...local.map((s) => Math.abs(latEnd - s.lat)));
    const maxAbsDh = Math.max(...local.map((s) => Math.abs(s.dhs)));
    const lc = {
      status: 'none', detected: false, direction: null,
      lateralM: round(latSpan, 1), maxHeadingDeg: round(maxAbsDh, 1), windowSec: sec(localMs),
      startT: null, endT: null, completedSecBefore: null,
      steeringCorroborated: quality.steeringAvailable ? false : null, steeringReturned: null,
      maneuverSpeedKph: round(recentSpeed, 0), reason: null,
      roadCurveCompensated: base.curved,
    };

    if (recentSpeed < THRESH.MIN_SPEED_KPH && latSpan < THRESH.LANE_LAT_M) { lc.status = 'too_slow'; return lc; }
    // 기록 중에 도로 모양이 바뀌었는데(굽은 길이 끝나는 중 등) 곧은 기준 구간을 찾지 못했다 → 옆 이동을 잴 기준이 없다
    if (base.roadChange) return emptyLaneChange('uncertain', { reason: 'road_change', maneuverSpeedKph: round(recentSpeed, 0), windowSec: sec(localMs) });

    // 충돌 직전 1.5초에 한쪽으로 꺾은 조향
    const lateSteer = quality.steeringAvailable
      ? (() => { const a = steeringRun(series, 1, last.t - 1500, last.t), b = steeringRun(series, -1, last.t - 1500, last.t); return a.ok ? 1 : b.ok ? -1 : 0; })()
      : 0;
    // 충돌 직전 2초 동안 한쪽으로 꾸준히 방향이 틀어지고 있었는가 (완만한 진로변경 초반은 차로 유지처럼 작게 보인다)
    const tail2 = series.filter((s) => s.t >= last.t - 2000);
    const endDh = tail2[tail2.length - 1].dhs, driftDh = endDh - tail2[0].dhs;
    const steerTail = series.filter((s) => s.t >= last.t - 1000 && s.sdev != null).map((s) => s.sdev);
    const steerMean = steerTail.length ? mean(steerTail) : 0;
    const drifting = Math.abs(endDh) >= 0.5 && Math.abs(driftDh) >= 0.4 && Math.sign(driftDh) === Math.sign(endDh) &&
      (Math.abs(steerMean) < 0.3 || Math.sign(steerMean) === Math.sign(endDh));
    // 뚜렷한 차로 유지
    if (latSpan < THRESH.LANE_KEEP_LAT_M && maxAbsDh < THRESH.LANE_KEEP_DEG && !lateSteer && !drifting) {
      // 빠를 때는 느린 진로변경(최대 9초 안팎)이 기록 앞부분에 걸쳐 있어도 기준이 그 움직임에 끌려가 "유지"로 보일 수 있다
      if (opts.fast && bufferMs < THRESH.MIN_BUFFER_FAST_SEC * 1000) { lc.status = 'uncertain'; lc.reason = 'short_for_speed'; }
      // 기록 앞부분이 고르지 않아 도로 방향 기준을 충돌 직전 구간에서 잡았으면, 그 구간의 느린 움직임이 기준에 묻혔을 수 있다
      else if (base.lateRef) { lc.status = 'uncertain'; lc.reason = 'late_reference'; }
      // 핸들 기록이 없으면 방향 기록만으로 "유지"를 단정하지 않는다 (방향 잡음·지연을 가려낼 수 없다)
      else if (!quality.steeringAvailable) { lc.status = 'uncertain'; lc.reason = 'no_steering'; }
      // 진행 방향이 핸들보다 늦게 반응하면(위치 기반 방향 지연) 충돌 직전 몇 초의 움직임이 아직 방향 기록에 없다.
      // 그 사이 핸들이 조용했을 때만 "유지"로 본다.
      else if (quality.headingLagSec != null && quality.headingLagSec >= THRESH.LAG_MAX_SEC) {
        const tailFrom = last.t - (quality.headingLagSec + 2) * 1000;
        const quietTail = series.filter((x) => x.t >= tailFrom && x.sdevs != null).every((x) => Math.abs(x.sdevs) < 1.5);
        if (!quietTail) { lc.status = 'uncertain'; lc.reason = 'heading_lag'; }
      }
      // 진행 방향은 차로를 지킨 것처럼 보여도, 핸들이 도로를 따라갈 때의 각도에서 크게·오래 벗어나 있었으면 단정하지 않는다
      // (곡선이 끝나는 곳의 같은 쪽 진로변경, 곡선이 시작되는 곳의 반대쪽 진로변경 등)
      if (lc.status === 'none') {
        const act = steerActivity(pre, base, last.t - localMs);
        if (act) { lc.steerActivityDeg = act.dev; lc.steerActivityThrDeg = act.thr; lc.steerRefSigmaDeg = act.sigma; }
        if (act && act.dev >= act.thr) { lc.status = 'uncertain'; lc.reason = 'steer_activity'; }
      }
      return lc;
    }

    const startingOrBorderline = () => {
      const recentDh = series.filter((s) => s.t >= last.t - 2000);
      const peak = recentDh.reduce((a, s) => (Math.abs(s.dhs) > Math.abs(a.dhs) ? s : a));
      if (lateSteer || drifting || Math.abs(peak.dhs) >= THRESH.LANE_KEEP_DEG) {
        const sgn = lateSteer || Math.sign(peak.dhs) || 1;
        let i = series.length - 1;
        const active = (s) => s.dhs * sgn >= 0.5 || (s.sdev != null && s.sdev * sgn >= THRESH.STEER_DEV_DEG);
        while (i > 0 && !active(series[i])) i--;
        while (i > 0 && active(series[i - 1])) i--;
        Object.assign(lc, { status: 'starting', direction: sgn > 0 ? 'RIGHT' : 'LEFT', startT: series[i].t,
          steeringCorroborated: quality.steeringAvailable ? !!lateSteer : null, basis: lateSteer ? 'steering' : 'heading' });
        return lc;
      }
      lc.status = 'uncertain'; lc.reason = 'borderline';
      return lc;
    };

    // 마지막 방향 변화 구간: 끝에서부터 거슬러 올라가 도로 방향에서 0.5도 넘게 벗어난 마지막 연속 구간
    let e = series.length - 1;
    while (e > 0 && Math.abs(series[e].dhs) < 0.5) e--;
    if (Math.abs(series[e].dhs) < 0.5) return startingOrBorderline();
    const dirSign = Math.sign(series[e].dhs);
    let s0 = e;
    while (s0 > 0 && series[s0 - 1].dhs * dirSign >= 0.5) s0--;
    let maxDirDh = 0, peakK = s0;
    for (let k = s0; k <= e; k++) if (series[k].dhs * dirSign > maxDirDh) { maxDirDh = series[k].dhs * dirSign; peakK = k; }
    // 시작점은 그 움직임의 크기에 비례한 문턱(최대 방향 변화의 15%, 최소 0.5°)으로 다시 잡는다 —
    // 큰 진로변경 앞의 차선 안 흔들림이 0.5°를 넘어 시작점을 앞당기지(신호 선행 거리를 줄이지) 않도록.
    const startTh = Math.max(0.5, 0.15 * maxDirDh);
    s0 = peakK;
    while (s0 > 0 && series[s0 - 1].dhs * dirSign >= startTh) s0--;
    // 무릎(오름이 시작된 곳): 진로변경 앞에서 차선 안 흔들림 등으로 방향이 한동안 비슷한 값(선반)에 머물렀으면, 그 선반은
    // 진로변경에 넣지 않는다 — 시작이 몇 초 앞으로 잡혀 제때 켠 방향지시등을 "늦게 켬"으로 읽는 것을 막는다.
    // 최대 방향 변화로 오르는 동안의 변화 속도(1초 창, 1Hz 갱신이면 더 긴 창)가 최대 속도의 15% 아래로 떨어지는 곳을 찾는다.
    {
      const W = Math.max(1000, 1.5 * (quality.headingUpdateMs || 0));
      const rateAt = (k) => {
        let j = k;
        while (j > 0 && series[k].t - series[j - 1].t <= W) j--;
        const dt = (series[k].t - series[j].t) / 1000;
        return dt > 0 ? ((series[k].dhs - series[j].dhs) * dirSign) / dt : 0;
      };
      let maxRate = 0;
      for (let k = s0; k <= peakK; k++) maxRate = Math.max(maxRate, rateAt(k));
      if (maxRate > 0) {
        let kk = peakK;
        while (kk > s0 && rateAt(kk - 1) >= 0.15 * maxRate) kk--;
        const kneeT = series[kk].t - W / 2;
        let k2 = s0;
        while (k2 < peakK && series[k2].t < kneeT) k2++;
        if (k2 > s0) s0 = k2;
      }
    }
    // 그 구간 동안 옆으로 움직인 거리 (구간 앞의 작은 기준 오차가 쌓인 몫은 빼고 본다)
    const relLat = (series[e].lat - series[s0].lat) * dirSign;
    if (!(relLat >= THRESH.LANE_LAT_M && maxDirDh >= THRESH.LANE_DEG)) return startingOrBorderline();
    // 바로 앞(1.5초 안)에 반대쪽으로 크게 틀어진 구간이 붙어 있으면 S자다 — 회피·연속 움직임이거나, 도로 방향 기준이 치우쳐
    // 진로변경의 되돌아오는 절반이 반대 방향 움직임처럼 보인 것이다. 방향을 단정하지 않는다.
    const oppTh = Math.max(0.5, 0.5 * maxDirDh);
    if (series.some((x) => x.t >= series[s0].t - 1500 && x.t < series[s0].t && x.dhs * -dirSign >= oppTh)) {
      Object.assign(lc, { status: 'uncertain', reason: 'opposite_pair', direction: dirSign > 0 ? 'RIGHT' : 'LEFT', lateralM: round(relLat, 1), maxHeadingDeg: round(maxDirDh, 1) });
      return lc;
    }

    // 시작점: 그 앞에 같은 쪽 핸들 조작이 이어지면 최대 1.5초까지 더 앞으로 잡는다 (신호 선행 거리를 부풀리지 않도록).
    // 문턱은 그 움직임의 최대 조향의 15%(최소 0.5°)라서 평소 보정 조향은 시작점을 끌어당기지 못한다.
    let peakSteerSeg = 0;
    for (let k = s0; k <= e; k++) if (series[k].sdevs != null) peakSteerSeg = Math.max(peakSteerSeg, series[k].sdevs * dirSign);
    const steerStartTh = Math.max(THRESH.STEER_START_DEG, 0.15 * peakSteerSeg);
    const headingStart = series[s0].t;
    const steerAt = (k) => (series[k].sdevs == null ? 0 : series[k].sdevs * dirSign);
    while (s0 > 0 && steerAt(s0 - 1) >= steerStartTh && headingStart - series[s0 - 1].t <= 1500) s0--;
    lc.direction = dirSign > 0 ? 'RIGHT' : 'LEFT';
    lc.startT = series[s0].t;
    lc.riseSec = sec(Math.max(0, series[peakK].t - series[s0].t)); // 시작부터 방향 변화가 가장 큰 때까지 (진로변경 길이의 절반쯤)
    lc.endT = e === series.length - 1 ? null : series[e].t;
    lc.lateralM = round(relLat, 1);
    lc.maxHeadingDeg = round(maxDirDh, 1);
    const manEnd = lc.endT == null ? last.t : lc.endT;
    const inMan = pre.filter((f) => f.t >= lc.startT && f.t <= manEnd);
    lc.maneuverSpeedKph = round(mean(inMan.map((f) => f.speedKph)), 0);

    if (lc.maneuverSpeedKph < THRESH.MIN_SPEED_KPH) { lc.status = 'too_slow'; return lc; }
    if (relLat > THRESH.LANE_LAT_MAX_M) { lc.status = 'uncertain'; lc.reason = 'too_large'; return lc; }
    if (!base.quiet) { lc.status = 'uncertain'; lc.reason = 'noisy_heading'; return lc; }
    // 진행 방향만으로는 곡선 진입·방향 잡음과 진로변경을 가릴 수 없다 → 핸들 기록이 있어야 진로변경 움직임으로 본다
    if (!quality.steeringAvailable) { lc.status = 'uncertain'; lc.reason = 'no_steering'; return lc; }
    // 핸들 문턱은 2°와, 진로변경 구간 밖 평소 조향 흔들림(강건 표준편차)의 2.5배 중 큰 값
    // — 차선 안에서 크게 흔들며 달리는 운전자의 보정 조향을 진로변경으로 읽지 않도록.
    const outside = series.filter((s) => (s.t < lc.startT - 1000 || s.t > manEnd + 500) && s.sdev != null).map((s) => s.sdev);
    let steerDev = THRESH.STEER_DEV_DEG;
    if (outside.length >= 20) {
      const md = median(outside);
      const sigma = 1.4826 * median(outside.map((x) => Math.abs(x - md)));
      steerDev = Math.max(steerDev, 2.5 * sigma);
    }
    lc.steerThresholdDeg = round(steerDev, 1);
    const run = steeringRun(series, dirSign, lc.startT - 1000, manEnd, steerDev);
    lc.steeringCorroborated = run.ok;
    if (!run.ok) { lc.status = 'uncertain'; lc.reason = 'steering_mismatch'; return lc; }
    // 진로변경은 꺾었던 핸들을 되돌린다(S자 조향). 곡선 진입은 핸들이 꺾인 채 유지된다.
    const tail = series.filter((s) => s.t > manEnd - 300 && s.t <= manEnd && s.sdevs != null);
    const endDev = tail.length ? mean(tail.map((s) => s.sdevs * dirSign)) : 0;
    lc.steeringReturned = endDev <= THRESH.STEER_RETURN_RATIO * run.peakDeg;
    if (!lc.steeringReturned) { lc.status = 'uncertain'; lc.reason = 'curve_or_lc'; return lc; }
    // 굽은 길: 핸들이 가운데로 돌아왔는데(반대로 꺾어 되돌리는 조작 없이) 진행 방향은 새 값에 머물러 있다.
    // 진로변경은 한가운데에서 핸들이 잠깐 가운데를 지날 뿐 곧 반대로 꺾어 방향을 되돌린다.
    if (lc.endT == null) {
      const neutral = Math.max(0.6, THRESH.HOLD_RATIO * run.peakDeg);
      let k = series.length - 1, holdFrom = null;
      while (k > s0 && series[k].sdevs != null && Math.abs(series[k].sdevs) <= neutral && series[k].dhs * dirSign >= 0.6 * maxDirDh) { holdFrom = series[k].t; k--; }
      const held = holdFrom == null ? 0 : last.t - holdFrom;
      lc.headingHeldSec = sec(held);
      const rise = Math.max(0, series[peakK].t - lc.startT);
      if (held >= Math.max(THRESH.HOLD_MIN_MS, THRESH.HOLD_RATIO * rise)) { lc.status = 'uncertain'; lc.reason = 'bend_hold'; return lc; }
    }

    if (lc.endT != null && last.t - lc.endT > THRESH.ACTIVE_SEC * 1000) {
      lc.status = 'completed';
      lc.completedSecBefore = sec(last.t - lc.endT);
      lc.keptLaneAfter = Math.abs(latEnd - series[e].lat) < THRESH.LANE_KEEP_LAT_M;
      return lc;
    }
    if (last.t - lc.startT < THRESH.STARTING_SEC * 1000) { lc.status = 'starting'; lc.basis = 'heading'; return lc; }
    // 회피 조향: 충돌 2.5초 안에 시작했고 그 무렵 브레이크를 밟았으면 진로변경으로 단정하지 않는다
    const evasive = last.t - lc.startT < THRESH.EVASIVE_SEC * 1000 && (brakeRuns || []).some((r) => r.end >= lc.startT - 1500);
    if (evasive) { lc.status = 'starting'; lc.reason = 'evasive'; lc.basis = 'heading'; return lc; }
    lc.status = 'detected';
    lc.detected = true;
    return lc;
  }

  // 핸들 부호 점검: 오른쪽(+) 조향이면 진행 방향도 오른쪽으로 돌아야 한다. 반대로 기록되면(연동 설정 오류) 핸들 기록을 쓰지 않는다.
  function steeringSignCheck(pre) {
    const xs = [], ys = [];
    for (let i = 1; i < pre.length; i++) {
      const p = pre[i - 1], f = pre[i];
      if (f.steeringDeg == null || f.speedKph < 10) continue;
      const dt = (f.t - p.t) / 1000;
      if (dt <= 0) continue;
      xs.push(f.steeringDeg); ys.push(angDiff(f.headingDeg, p.headingDeg) / dt);
    }
    if (xs.length < 20) return 'unknown';
    const mxv = mean(xs), myv = mean(ys);
    let sxy = 0, sxx = 0, syy = 0;
    xs.forEach((x, i) => { sxy += (x - mxv) * (ys[i] - myv); sxx += (x - mxv) ** 2; syy += (ys[i] - myv) ** 2; });
    const n = xs.length;
    if (Math.sqrt(sxx / n) < 1 || Math.sqrt(syy / n) < 0.3) return 'unknown';
    const r = sxy / Math.sqrt(sxx * syy);
    return r < -0.5 ? 'reversed' : r > 0.3 ? 'ok' : 'unknown';
  }

  function runsOf(pre, pred) {
    const runs = [];
    let cur = null;
    for (const f of pre) {
      if (pred(f)) { if (!cur) cur = { start: f.t, end: f.t }; else cur.end = f.t; }
      else if (cur) { runs.push(cur); cur = null; }
    }
    if (cur) runs.push(cur);
    return runs;
  }

  // 진로변경 움직임(detected)의 "방향"을 믿기 어려운 기록인가. 판정(상태)은 바꾸지 않고, 방향에 기대는 내 신호 가산만
  // 범위로 낮추는 데 쓴다(computeFault). 곡률이 바뀌는 길에서는 한쪽 진로변경이 반대쪽 움직임으로 읽힐 수 있는데,
  // 그때 운전자가 방향을 듣지 않고 "예"라고 하면 실제 방향의 방향지시등을 "켜지 않음"으로 읽게 된다.
  //  - 'opposite_signal': 움직임 시작 3초 전부터 충돌까지 반대쪽 방향지시등이 켜져 있었다 (실제로는 그쪽으로 움직였을 수 있다)
  //  - 'opposite_move'  : 시작 바로 앞(충돌 전 8초 안)에 반대쪽으로 0.9m 넘게 옆 이동했고, 시작 전 5초 안에 반대쪽 핸들 조작이 있었다
  //                       (그쪽 진로변경 뒤에 도로가 굽어 반대 방향 움직임처럼 보였을 수 있다)
  //  - 'road_curve'     : 도로 방향 기준이 곡선 맞춤이다 (그 곡선을 충돌 시점까지 늘려 쓴다 — 곡률이 바뀌면 방향이 틀어진다)
  //  - 'late_reference' : 기록 앞부분이 고르지 않아 도로 방향 기준을 충돌 무렵 구간에서 잡았다
  // 곡선이 끝난 뒤의 곧은 구간에서 다시 잡은 기준(afterCurve)은 넣지 않는다 — 움직임 가까이의 곧은 기준이라 방향이 더 흔들리지 않고,
  // 차선 안 흔들림만으로도 자주 켜진다(합성 시험: 흔들림 있는 곧은 길 진로변경 중 신호 가산이 확정되던 경우의 약 17%).
  // 신호를 아예 켜지 않았으면 방향이 틀려도 "켜지 않음"은 그대로 맞으므로, 여기 없는 경우가 남아도 신호 판단이 틀리지는 않는다.
  function directionCaveat(pre, series, base, lc, quality) {
    if (lc.status !== 'detected' || !lc.direction || lc.startT == null) return null;
    const last = pre[pre.length - 1];
    const sgn = lc.direction === 'RIGHT' ? 1 : -1;
    const opp = sgn > 0 ? 'LEFT' : 'RIGHT';
    if (quality.signalAvailable && pre.some((f) => f.t >= lc.startT - THRESH.RECENT_SIGNAL_SEC * 1000 && f.turnSignal === opp)) return 'opposite_signal';
    const atStart = series.filter((x) => x.t >= last.t - THRESH.LOCAL_SEC * 1000 && x.t <= lc.startT);
    if (atStart.length) {
      const latS = atStart[atStart.length - 1].lat;
      // 옆 이동과 함께 반대쪽 핸들 조작(평소 흔들림을 넘는 문턱, 0.5초 이상)이 있어야 한다 — 차선 안 흔들림만으로는 낮추지 않는다
      const oppLat = Math.max(0, ...atStart.map((x) => (x.lat - latS) * sgn));
      if (oppLat >= THRESH.LANE_LAT_M && steeringRun(series, -sgn, lc.startT - 5000, lc.startT, lc.steerThresholdDeg || THRESH.STEER_DEV_DEG).ok) return 'opposite_move';
    }
    if (base.curved) return 'road_curve';
    if (base.lateRef) return 'late_reference';
    return null;
  }

  function roadTypeOf(ctx) {
    return ctx.roadType === 'highway' || ctx.roadType === 'urban' ? ctx.roadType : 'unknown';
  }

  function analyzeSignal(pre, lc, roadType, quality) {
    const last = pre[pre.length - 1];
    const requiredM = roadType === 'highway' ? 100 : 30;
    const recent = quality.signalAvailable ? pre.filter((f) => f.t >= last.t - THRESH.RECENT_SIGNAL_SEC * 1000) : [];
    const recentLR = recent.filter((f) => f.turnSignal === 'LEFT' || f.turnSignal === 'RIGHT');
    const signal = {
      status: 'not_applicable', requiredM, leadM: null, leadSec: null, marginM: null,
      onAtBufferStart: false, stateAtImpact: quality.signalAvailable ? last.turnSignal : null,
      recentOn: recentLR.length ? recentLR[recentLR.length - 1].turnSignal : null,
      bridged: pre.some((f) => f.bridged), note: null,
    };
    if (!lc.direction || lc.startT == null) return signal;
    if (!quality.signalAvailable) { signal.status = 'unavailable'; return signal; }
    const dir = lc.direction;
    const runs = runsOf(pre, (f) => f.turnSignal === dir);
    if (!runs.length) { signal.status = 'none'; return signal; }
    const s = lc.startT;
    // 시작 시점 추정 오차: 기본 0.3초, 방향 값이 드물게 갱신되면(예: 1Hz) 그 간격만큼, 느린 진로변경은 시작이 흐릿해
    // 방향이 바뀌기 시작해 가장 크게 바뀔 때까지 걸린 시간의 30%만큼 (최대 1.5초). 진행 방향이 핸들보다 늦게 반응하면
    // (위치 기반 방향 지연) 시작이 그만큼 늦게 잡히므로 잰 지연을 더한다 (최대 2초).
    const lagSec = Math.min(2, quality.headingLagSec || 0);
    const errSec = Math.max(THRESH.SIGNAL_MARGIN_SEC, Math.min(1.5, Math.max((quality.headingUpdateMs || 0) / 1000, 0.3 * (lc.riseSec || 0)))) + lagSec;
    const vNear = mean(pre.filter((f) => f.t >= s - errSec * 1000 && f.t <= s + errSec * 1000).map((f) => f.speedKph)) / 3.6;
    const covering = runs.find((r) => r.start <= s && r.end >= s);
    const after = runs.find((r) => r.start > s);
    if (!covering && !after) {
      signal.status = 'none';
      signal.note = '움직이기 전에 신호를 껐음';
      return signal;
    }
    if (!covering) {
      const afterSec = (after.start - s) / 1000;
      signal.leadM = 0;
      signal.leadSec = -sec(after.start - s);
      // 시작 시점 오차 안에서 켰으면 "늦게 켬"으로 단정하지 않는다 (시작이 실제보다 앞으로 잡혔을 수 있다)
      if (afterSec <= errSec) {
        signal.status = 'borderline';
        signal.marginM = Math.max(THRESH.SIGNAL_MARGIN_MIN_M, round(vNear * errSec, 0));
        signal.note = '움직이기 시작한 무렵에 켰음 — 시작 시점의 측정 오차 안이라 기준 충족 여부를 단정할 수 없음';
      } else signal.status = 'after';
      return signal;
    }
    const run = covering;
    let dist = 0;
    for (let i = 1; i < pre.length; i++) {
      const p = pre[i - 1], f = pre[i];
      if (p.t >= run.start && f.t <= s) dist += ((p.speedKph + f.speedKph) / 2 / 3.6) * ((f.t - p.t) / 1000);
    }
    const vAvg = mean(pre.filter((f) => f.t >= run.start && f.t <= s).map((f) => f.speedKph)) / 3.6;
    const marginM = Math.max(THRESH.SIGNAL_MARGIN_MIN_M, round(Math.max(vAvg, vNear || 0) * errSec, 0));
    const leadM = round(dist, 0); // 판정과 문장에 같은 값을 쓴다
    Object.assign(signal, { leadM, leadSec: sec(s - run.start), marginM, onAtBufferStart: run.start === pre[0].t });
    if (leadM < 1 && !signal.onAtBufferStart) {
      signal.status = 'borderline';
      signal.note = '움직이기 시작하면서 거의 동시에 켰음 — 시작 시점의 측정 오차 안이라 기준 충족 여부를 단정할 수 없음';
      return signal;
    }
    if (roadType === 'unknown' && leadM >= 30 - marginM && leadM < 100 + marginM) {
      signal.status = signal.onAtBufferStart ? 'indeterminate' : 'borderline';
      signal.note = '도로 유형을 몰라 일반도로(30m)·고속도로(100m) 기준 중 어느 쪽인지 정할 수 없음';
      return signal;
    }
    if (leadM >= requiredM + marginM) signal.status = 'ok';
    else if (signal.onAtBufferStart) signal.status = 'indeterminate';
    else if (leadM > requiredM - marginM) signal.status = 'borderline';
    else signal.status = 'late';
    return signal;
  }

  const brakeEmpty = (available) => ({ applied: available ? false : null, onsetSecBefore: null, releasedSecBefore: null, onAtBufferStart: false });

  // 핸들(지연 없음)과 진행 방향 변화율(위치 기반이라 늦을 수 있음)을 맞대어, 상관이 가장 큰 시간 차를 방향 지연으로 본다.
  // 핸들 움직임이 거의 없거나 상관이 약하면 잴 수 없다(null).
  function estimateHeadingLag(pre) {
    const n = pre.length;
    if (n < 40 || pre.some((f) => f.steeringDeg == null)) return null;
    const win = (i, key, half) => {
      let a = i, b = i;
      while (a > 0 && pre[i].t - pre[a - 1].t <= half) a--;
      while (b < n - 1 && pre[b + 1].t - pre[i].t <= half) b++;
      return [a, b];
    };
    const rate = pre.map((_, i) => { const [a, b] = win(i, 'h', 500); const dt = (pre[b].t - pre[a].t) / 1000; return dt > 0 ? angDiff(pre[b].headingDeg, pre[a].headingDeg) / dt : 0; });
    const x = pre.map((f, i) => { const [a, b] = win(i, 's', 500); return mean(pre.slice(a, b + 1).map((g) => g.steeringDeg)) * Math.max(1, f.speedKph / 3.6); });
    const mx0 = mean(x), sdx = Math.sqrt(mean(x.map((v) => (v - mx0) ** 2)));
    const vAvg = mean(pre.map((f) => Math.max(1, f.speedKph / 3.6)));
    if (sdx / vAvg < 0.5) return null; // 핸들이 평균 0.5° 넘게 움직이지 않았다
    let best = { lag: null, r: -1 };
    for (let L = 0; L <= 3000; L += 100) {
      const xs = [], ys = [];
      let j = 0;
      for (let i = 0; i < n; i++) {
        const target = pre[i].t + L;
        while (j < n && pre[j].t < target) j++;
        if (j >= n) break;
        if (pre[j].t - target > 150) continue;
        xs.push(x[i]); ys.push(rate[j]);
      }
      if (xs.length < 30) break;
      const mxs = mean(xs), mys = mean(ys);
      let sxy = 0, sxx = 0, syy = 0;
      xs.forEach((v, k) => { sxy += (v - mxs) * (ys[k] - mys); sxx += (v - mxs) ** 2; syy += (ys[k] - mys) ** 2; });
      const r = sxx > 0 && syy > 0 ? sxy / Math.sqrt(sxx * syy) : 0;
      if (r > best.r) best = { lag: L, r };
    }
    return best.r >= 0.5 ? round(best.lag / 1000, 1) : null;
  }

  function analyzeWindow(frames, ctx) {
    ctx = ctx || {};
    let { pre: all, quality } = prepare(frames);
    const roadType = roadTypeOf(ctx);
    const impact = impactOf(ctx);
    // 에어백 트리거가 늦게 온 경우(사고 뒤 한참 지나 에어백 "상태"를 보고 시작): 트리거 시각(t=0)까지 이어진 에어백 켜짐 구간의
    // 시작을 충돌 시각으로 다시 맞춘다. 앞으로만(트리거보다 이른 쪽으로만), 15초 안에서만 맞추고, 그 구간이 기록 첫머리부터
    // 켜져 있었으면 전개 시각이 기록 밖이라 충돌 시각을 모르는 것으로 본다. 떨어져 있는 짧은 에어백 값(잡음)은 쓰지 않는다.
    if (impact.known && impact.source === 'airbag' && all.length) {
      let i0 = -1;
      for (let i = 0; i < all.length; i++) if (all[i].t <= THRESH.AIRBAG_SYNC_MS) i0 = i;
      if (i0 >= 0 && all[i0].airbag) {
        let s0 = i0;
        while (s0 > 0 && all[s0 - 1].airbag) s0--;
        const startT = all[s0].t;
        if (s0 === 0 && startT < -THRESH.AIRBAG_SYNC_MS) {
          impact.known = false; impact.reason = 'airbag_before_buffer';
        } else if (startT < -THRESH.AIRBAG_SYNC_MS) {
          if (-startT > THRESH.AIRBAG_MAX_SHIFT_MS) { impact.known = false; impact.reason = 'airbag_too_early'; }
          else { impact.anchoredMs = startT; all = all.map((f) => ({ ...f, t: f.t - startT })); }
        }
      }
    }
    const pre = all.filter((f) => f.t <= 0);
    const airbagDeployed = all.some((f) => f.airbag);
    const speeds = speedContext(pre, quality);
    const bufferSec = pre.length ? sec(pre[pre.length - 1].t - pre[0].t) : 0;
    const hazardOn = quality.signalAvailable && pre.some((f) => f.turnSignal === 'HAZARD');
    if (!impact.known || pre.length < 3) {
      return {
        quality, roadType, impact, impactSpeedKph: null, ...speeds, bufferSec,
        laneChange: emptyLaneChange('unmeasurable', { reason: impact.known ? 'too_few_frames' : 'impact_unknown' }),
        signal: { status: 'not_applicable', requiredM: roadType === 'highway' ? 100 : 30, leadM: null, leadSec: null, stateAtImpact: null, recentOn: null },
        brake: brakeEmpty(quality.brakeAvailable && impact.known), maxDecelKphPerS: null, airbagDeployed,
        stoppedAtImpact: null, stoppedAtEnd: pre.length ? pre.slice(-5).every((f) => f.speedKph < 2) : null, hazardOn,
      };
    }
    const last = pre[pre.length - 1];
    if (quality.steeringAvailable) {
      quality.steeringSign = steeringSignCheck(pre);
      if (quality.steeringSign === 'reversed') quality.steeringAvailable = false;
    }
    const { fast, guardMs } = guardFor(pre);
    if (quality.steeringAvailable) quality.headingLagSec = estimateHeadingLag(pre);
    const base = roadBaseline(pre, guardMs);
    const series = lateralSeries(pre, base);
    const allBrakeRuns = quality.brakeAvailable ? runsOf(pre, (f) => f.brake) : [];
    let laneChange = classifyLaneChange(pre, series, base, quality, allBrakeRuns, { fast });
    // 판정 민감도 확인: 도로 방향 기준을 버퍼 전체로 바꿔 한 번 더 본다(직선은 중앙값 둘레 평균, 곡선은 버퍼 전체 회전 맞춤).
    // 이 단계는 판정을 낮출 때만 쓴다.
    //  - 진로변경 움직임으로 나왔는데 기준을 바꾸니 사라지면 (차선 안 흔들림이 기준을 치우치게 만든 경우) 단정하지 않는다.
    //  - 차로 유지로 나왔는데 기준을 바꾸니 옆으로 움직인 흔적이 나오면 (느린 진로변경이 기준을 끌고 간 경우) 단정하지 않는다.
    if (base.quiet && (laneChange.status === 'detected' || laneChange.status === 'none')) {
      const base2 = base.curved
        ? { ...baselineFrom({ ...fitWindow(pre, pre[0].t), ws: pre[0].t, we: last.t, quiet: true }, pre[0].t, true), wholeBuffer: true }
        : wholeBufferBaseline(pre, base);
      const lc2 = classifyLaneChange(pre, lateralSeries(pre, base2), base2, quality, allBrakeRuns, { fast });
      if (laneChange.status === 'detected' && lc2.status !== 'detected') {
        laneChange = { ...laneChange, status: 'uncertain', detected: false, reason: 'baseline_sensitive' };
      } else if (laneChange.status === 'none' && lc2.status !== 'none' && lc2.status !== 'too_slow' &&
        (['detected', 'completed', 'starting'].includes(lc2.status) || (lc2.lateralM || 0) >= THRESH.LANE_LAT_M)) {
        laneChange = { ...laneChange, status: 'uncertain', reason: 'baseline_sensitive' };
      }
    }
    if (laneChange.status === 'detected') laneChange = { ...laneChange, directionCaveat: directionCaveat(pre, series, base, laneChange, quality) };
    const signal = analyzeSignal(pre, laneChange, roadType, quality);

    // 브레이크: 충돌 직전 1.5초 안에 걸친 마지막 브레이크 구간
    let brake;
    if (!quality.brakeAvailable) brake = brakeEmpty(false);
    else {
      const bruns = allBrakeRuns.filter((r) => r.end >= last.t - 1500);
      const br = bruns[bruns.length - 1];
      brake = br
        ? { applied: true, onsetSecBefore: sec(last.t - br.start), releasedSecBefore: br.end < last.t ? sec(last.t - br.end) : null, onAtBufferStart: br.start === pre[0].t }
        : brakeEmpty(true);
    }

    let maxDecel = 0; // km/h per s, 마지막 2초
    const last2s = pre.filter((f) => f.t >= last.t - 2000);
    for (let i = 0; i < last2s.length; i++) {
      for (let j = i + 1; j < last2s.length; j++) {
        const dt = (last2s[j].t - last2s[i].t) / 1000;
        if (dt >= 0.5 && dt <= 1.0) maxDecel = Math.max(maxDecel, (last2s[i].speedKph - last2s[j].speedKph) / dt);
      }
    }

    return {
      quality,
      roadType,
      impact,
      road: { yawRateDegS: base.yawRateDegS, curveCompensated: base.curved, headingNoiseDeg: base.noiseDeg, refEndSecBefore: sec(last.t - base.refEndT) },
      impactSpeedKph: round(last.speedKph, 0),
      ...speeds,
      laneChange,
      signal,
      brake,
      maxDecelKphPerS: round(maxDecel, 1),
      airbagDeployed,
      stoppedAtImpact: pre.slice(-5).every((f) => f.speedKph < 2),
      hazardOn,
      bufferSec,
    };
  }

  // ---------- 2. 사실 문장 ----------
  const DIR_KO = { LEFT: '왼쪽', RIGHT: '오른쪽' };
  const CAUTION_KO = {
    LANE_CHANGE_CAMERA_ALERT: '진로변경 위반 단속 카메라 구간 안내가 있었음',
    CUT_IN_VIOLATION_ENFORCEMENT: '끼어들기 단속 구간 안내가 있었음',
    BUS_LANE_AVAILABLE_NOTICE: '버스전용차로 운영 구간 안내가 있었음',
    BUS_LANE_UNAVAILABLE_NOTICE: '버스전용차로 비운영 시간 안내가 있었음',
    BUS_LANE_VIOLATION_ENFORCEMENT: '버스전용차로 위반 단속 구간 안내가 있었음',
    ACCIDENT_PRONE_AREA: '사고 다발 구간 안내가 있었음',
    SCHOOL_ZONE: '어린이보호구역 안내가 있었음',
  };
  const UNCERTAIN_KO = {
    too_large: '이동 폭이 차로 하나보다 커서 (곡선 도로·회전·연속 차로변경 가능성)',
    noisy_heading: '방향 기록의 흔들림이 커서',
    steering_mismatch: '핸들 조작 기록이 진로변경과 맞지 않아 (곡선 도로나 방향 측정 오차 가능성)',
    no_steering: '핸들 기록이 없어 (진행 방향만으로는 곡선 진입·측정 오차와 구분할 수 없음)',
    curve_or_lc: '핸들이 꺾인 채 유지돼 있어 (곡선에 들어선 것인지 진로를 바꾸던 중인지 구분할 수 없음)',
    bend_hold: '핸들은 가운데로 돌아왔는데 진행 방향이 바뀐 채 유지돼 있어 (굽은 길을 따라간 움직임으로 보임)',
    borderline: '차로 유지와 차로변경의 경계에 있어',
    baseline_sensitive: '도로 방향 기준을 조금만 바꿔도 결과가 달라져',
    short_for_speed: '빠르게 달릴 때는 진로변경이 10초 가까이 걸릴 수 있는데 충돌 전 기록이 짧아',
    late_reference: '기록 앞부분이 고르지 않아 도로 방향 기준을 충돌 직전 구간에서 잡을 수밖에 없어',
    opposite_pair: '바로 앞에 반대쪽으로 크게 틀어진 움직임이 붙어 있어 (S자 회피·연속 움직임이거나 도로 방향 기준이 치우친 경우)',
    heading_lag: '위치 기반 진행 방향이 핸들보다 늦게 반응해 충돌 직전 움직임이 아직 방향 기록에 다 담기지 않아',
    road_change: '기록 중에 도로가 굽었다가 펴지는 등 도로 모양이 바뀌어 옆 이동을 잴 기준이 없어',
    steer_activity: '진행 방향은 차로를 지킨 것처럼 보여도 그동안 핸들이 도로를 따라갈 때보다 크게 움직여 (굽기가 바뀌는 길이거나 진로변경 중일 수 있음)',
  };
  const UNMEASURABLE_KO = {
    missing: (m) => `위치·방향 기록이 부족해 (결측 약 ${Math.round(Math.max(m.quality.headingMissing, m.quality.speedMissing) * 100)}%) 차로변경 여부를 측정할 수 없음`,
    short_buffer: (m) => `충돌 전 기록이 ${m.bufferSec}초뿐이라 차로변경 여부를 측정할 수 없음`,
    too_few_frames: () => '충돌 전 차량 기록이 거의 없어 차로변경 여부를 측정할 수 없음',
    impact_unknown: (m) => (m && m.impact && /^airbag_/.test(m.impact.reason || '')
      ? '에어백이 터진 시각이 기록 범위 밖이라 충돌 순간을 알 수 없어 차로변경 여부를 측정하지 않았음 — 누가 진로를 바꿨는지는 블랙박스 영상으로 확인해야 함'
      : '충돌 순간을 차가 직접 감지하지 못해(음성·버튼 신고) 차로변경 여부를 측정하지 않았음 — 누가 진로를 바꿨는지는 블랙박스 영상으로 확인해야 함'),
  };
  // 진술에서 차량 번호·전화번호를 지운다 (판정 모델에 보내기 전)
  function redact(text) {
    return String(text || '')
      .replace(/\d{2,3}\s?[가-힣]\s?\d{4}/g, '[차량번호]')
      .replace(/01[016789][-\s.]?\d{3,4}[-\s.]?\d{4}/g, '[전화번호]');
  }

  // 방향지시등 문장: 진로변경으로 단정하지 않고 "그 움직임" 기준으로 쓴다
  function signalSentence(s, d) {
    switch (s.status) {
      case 'none': return `그 움직임 동안 ${d} 방향지시등은 켜지 않았음${s.note ? ` (${s.note})` : ''}`;
      case 'after': return `움직이기 시작하고 ${Math.abs(s.leadSec)}초 뒤에야 ${d} 방향지시등을 켰음`;
      case 'at_start': return `움직이기 시작하면서 거의 동시에 ${d} 방향지시등을 켰음 (진로변경 법정 기준인 ${s.requiredM}m 전보다 늦음)`;
      case 'late': return `움직이기 약 ${s.leadM}m 전에 ${d} 방향지시등을 켰음 (진로변경 법정 기준 ${s.requiredM}m보다 짧음)`;
      case 'borderline': return `움직이기 약 ${s.leadM}m 전에 ${d} 방향지시등을 켰음 (${s.note || `진로변경 법정 기준 ${s.requiredM}m 안팎이라 측정 오차 범위에서 충족 여부를 단정할 수 없음`})`;
      case 'ok': return `움직이기 약 ${s.leadM}m 전부터 ${d} 방향지시등을 켜고 있었음 (진로변경 법정 기준 ${s.requiredM}m 이상)`;
      case 'indeterminate': return `${d} 방향지시등은 기록 시작 시점부터 켜져 있었음 (켠 시점은 기록 밖이라 확인 불가)`;
      default: return null;
    }
  }

  // 확인 질문은 기록이 보여 준 방향으로 묻는다. 답은 그 질문에 대한 답으로만 적는다(방향이 틀린 질문에 "아니요"일 수 있다).
  const confirmQuestion = (dir) => (DIR_KO[dir] ? `그때 ${DIR_KO[dir]} 차로로 진로를 바꾸던 중이었나요?` : '그때 차로를 바꾸던 중이었나요?');
  function confirmationText(ctx, m) {
    if (ctx.laneChangeConfirmed !== true && ctx.laneChangeConfirmed !== false) return null;
    const dir = m && m.laneChange ? m.laneChange.direction : null;
    return `운전자 확인 답변: "${confirmQuestion(dir)}"에 "${ctx.laneChangeConfirmed ? '예' : '아니요'}"`;
  }

  function renderFacts(m, ctx) {
    ctx = ctx || {};
    const vehicle = [];
    const lc = m.laneChange;
    const known = !!(m.impact && m.impact.known);
    if (!known) {
      vehicle.push(UNMEASURABLE_KO.impact_unknown(m));
      if (m.endSpeedKph != null) vehicle.push(m.stoppedAtEnd ? '신고할 때 내 차는 멈춰 있었음' : `신고할 때 내 차 속도는 시속 약 ${m.endSpeedKph}km였음`);
    } else {
      if (m.impactSpeedKph != null) vehicle.push(m.stoppedAtImpact ? '충돌 순간 내 차는 거의 멈춰 있었음' : `충돌 순간 내 차 속도는 시속 약 ${m.impactSpeedKph}km였음`);
      const d = lc.direction ? DIR_KO[lc.direction] : null;
      const lookAlike = '굽은 길이나 차로가 어긋난 곳을 따라갈 때도 기록은 같게 나옴';
      switch (lc.status) {
        case 'detected':
          vehicle.push(lc.endT == null
            ? `충돌 ${sec(lc.startT)}초 전부터 내 차가 ${d}으로 진행 방향을 틀어 옆으로 약 ${lc.lateralM}m 움직이고 있었음 (진행 방향 최대 ${lc.maxHeadingDeg}도) — 차로를 바꿀 때의 움직임과 같지만, ${lookAlike}`
            : `충돌 ${sec(lc.startT)}초 전부터 ${sec(lc.endT)}초 전까지 내 차가 ${d}으로 옆으로 약 ${lc.lateralM}m 움직였음 (진행 방향 최대 ${lc.maxHeadingDeg}도) — 차로를 바꾼 직후의 움직임과 같지만, ${lookAlike}`);
          break;
        case 'completed':
          vehicle.push(`충돌 ${sec(lc.startT)}초 전부터 ${lc.completedSecBefore}초 전까지 내 차가 ${d}으로 옆으로 약 ${lc.lateralM}m 움직였음 (차로를 바꾼 움직임과 같음)${lc.keptLaneAfter ? ' — 그 뒤 충돌까지 옆으로 거의 움직이지 않았음' : ''}`);
          break;
        case 'starting':
          if (lc.reason === 'evasive') {
            vehicle.push(`충돌 ${sec(lc.startT)}초 전부터 내 차가 ${d}으로 방향을 틀면서 브레이크를 밟았음 (옆 이동 약 ${lc.lateralM}m) — 진로변경인지 피하려는 조작인지 기록만으로 구분할 수 없음`);
          } else if (lc.basis === 'steering') {
            vehicle.push(`충돌 직전 ${sec(lc.startT)}초 동안 내 차 핸들이 ${d}으로 꺾여 있었음 (진행 방향 변화 최대 ${lc.maxHeadingDeg}도) — 진로변경을 시작했는지, 피하려고 꺾었는지, 진로변경을 마무리하던 중인지는 기록만으로 구분할 수 없음`);
          } else {
            vehicle.push(`충돌 직전 ${sec(lc.startT)}초 동안 내 차가 ${d}으로 진행 방향을 틀었음 (옆 이동 약 ${lc.lateralM}m, 방향 최대 ${lc.maxHeadingDeg}도) — 진로변경을 시작했는지, 피하려고 핸들을 꺾었는지는 기록만으로 구분할 수 없음`);
          }
          break;
        case 'uncertain':
          vehicle.push(lc.lateralM == null || lc.maxHeadingDeg == null
            ? `${UNCERTAIN_KO[lc.reason] || '차량 기록만으로는'} 차로를 바꿨는지 확정할 수 없음`
            : `최근 기록상 내 차는 옆으로 약 ${lc.lateralM}m 움직이고 진행 방향이 최대 ${lc.maxHeadingDeg}도 바뀌었지만, ${UNCERTAIN_KO[lc.reason] || ''} 차로를 바꿨는지 확정할 수 없음`);
          break;
        case 'too_slow':
          vehicle.push(m.recentSpeedKph != null && m.recentSpeedKph < 2
            ? '충돌 전 4초 동안 내 차는 거의 멈춰 있었음'
            : `충돌 전 내 차는 매우 느리게 움직이고 있어 (최근 4초 평균 시속 약 ${m.recentSpeedKph}km) 차로변경 여부를 측정하지 않음`);
          break;
        case 'unmeasurable':
          vehicle.push((UNMEASURABLE_KO[lc.reason] || UNMEASURABLE_KO.too_few_frames)(m));
          break;
        default:
          vehicle.push(`충돌 전 ${lc.windowSec}초 동안 내 차는 차로를 유지했음 (측면 이동 약 ${lc.lateralM}m, 진행 방향 변화 ${lc.maxHeadingDeg}도)`);
      }
      if (lc.steeringCorroborated === true && (lc.status === 'detected' || lc.status === 'completed')) {
        vehicle.push(`핸들도 ${d}으로 꺾였다가 돌아와 진행 방향 기록과 일치함`);
      }
      if (['detected', 'completed', 'starting'].includes(lc.status)) {
        const s = m.signal.status === 'unavailable' ? '방향지시등 기록이 없어 신호를 켰는지 확인할 수 없음' : signalSentence(m.signal, d);
        if (s) vehicle.push(s);
      } else if (m.signal.recentOn === 'LEFT' || m.signal.recentOn === 'RIGHT') {
        vehicle.push(m.signal.stateAtImpact === m.signal.recentOn
          ? `충돌 순간 내 차의 ${DIR_KO[m.signal.recentOn]} 방향지시등이 켜져 있었음`
          : `충돌 직전 ${THRESH.RECENT_SIGNAL_SEC}초 안에 내 차의 ${DIR_KO[m.signal.recentOn]} 방향지시등이 켜져 있었음`);
      }
      if (m.quality && m.quality.steeringSign === 'reversed') vehicle.push('핸들 각도와 진행 방향 변화의 부호가 반대로 기록돼 있어 핸들 기록을 쓰지 않았음 (차량 연동 설정 확인 필요)');

      const b = m.brake;
      if (b.applied == null) { if (m.impactSpeedKph != null) vehicle.push('브레이크 기록이 없어 확인할 수 없음'); }
      else if (b.applied) {
        if (b.onAtBufferStart && b.releasedSecBefore == null) vehicle.push('기록 시작부터 충돌 순간까지 브레이크를 밟고 있었음');
        else if (b.releasedSecBefore != null) vehicle.push(`충돌 ${b.onsetSecBefore}초 전에 브레이크를 밟았다가 충돌 ${b.releasedSecBefore}초 전에 뗐음`);
        else if (b.onsetSecBefore < 0.1) vehicle.push('충돌 순간에 브레이크를 밟았음');
        else vehicle.push(`충돌 ${b.onsetSecBefore}초 전부터 브레이크를 밟고 있었음`);
      } else if (m.impactSpeedKph != null) vehicle.push('충돌 직전 1.5초 동안 브레이크를 밟지 않았음');
      if (m.maxDecelKphPerS != null && m.maxDecelKphPerS >= 10) vehicle.push(`충돌 직전 급감속이 있었음 (1초에 시속 약 ${Math.round(m.maxDecelKphPerS)}km 감소)`);
    }
    if (m.hazardOn) vehicle.push(known ? '충돌 전 기록에 비상등이 켜진 구간이 있었음' : '신고 전 기록에 비상등이 켜진 구간이 있었음');
    if (m.airbagDeployed) vehicle.push('에어백이 전개됐음');

    const place = [];
    const rt = roadTypeOf(ctx);
    place.push(rt === 'highway' ? '사고 장소는 고속도로' : rt === 'urban' ? '사고 장소는 일반도로' : '도로 유형 정보 없음');
    // 자기 키만 본다 — 'constructor' 같은 상속 속성이 사실 문장에 섞이지 않도록
    for (const c of ctx.navCautions || []) if (Object.prototype.hasOwnProperty.call(CAUTION_KO, c)) place.push(`내비게이션: ${CAUTION_KO[c]}`);

    const statement = redact(String(ctx.driverStatement || '').trim().slice(0, 300));
    return {
      vehicle,
      place,
      driverStatement: statement
        ? `운전자 본인 진술(사고 직후 음성, 확인되지 않은 주장): "${statement}"`
        : '운전자 진술 없음',
      driverConfirmation: confirmationText(ctx, m),
    };
  }

  // ---------- 3. Jev 요청 ----------
  function buildJevRequest(facts, opts = {}) {
    const criteria = {};
    for (const [k, v] of Object.entries(F.CHART_OPTIONS)) criteria[k] = v.criteria;
    const statementOnly = '운전자 진술은 확인되지 않은 본인 주장이므로, 진술 말고 다른 근거(차량 기록·내비게이션 정보)가 없으면 높게 답하지 마세요.';
    const state = {
      vehicle_facts: facts.vehicle,
      place_facts: facts.place,
      driver_statement: facts.driverStatement,
    };
    if (facts.driverConfirmation) state.driver_confirmation = facts.driverConfirmation;
    return {
      model: opts.model || 'jev-latest',
      state,
      questions: {
        chart: {
          type: 'choice',
          instructions: '차량 기록과 장소 정보를 바탕으로 이 사고가 아래 차로변경 사고 유형 중 어디에 해당하는지 고르세요. 어느 유형에도 맞지 않거나 근거가 부족하면 none을 고르세요. 운전자 진술은 확인되지 않은 본인 주장이므로 차량 기록과 어긋나면 차량 기록을 우선하세요. 차량 기록의 "옆으로 움직임"은 굽은 길을 따라간 움직임일 수도 있습니다.',
          criteria,
        },
        evidence_sufficient: {
          type: 'noul',
          instructions: '주어진 차량 기록과 진술만으로 사고 유형을 판단할 근거가 충분하다.',
        },
        self_lane_change: {
          type: 'noul',
          instructions: '운전자가 진술이나 확인 답변에서 사고 직전 자기 차가 진로(차로)를 바꾸고 있었다고 스스로 인정한다. 바꾸려고 했다·기다렸다처럼 의도만 말한 경우, 바꾸지 않았다고 부정한 경우, 상대 차 이야기만 한 경우, 남의 주장을 옮긴 경우("상대가 제가 끼어들었다고 우겨요"), 가정·질문("바꿨으면", "바꾼 거냐고")은 해당하지 않는다.',
        },
        other_signal_fail: {
          type: 'noul',
          instructions: `상대 차가 방향지시등을 켜지 않았거나 늦게 켜고 진로를 바꿨다는 구체적인 근거가 있다. ${statementOnly}`,
        },
        forbidden_zone: {
          type: 'noul',
          instructions: `진로를 바꾼 차가 진로변경이 금지된 곳(백색 실선 구간 등)에서 진로를 바꿨다는 구체적인 근거가 있다. ${statementOnly}`,
        },
        bus_lane: {
          type: 'noul',
          instructions: `진로를 바꾼 차가 버스·다인승 전용차로를 위반했다는 구체적인 근거가 있다. ${statementOnly}`,
        },
      },
    };
  }

  // ---------- 4. 과실 계산 ----------
  function decide(p) {
    if (p == null) return 'not_assessed';
    if (p >= THRESH.YES) return 'applied';
    if (p >= THRESH.MAYBE) return 'possible';
    return 'not_applied';
  }
  const clamp = (x) => Math.max(0, Math.min(100, x));

  // 차량 기록이 고른 유형을 뒷받침하는지. 맞지 않으면 판단 보류 사유를 돌려준다.
  function recordConflict(choice, m, ctx) {
    ctx = ctx || {};
    if (choice === 'none') return null;
    const opt = F.CHART_OPTIONS[choice];
    const lc = m.laneChange;
    const needsMyLC = opt.me === 'B' || opt.chart === '252-2';
    // 충돌 직전에 시작한 움직임(starting)은 진로변경 시작인지 피하려는 조작인지 기록으로 못 가린다 — 운전자가 "예"라고 답하면 진로변경으로 본다.
    // 그 무렵 브레이크까지 밟았으면(evasive) 예/아니요 답으로도 가릴 수 없어 숫자를 내지 않는다.
    if (lc.status === 'starting' && lc.reason !== 'evasive' && needsMyLC && ctx.laneChangeConfirmed === true) return null;
    switch (lc.status) {
      case 'detected':
        return needsMyLC ? null : '차량 기록에는 내 차가 차로를 바꾸는 움직임이 있는데, 판정은 상대 차만 진로를 바꾼 사고로 나와 서로 맞지 않음';
      case 'none':
        return needsMyLC ? '판정은 내 차의 진로변경으로 나왔지만, 차량 기록에는 내 차가 차로를 유지한 것으로 나와 서로 맞지 않음' : null;
      case 'completed':
        return `내 차는 충돌 ${lc.completedSecBefore}초 전에 옆으로 움직이기를 마쳤음 — 진로변경 사고인지 추돌 사고인지 기록만으로 가리기 어려움`;
      case 'starting':
        return lc.reason === 'evasive'
          ? '충돌 직전 핸들 조작과 함께 브레이크를 밟아, 피하려는 조작인지 진로변경인지 가릴 수 없음'
          : '충돌 직전 핸들 조작이 진로변경 시작인지 피하려는 조작인지 기록만으로 구분하기 어려움';
      case 'uncertain':
        return lc.reason === 'curve_or_lc' ? '곡선에 들어선 것인지 진로를 바꾸던 중인지 차량 기록만으로 구분할 수 없음'
          : lc.reason === 'bend_hold' ? '진행 방향이 바뀐 채 유지돼 굽은 길을 따라간 움직임으로 보여 진로변경으로 단정할 수 없음'
          : lc.reason === 'no_steering' ? '핸들 기록이 없어 차로변경 여부를 확정할 수 없음'
          : lc.reason === 'short_for_speed' ? `빠르게 달릴 때는 진로변경이 10초 가까이 걸릴 수 있는데 충돌 전 기록이 ${m.bufferSec}초뿐이라 차로를 유지했다고 확정할 수 없음`
          : lc.reason === 'road_change' ? '기록 중에 도로가 굽었다가 펴지는 등 도로 모양이 바뀌어, 차로를 바꿨는지 차량 기록만으로 확정할 수 없음'
          : lc.reason === 'heading_lag' ? '진행 방향 기록이 핸들보다 늦게 반응해, 충돌 직전 움직임을 아직 다 볼 수 없음'
          : lc.reason === 'steer_activity' ? '진행 방향은 차로를 지킨 것처럼 보이지만 핸들이 도로를 따라갈 때보다 크게 움직여, 차로를 지켰다고 확정할 수 없음'
          : '차량 기록만으로는 내 차가 차로를 바꿨는지 확정할 수 없음';
      case 'too_slow':
        return '거의 멈춘 상태여서 차량 기록으로 누가 진로를 바꿨는지 측정할 수 없음';
      default:
        return lc.reason === 'impact_unknown' ? '충돌 순간을 차가 직접 감지하지 못해 차로변경 여부를 측정하지 않았음'
          : lc.reason === 'short_buffer' ? '충돌 전 기록이 짧아 차로변경 여부를 측정할 수 없음'
          : '차량 기록이 부족해 차로변경 여부를 측정할 수 없음';
    }
  }

  // 운전자 확인 — 숫자는 차량 기록과 확인 질문의 답이 같은 이야기를 할 때만 낸다.
  //  - "내 차 진로변경(B)": 기록에 진로변경 같은 움직임(detected·starting, 방향 있음)이 있고, 방향을 넣은 질문
  //    "그때 오른쪽 차로로 진로를 바꾸던 중이었나요?"에 "예"(ctx.laneChangeConfirmed === true).
  //    굽은 길·차로 어긋남은 기록이 똑같이 보이고, 진술 해석은 부정·질문·남의 말을 잘못 읽을 수 있기 때문이다.
  //  - "상대 진로변경(A)": 기록이 차로 유지(none)이고, "그때 차로를 바꾸던 중이었나요?"에 "아니요"(false).
  //    곡선이 바뀌는 곳에서 천천히 진로를 바꾸면 기록이 차로 유지처럼 보일 수 있기 때문이다. 진술에서 자기 진로변경을 말했거나,
  //    충돌 직전 내 방향지시등이 켜져 있었거나, 방향지시등 기록이 없으면 답과 관계없이 내지 않는다(진술 해석은 막는 쪽으로만 쓴다).
  //  - 브레이크를 함께 밟은 충돌 직전 조향(starting/evasive)은 예/아니요로도 가릴 수 없어 묻지 않는다.
  function driverCheck(choice, m, ctx, answers) {
    const lc = m.laneChange;
    const selfP = answers && answers.self_lane_change ? answers.self_lane_change.noul : null;
    const yes = ctx.laneChangeConfirmed === true;
    const no = ctx.laneChangeConfirmed === false;
    const statedSelf = selfP != null && selfP >= THRESH.SELF_LC;
    const q = confirmQuestion(lc.direction);
    const out = { reason: null, askable: false, question: q, hint: null };
    if (choice === 'none' || !F.CHART_OPTIONS[choice]) return out;
    const opt = F.CHART_OPTIONS[choice];
    if (opt.me === 'B' || opt.chart === '252-2') {
      // 기록이 내 진로변경 같은 움직임을 보이지 않으면 묻지 않는다 (그 이유는 recordConflict 가 낸다)
      const movement = ['detected', 'starting'].includes(lc.status) && !!lc.direction && lc.reason !== 'evasive';
      if (!movement) return out;
      if (no) out.reason = `운전자는 "${q}"에 "아니요"라고 답했음 — 기록의 움직임은 굽은 길·차로가 어긋난 곳을 따라간 것이거나 다른 움직임일 수 있어, 블랙박스 영상으로 확인해야 함`;
      else if (!yes) {
        out.reason = `기록상 내 차가 ${DIR_KO[lc.direction]}으로 움직였지만, 굽은 길이나 차로가 어긋난 곳을 따라가도 기록이 똑같이 보여 운전자 확인이 필요함`;
        out.askable = true;
        out.hint = confirmHint(lc);
      }
    } else if (opt.me === 'A') {
      if (yes) out.reason = `운전자는 "${q}"에 "예"라고 답했는데 기록상으로는 차로를 유지한 것으로 나와 서로 맞지 않음`;
      else if (statedSelf) out.reason = '운전자는 진술에서 차로를 바꾸던 중이었다고 했는데 기록상으로는 차로를 유지한 것으로 나와 서로 맞지 않음';
      else if (!m.quality || !m.quality.signalAvailable) out.reason = '방향지시등 기록이 없어 충돌 직전 내 차가 진로를 바꾸려 했는지 확인할 수 없음';
      else if (m.signal && (m.signal.recentOn === 'LEFT' || m.signal.recentOn === 'RIGHT')) {
        out.reason = `충돌 직전 ${THRESH.RECENT_SIGNAL_SEC}초 안에 내 차의 ${DIR_KO[m.signal.recentOn]} 방향지시등이 켜져 있어, 기록상 차로 유지로 보여도 진로를 바꾸기 시작했을 수 있음`;
      } else if (lc.status === 'none' && !no) {
        out.reason = '기록상 내 차는 차로를 유지한 것으로 보이지만, 곡선이 바뀌는 곳 등에서는 천천히 진로를 바꿔도 이렇게 보일 수 있어 운전자 확인이 필요함';
        out.askable = true;
      }
    }
    return out;
  }

  // 확인 질문에 덧붙이는 말 (화면에 보이고 함께 읽는다). 질문의 뜻만 분명히 하고 어느 답도 권하지 않는다.
  //  - starting: 충돌 직전에 시작한 움직임은 피하려는 조작과 구분이 안 된다
  //  - detected: 굽은 길·차로가 어긋난 곳을 따라가도 기록이 똑같이 보인다
  const confirmHint = (lc) => (lc.status === 'starting' ? '피하려고 핸들을 꺾은 거라면 "아니요"'
    : lc.status === 'detected' ? '굽은 길이나 차로를 따라 달린 거라면 "아니요"' : null);
  const DIRECTION_CAVEAT_KO = {
    opposite_signal: '움직임과 반대쪽 방향지시등이 켜져 있어 움직인 방향을 잘못 쟀을 수 있어',
    opposite_move: '바로 앞에 반대쪽으로 움직인 기록이 있어 움직인 방향을 잘못 쟀을 수 있어',
    road_curve: '굽은 길에서는 움직인 방향을 잘못 잴 수 있어',
    late_reference: '도로 방향 기준을 충돌 무렵 구간에서 잡아 움직인 방향을 잘못 잴 수 있어',
  };

  const hasCaution = (ctx, list) => (ctx.navCautions || []).some((c) => list.includes(c));
  const IMPACT_UNKNOWN_REASON = '충돌 순간을 차가 직접 감지하지 못해(음성·버튼 신고) 과실 범위를 계산하지 않음 — 기록 속 움직임이 사고 전인지, 사고 뒤 갓길로 옮긴 것인지 가릴 수 없음';
  const IMPACT_UNKNOWN_AIRBAG_REASON = '에어백이 터진 시각이 기록 범위 밖이라 충돌 순간을 알 수 없어 과실 범위를 계산하지 않음';

  function computeFault(answers, m, ctx) {
    ctx = ctx || {};
    const ch = (answers && answers.chart) || {};
    const probs = ch.probabilities || {};
    const ranked = Object.entries(probs).filter(([k]) => Object.prototype.hasOwnProperty.call(F.CHART_OPTIONS, k)).sort((a, b) => b[1] - a[1]);
    let choice = ch.choice && Object.prototype.hasOwnProperty.call(F.CHART_OPTIONS, ch.choice) ? ch.choice : (ranked[0] && ranked[0][0]);
    if (!choice) choice = 'none';
    const pOf = (k) => (probs[k] != null ? probs[k] : 0);
    // 252-2(동시 진로변경)는 "상대도 동시에 바꿨다"는 진술에만 기대는 유형이다. 내 진로변경은 기록과 운전자 확인으로 정하므로
    // 252(내 차 B)로 계산하고, 252-2는 범위의 아래쪽 끝으로만 반영한다.
    const folded = choice === 'c252_2';
    const pChoice = folded ? pOf('c252_2') + pOf('c252_me_B') : pOf(choice);
    const evidence = answers && answers.evidence_sufficient ? answers.evidence_sufficient.noul : null;

    const reasons = [];
    let confirm = null;
    if (!m.impact || !m.impact.known) {
      reasons.push(m.impact && /^airbag_/.test(m.impact.reason || '') ? IMPACT_UNKNOWN_AIRBAG_REASON : IMPACT_UNKNOWN_REASON);
    } else {
      if (choice === 'none') reasons.push('사고 유형이 차로변경 사고 도표에 해당하지 않거나 판단 근거가 부족함');
      if (choice !== 'none' && pChoice < THRESH.CHART_MIN) reasons.push(`사고 유형 판단 확률이 낮음 (${round(pChoice * 100)}%)`);
      if (evidence != null && evidence < THRESH.EVIDENCE_MIN) reasons.push('차량 기록과 진술만으로는 근거가 부족함');
      const unresolved = reasons.length; // 답으로 풀리지 않는 이유 (유형 없음·확률 낮음·근거 부족)
      const conflict = recordConflict(choice, m, ctx);
      if (conflict) reasons.push(conflict);
      else if (choice === 'none' && ['completed', 'starting', 'uncertain', 'unmeasurable'].includes(m.laneChange.status)) {
        const why = recordConflict('c252_me_B', m, ctx);
        if (why) reasons.push(why);
      }
      const g = driverCheck(choice, m, ctx, answers);
      if (g.reason) reasons.push(g.reason);
      // 확인 질문은 답이 결과를 바꿀 수 있을 때만 한다.
      //  - 기록에 진로변경 같은 움직임이 있으면(방향 있음, 회피 아님) 판정이 아직 유형을 못 정했어도 묻는다 — "예"면 판정이 달라진다.
      //    단, 판정이 차로변경 도표 밖(교차로·주차장 등)이라고 거의 확신하면 묻지 않는다.
      //  - 기록이 차로 유지이면 판정이 "상대 진로변경"을 골랐고, "아니요"를 넣었을 때 다른 이유로 막히지 않을 때만 묻는다.
      const lcNow = m.laneChange;
      const unanswered = ctx.laneChangeConfirmed !== true && ctx.laneChangeConfirmed !== false;
      const movement = ['detected', 'starting'].includes(lcNow.status) && !!lcNow.direction && lcNow.reason !== 'evasive';
      if (unanswered && movement && pOf('none') < 0.8) {
        confirm = { question: confirmQuestion(lcNow.direction), direction: lcNow.direction, hint: confirmHint(lcNow) };
      } else if (g.askable && !unresolved && F.CHART_OPTIONS[choice].me === 'A' && F.CHART_OPTIONS[choice].chart !== '252-2') {
        const hypo = { ...ctx, laneChangeConfirmed: false };
        if (!recordConflict(choice, m, hypo) && !driverCheck(choice, m, hypo, answers).reason) {
          confirm = { question: g.question, direction: null, hint: null };
        }
      }
    }

    if (reasons.length) {
      return {
        status: 'abstain',
        option: choice,
        reasons: [...new Set(reasons)],
        confidence: round(pChoice, 2),
        evidenceChecklist: evidenceChecklist(m, ctx),
        confirm,
      };
    }

    const effChoice = folded ? 'c252_me_B' : choice;
    const simultaneous = folded || (effChoice === 'c252_me_B' && pOf('c252_2') >= THRESH.ALT_MIN);
    const opt = F.CHART_OPTIONS[effChoice];
    const chart = F.CHARTS[opt.chart];
    const base = chart.base[opt.me];
    const caveats = [];
    const evals = [];

    const partyKo = (party) => (party === opt.me ? '내 차' : '상대 차');
    if (!chart.source.verified) {
      caveats.push(`이 유형은 기본과실(${chart.base.A}:${chart.base.B})만 반영했어요. 가감 요소는 협회 원문을 확인한 뒤 반영합니다.`);
      for (const h of chart.pendingHints || []) caveats.push(`확인 예정 가감 요소: ${h}`);
    } else {
      for (const mod of chart.modifiers) {
        let p = null, source = null, basis = [], why = '';
        if (mod.id === 'B_signal') {
          if (opt.me === 'B') {
            source = 'vehicle'; basis = ['vehicle'];
            const st = m.signal.status;
            // 기록이 없거나(unavailable) 켠 시점이 기록 밖이면(indeterminate) 위반 여부를 모른다 → 범위에만 넣는다 (0.5)
            p = ['none', 'late', 'after', 'at_start'].includes(st) ? 1 : ['borderline', 'unavailable', 'indeterminate'].includes(st) ? 0.5 : 0;
            why = st === 'unavailable' ? '방향지시등 기록이 없어 확인할 수 없음 — 범위에만 반영'
              : st === 'ok' ? `방향지시등 ${m.signal.leadM}m 전 점등 (기준 ${m.signal.requiredM}m 충족)`
              : st === 'borderline' ? (m.signal.leadM ? `방향지시등 ${m.signal.leadM}m 전 점등 — 기준 ${m.signal.requiredM}m 안팎(측정 오차 범위)` : '움직이기 시작한 무렵 점등 — 시작 시점의 측정 오차 범위')
              : st === 'indeterminate' ? '점등 시점이 기록 밖이라 기준 충족 여부를 알 수 없음 — 범위에만 반영'
              : st === 'none' ? '방향지시등 미점등' : '방향지시등 점등이 늦음';
            // 충돌 직전에 시작한 움직임(starting)은 진로변경인지, 시작 시점이 언제인지 기록으로 정확히 잴 수 없다 → 신호 가감은 범위에만
            if (m.laneChange.status === 'starting') {
              p = 0.5;
              why += ' — 충돌 직전에 시작한 움직임이라 시작 시점을 정확히 잴 수 없어 범위에만 반영';
            } else if (m.laneChange.status === 'detected' && m.laneChange.directionCaveat && p === 1) {
              // 움직인 방향을 잘못 잴 수 있는 기록이면 "그쪽 신호를 켜지 않음"을 확정하지 않는다 → 범위에만
              p = 0.5;
              why += ` — ${DIRECTION_CAVEAT_KO[m.laneChange.directionCaveat] || '움직인 방향을 잘못 잴 수 있어'} 범위에만 반영`;
            }
          } else {
            source = 'model'; basis = ['statement'];
            p = answers.other_signal_fail ? answers.other_signal_fail.noul : null;
            why = '상대 차 방향지시등은 내 차가 측정할 수 없음 — 진술 해석';
          }
        } else if (mod.id === 'B_forbidden') {
          source = 'model';
          basis = hasCaution(ctx, ['LANE_CHANGE_CAMERA_ALERT', 'CUT_IN_VIOLATION_ENFORCEMENT']) ? ['statement', 'nav'] : ['statement'];
          p = answers.forbidden_zone ? answers.forbidden_zone.noul : null;
          why = '진로변경 금지장소 — 진술·내비 정보 해석';
        } else if (mod.id === 'B_buslane') {
          source = 'model';
          basis = hasCaution(ctx, ['BUS_LANE_AVAILABLE_NOTICE', 'BUS_LANE_VIOLATION_ENFORCEMENT']) ? ['statement', 'nav'] : ['statement'];
          p = answers.bus_lane ? answers.bus_lane.noul : null;
          why = '전용차로 위반 — 진술·내비 정보 해석';
        } else {
          why = '현저한 과실·중과실(과속·음주·휴대전화 등)은 차량 기록만으로 판단하지 않음';
        }
        let decision = decide(p);
        // 판정 모델의 해석(진술·내비 구간 안내)은 충돌 지점의 위반을 직접 보여주지 못한다 → "반영"하지 않고 범위에만 넣는다.
        // 확정(반영)은 차량이 직접 잰 항목만 한다.
        if (decision === 'applied' && source === 'model') {
          decision = 'possible';
          why += ' (차량이 직접 잰 값이 아니라 범위에만 반영)';
        }
        const mine = mod.party === opt.me;
        evals.push({
          id: mod.id, label: mod.label.replace(/^[AB] /, `${partyKo(mod.party)} `), chartLabel: mod.label,
          party: mod.party, delta: mod.delta,
          p: p == null ? null : round(p, 2), source, basis, decision, why,
          effectOnMe: mine ? +mod.delta : -mod.delta,
        });
      }
    }

    // 같은 그룹(현저/중과실)은 중복 적용하지 않는다 — 반영 > 가능성 순, 같으면 큰 것 하나만
    const rank = { applied: 2, possible: 1 };
    const keep = {};
    for (const e of evals) {
      const mod = chart.modifiers.find((x) => x.id === e.id);
      if (!mod.group || !rank[e.decision]) continue;
      const k = keep[mod.group];
      if (!k || rank[e.decision] > rank[k.decision] || (rank[e.decision] === rank[k.decision] && e.delta > k.delta)) keep[mod.group] = e;
    }
    for (const e of evals) {
      const mod = chart.modifiers.find((x) => x.id === e.id);
      if (mod.group && rank[e.decision] && keep[mod.group] !== e) e.decision = 'not_applied';
    }

    let likely = base, up = 0, down = 0;
    for (const e of evals) {
      if (e.decision === 'applied') likely += e.effectOnMe;
      else if (e.decision === 'possible') { if (e.effectOnMe > 0) up += e.effectOnMe; else down += -e.effectOnMe; }
    }
    likely = clamp(likely);
    let low = clamp(likely - down);
    const high = clamp(likely + up);
    if (simultaneous) {
      const simBase = F.CHARTS['252-2'].base.A;
      if (simBase < low) low = simBase;
      evals.push({
        id: 'simultaneous', label: '상대 차도 동시에 진로변경 (도표 252-2, 기본 50:50)', chartLabel: '252-2',
        party: 'B', delta: null, p: round(pOf('c252_2'), 2), source: 'model', basis: ['statement'], decision: 'possible',
        why: '상대 차의 진로변경은 내 차가 측정할 수 없음 — 진술 해석', effectOnMe: simBase - likely,
      });
      caveats.push('상대 차의 진로변경은 내 차가 잴 수 없어, 동시 진로변경(252-2)은 범위의 아래쪽 끝으로만 넣었어요');
    }
    if (opt.me === 'B') {
      caveats.push(`"${confirmQuestion(m.laneChange.direction)}"에 "예"라고 답한 것을 바탕으로 계산했어요`);
      const said = F.parseStatement ? F.parseStatement(ctx.driverStatement) : null;
      if (said && said.selfDeny) caveats.push('진술(차로를 바꾸지 않았다)과 확인 답(예)이 서로 달라요 — 확인 답을 따랐어요');
      if (m.signal.status === 'unavailable') caveats.push('방향지시등 기록이 없어 신호 위반 여부는 범위에만 넣었어요');
    } else if (opt.me === 'A') {
      caveats.push(`"${confirmQuestion(null)}"에 "아니요"라고 답한 것과 차로를 유지한 기록을 바탕으로 계산했어요`);
    }
    const alt = ranked.find(([k, p]) => k !== effChoice && k !== 'none' && !(simultaneous && k === 'c252_2') && !(folded && k === 'c252_me_B') && p >= THRESH.ALT_MIN);
    const pNone = pOf('none');
    if (pNone >= THRESH.ALT_MIN) caveats.push(`차로변경 사고 도표에 해당하지 않을 가능성도 ${round(pNone * 100)}% 있어요`);
    return {
      status: 'estimate',
      option: effChoice,
      judgedOption: choice,
      chartId: chart.id,
      chartTitle: chart.title,
      optionLabel: opt.label + (simultaneous ? ' · 동시 진로변경 가능성 포함' : ''),
      me: opt.me,
      other: opt.other,
      base,
      likely,
      low,
      high,
      modifiers: evals,
      confidence: round(pChoice, 2),
      alternative: alt ? { option: alt[0], p: round(alt[1], 2), title: F.CHART_OPTIONS[alt[0]].label } : null,
      verified: chart.source.verified,
      source: chart.source,
      caveats,
    };
  }

  function evidenceChecklist(m, ctx) {
    ctx = ctx || {};
    const evacuate = roadContext(m, ctx) !== 'urban';
    const list = [
      '내 블랙박스 원본 보존 (메모리카드 덮어쓰기 방지)',
      '상대 차 블랙박스 영상 공유 요청',
      evacuate ? '현장 사진은 대피한 뒤 경찰·보험사 안내에 따라 안전할 때만' : '차량 최종 위치·파손 부위·노면 표시(실선/점선) 사진',
    ];
    if (m.laneChange.status !== 'detected') list.push('누가 차로를 바꿨는지 보여줄 영상이나 목격자 연락처');
    if (!evacuate) list.push('주변 상가·교차로 CCTV 위치 기록 (보관 기간이 짧음)');
    return list;
  }

  // ---------- 5. 현장 30초 동반자 ----------
  // 도로 맥락: 내비가 고속도로라고 하면 'highway'. 일반도로라도 최근 20초 안 최고 속도가 시속 65km를 넘으면(도시고속도로 등)
  // 'caution' — 사진보다 대피를 먼저 말한다. 도로 유형을 모르면 최고 속도로 가늠하고(80km 이상이면 고속도로처럼), 속도도 모르면 'caution'.
  function roadContext(m, ctx) {
    ctx = ctx || {};
    const rt = roadTypeOf(ctx);
    if (rt === 'highway') return 'highway';
    const peak = m.peakSpeedKph;
    // 음성·버튼 신고는 사고 뒤에 시작할 수 있어, 기록 내내 멈춰 있었으면 그 속도로 도로를 가늠하지 않는다
    const informative = peak != null && ((m.impact && m.impact.known) || peak >= THRESH.STILL_KPH);
    if (!informative) return 'caution';
    if (rt === 'urban') return peak <= THRESH.PHOTO_MAX_KPH ? 'urban' : 'caution';
    // 도로 유형을 모르면 사진을 먼저 권하지 않는다 — 시속 80km 이상이면 고속도로 안내, 아니면 "빠른 도로일 수 있음"
    return peak >= THRESH.FAST_KPH ? 'highway' : 'caution';
  }
  function respondMode(m, ctx) {
    ctx = ctx || {};
    if (m.airbagDeployed || ctx.injuryReported) return 'emergency';
    return roadContext(m, ctx);
  }

  function safetyCard(m, ctx) {
    const mode = respondMode(m, ctx);
    const road = roadContext(m, ctx);
    const evacuate = road !== 'urban';
    const withPassengers = Number(ctx.occupants) > 0; // 운전석 말고 사람이 앉은 좌석 수 (Vehicle SDK 좌석 착석)
    const hurt = withPassengers ? '나와 동승자가 다쳤는지 확인' : '다친 사람이 있는지 확인';
    if (mode === 'emergency') {
      const cause = m.airbagDeployed ? '에어백이 터졌어요.' : '다친 사람이 있다고 하셨어요.';
      // 빠른 도로에서는 움직일 수 있으면 대피가 먼저이고, 신고는 대피한 곳에서 한다. 움직이기 어려우면 그 자리에서 바로 신고.
      return evacuate ? {
        title: '먼저 사람부터',
        lines: [
          '움직일 수 있으면 비상등을 켜고 가드레일 밖으로 먼저 대피',
          '대피한 곳에서 119에 신고 (위치는 내비게이션 화면이나 주변 이정표로 확인)',
          '움직이기 어려우면 그 자리에서 바로 119에 신고하고 구조를 기다리기',
          ...(withPassengers ? ['동승자도 함께 대피했는지 확인'] : []),
        ],
        tts: `${cause} 움직일 수 있으면 먼저 가드레일 밖으로 대피한 뒤 119에 신고하세요. 움직이기 어려우면 그 자리에서 바로 119에 신고하세요.`,
      } : {
        title: '먼저 사람부터',
        lines: [hurt, '119에 바로 신고 (위치는 내비게이션 화면에서 확인해 알려 주기)', '움직이기 어려우면 무리해서 내리지 않기', '안전하면 비상등 켜기'],
        tts: `${cause} 다친 곳이 있는지 먼저 확인하고, 바로 119에 신고하세요.`,
      };
    }
    if (mode === 'highway') {
      // 내비가 고속도로라고 했을 때만 "고속도로예요"라고 말한다 (속도로 가늠한 경우는 "빠른 도로")
      const sure = roadTypeOf(ctx) === 'highway';
      return {
        title: '비트박스로 2차 사고 막기',
        lines: ['비상등 켜기', '트렁크 열기', withPassengers ? '동승자와 함께 가드레일 밖으로 대피' : '가드레일 밖으로 대피', '안전한 곳에서 신고 (한국도로공사 1588-2504 긴급견인)'],
        tts: `${sure ? '고속도로예요.' : '차가 빠르게 다니는 도로예요.'} 비상등을 켜고 트렁크를 연 다음, 가드레일 밖으로 먼저 대피하세요.`,
      };
    }
    if (mode === 'caution') {
      return {
        title: '먼저 안전한 곳으로',
        lines: ['비상등 켜기', hurt, '차가 빠르게 다니는 도로(고속도로·자동차 전용도로)라면 사진보다 대피가 먼저 — 가드레일 밖으로',
          '차가 느린 일반도로에서 안전하면 사진을 찍고 차를 갓길로 옮기기'],
        tts: '비상등을 켜고 다친 사람이 없는지 확인하세요. 차가 빠르게 다니는 도로라면 사진보다 대피가 먼저예요.',
      };
    }
    return {
      title: '안전 확보 후 기록',
      lines: ['비상등 켜기', hurt, '안전하면 사진부터 찍고 갓길로 이동'],
      tts: '비상등을 켜고, 다친 사람이 없는지 먼저 확인하세요.',
    };
  }

  const SPEECH_GUIDE = {
    say: ['다치신 데는 없으세요?', '사진 먼저 찍고 보험 접수할게요.', '연락처랑 보험사 정보 교환해요.'],
    sayHighway: ['다치신 데는 없으세요?', '일단 가드레일 밖으로 피해요.', '보험사·경찰은 안전한 데서 부를게요.'],
    sayCaution: ['다치신 데는 없으세요?', '일단 차 밖 안전한 곳으로 피해요.', '보험사·경찰은 안전한 데서 부를게요.'],
    avoid: [
      '"제가 다 잘못했어요" 같은 책임 단정 발언',
      '현장에서 과실 비율을 두고 다투기',
      '기록 없이 "그냥 가세요" 하고 헤어지기',
      '블랙박스 전원 끄기·영상 지우기',
    ],
    principle: '퍼스트30은 다친 척이나 과장 신고를 돕지 않습니다. 실제로 아프면 진료를 받으세요.',
  };

  function faultCard(result, m, ctx, mode) {
    const evacuate = roadContext(m, ctx) !== 'urban';
    const usedStandIn = result.judge && result.judge.usedStandIn;
    const judgeLine = usedStandIn
      ? [`판정 모델 대신 규칙 기반 대체 판정을 썼어요${result.judge.fallback ? ' (판정 모델 연결 실패)' : ''}`] : [];
    const quiet = mode === 'emergency' ? '과실 참고 정보는 화면에 남겨 둘게요. 안전이 확인된 뒤에 보세요.' : null;
    if (result.status === 'estimate') {
      const range = result.low === result.high ? `${result.likely}%` : `${result.low}~${result.high}% (가능성 높은 값 ${result.likely}%)`;
      const title = result.verified
        ? `예상 내 과실 ${range}`
        : `예상 내 과실 ${result.base}% (기본과실만 · 가감 요소 미반영)`;
      const roleNote = F.CHARTS[result.chartId].base.A === F.CHARTS[result.chartId].base.B ? '' : ` · 내 차는 ${result.me}`;
      return {
        kind: 'estimate', title: mode === 'emergency' ? `안전이 확인되면 보세요 · ${title}` : title,
        lines: [
          `유형: ${result.chartTitle}${roleNote} — 기본 내 과실 ${result.base}%`,
          ...result.modifiers.filter((e) => e.decision === 'applied' || e.decision === 'possible')
            .map((e) => `${e.decision === 'applied' ? '반영' : '가능성'}: ${e.label}${e.id === 'simultaneous' ? ` (내 과실 ${F.CHARTS['252-2'].base.A}%까지)` : ` (내 과실 ${e.effectOnMe > 0 ? '+' : ''}${e.effectOnMe}%p)`}`),
          ...(result.alternative ? [`다른 해석 가능성: ${result.alternative.title} (${Math.round(result.alternative.p * 100)}%)`] : []),
          ...result.caveats,
          ...judgeLine,
          '과속·음주·휴대전화 같은 현저한 과실·중과실은 반영하지 않았어요',
          '손해보험협회 인정기준에 근거한 참고치이며, 최종 비율은 보험사·분쟁심의위원회가 정합니다',
        ],
        tts: quiet
          || (result.verified
            ? `예상 과실은 ${result.low === result.high ? `${result.likely}퍼센트` : `${result.low}에서 ${result.high}퍼센트 사이`}예요. 참고용이니 현장에서는 다투지 마세요.`
            : `기본 과실은 ${result.base}퍼센트예요. 참고용이니 현장에서는 다투지 마세요.`),
      };
    }
    if (result.confirm) {
      const hint = result.confirm.hint;
      return {
        kind: 'abstain', confirm: result.confirm,
        title: `확인이 필요해요 — ${result.confirm.question}`,
        lines: [...(hint ? [`${hint}라고 답해 주세요`] : []), ...result.reasons, ...judgeLine, '답에 따라 예상 과실을 계산하거나 판단을 보류해요',
          ...result.evidenceChecklist.map((x) => `□ ${x}`)],
        // 질문은 기록 내용을 앞세우지 않고 그대로 묻는다. 빠른 도로에서는 차로 돌아오게 하지 않는다.
        tts: quiet || (evacuate
          ? '과실 참고 정보는 화면에 남겨 둘게요. 지금은 대피한 곳에서 기다리고, 안전해진 뒤에 확인하세요.'
          : `${result.confirm.question} 예 또는 아니요로 답해 주세요.${hint ? ` ${hint.replace(/"/g, '')}예요.` : ''}`),
      };
    }
    return {
      kind: 'abstain',
      title: evacuate ? '과실 판단 보류 — 안전해진 뒤 증거를 챙기세요' : '과실 판단 보류 — 증거부터 확보하세요',
      lines: [...result.reasons, ...judgeLine, ...result.evidenceChecklist.map((x) => `□ ${x}`)],
      tts: quiet || (evacuate ? '지금 기록만으로는 과실을 판단하기 어려워요. 대피한 곳에서 기다리고, 블랙박스 원본은 안전해진 뒤에 챙기세요.'
        : '지금 기록만으로는 과실을 판단하기 어려워요. 화면에 나온 증거부터 확보하세요.'),
    };
  }

  function companion(result, m, ctx, seal = null) {
    ctx = ctx || {};
    const mode = respondMode(m, ctx);
    const evacuate = roadContext(m, ctx) !== 'urban';
    const s = safetyCard(m, ctx);
    const safety = { kind: 'safety', title: s.title, lines: s.lines, tts: s.tts };
    const known = !!(m.impact && m.impact.known);
    const sealCard = {
      kind: 'evidence', title: '사고 기록 봉인',
      lines: [
        `${known ? '충돌' : '신고'} 전 ${m.bufferSec}초 차량 기록을 봉인했어요${known ? '' : ' (충돌 순간은 기록에서 확인하지 못했어요)'}`,
        seal ? `무결성 지문(SHA-256) ${seal.hash.slice(0, 12)}… — 기록이 나중에 바뀌면 지문이 달라져요` : '봉인하지 못했어요 — 블랙박스 원본을 따로 보관하세요',
      ],
      tts: seal ? '사고 전 기록을 봉인했어요.' : '기록 봉인에 실패했어요. 블랙박스 원본을 따로 보관하세요.',
    };
    const fault = faultCard(result, m, ctx, mode);
    const speech = {
      kind: 'speech', title: '현장에서 이렇게 말하세요',
      lines: [...(!evacuate ? SPEECH_GUIDE.say : roadContext(m, ctx) === 'highway' ? SPEECH_GUIDE.sayHighway : SPEECH_GUIDE.sayCaution).map((x) => `○ ${x}`),
        ...SPEECH_GUIDE.avoid.map((x) => `✕ ${x}`)],
      tts: evacuate ? '말은 대피한 다음에 하세요. 책임을 단정하는 말은 피하세요.' : '책임을 단정하는 말은 피하고, 다친 데 없는지부터 물어보세요.',
    };
    const next = {
      kind: 'next', title: '다음 단계',
      lines: ['내 보험사 사고 접수 (봉인 기록 첨부 가능)', evacuate ? '상대 차량 번호·보험사 확인 (안전해진 뒤에)' : '상대 차량 번호·보험사 확인', '블랙박스 원본 보존', SPEECH_GUIDE.principle],
      tts: evacuate ? '안전해진 뒤에 보험 접수와 상대 차 정보 확인을 잊지 마세요.' : '보험 접수와 상대 차 정보 확인을 잊지 마세요.',
    };
    let cards;
    if (mode === 'emergency') {
      // 사람과 구조가 먼저: 10초에 과실을 말하지 않는다. 과실 참고 정보는 마지막으로 미루고 음성으로 읽지 않는다.
      const rescue = {
        kind: 'rescue', title: '구조 요청 확인',
        lines: ['119 신고가 됐는지 확인', evacuate ? '가드레일 밖 안전한 곳에서 구조를 기다리기' : '움직이기 어려우면 차 안에서 구조를 기다리기',
          '블랙박스는 끄지 말고 그대로 두기'],
        tts: '119 신고가 됐는지 확인하세요. 구조대가 올 때까지 안전한 곳에 있으세요.',
      };
      cards = [safety, sealCard, rescue, next, fault];
    } else {
      cards = [safety, sealCard, fault, speech, next];
    }
    [0, 5, 10, 20, 25].forEach((at, i) => { cards[i].at = at; });
    return { mode, cards };
  }

  // ---------- 6. 증거 봉인 ----------
  function stableStringify(v) {
    if (v === null || typeof v !== 'object') return JSON.stringify(v === undefined ? null : v);
    if (Array.isArray(v)) return '[' + v.map(stableStringify).join(',') + ']';
    return '{' + Object.keys(v).filter((k) => v[k] !== undefined && typeof v[k] !== 'function').sort()
      .map((k) => JSON.stringify(k) + ':' + stableStringify(v[k])).join(',') + '}';
  }
  // 순수 JS SHA-256 (crypto.subtle 이 없는 비보안 컨텍스트·구형 WebView 대비)
  function sha256Fallback(bytes) {
    const K = new Uint32Array([0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
      0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786,
      0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
      0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb,
      0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
      0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f,
      0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2]);
    const H = new Uint32Array([0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19]);
    const len = bytes.length;
    const total = Math.ceil((len + 9) / 64) * 64;
    const msg = new Uint8Array(total);
    msg.set(bytes); msg[len] = 0x80;
    const bits = len * 8;
    const dv = new DataView(msg.buffer);
    dv.setUint32(total - 8, Math.floor(bits / 0x100000000)); dv.setUint32(total - 4, bits >>> 0);
    const W = new Uint32Array(64);
    const rotr = (x, n) => (x >>> n) | (x << (32 - n));
    for (let off = 0; off < total; off += 64) {
      for (let i = 0; i < 16; i++) W[i] = dv.getUint32(off + i * 4);
      for (let i = 16; i < 64; i++) {
        const s0 = rotr(W[i - 15], 7) ^ rotr(W[i - 15], 18) ^ (W[i - 15] >>> 3);
        const s1 = rotr(W[i - 2], 17) ^ rotr(W[i - 2], 19) ^ (W[i - 2] >>> 10);
        W[i] = (W[i - 16] + s0 + W[i - 7] + s1) >>> 0;
      }
      let [a, b, c, d, e, f, g, h] = H;
      for (let i = 0; i < 64; i++) {
        const S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
        const t1 = (h + S1 + ((e & f) ^ (~e & g)) + K[i] + W[i]) >>> 0;
        const S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
        const t2 = (S0 + ((a & b) ^ (a & c) ^ (b & c))) >>> 0;
        h = g; g = f; f = e; e = (d + t1) >>> 0; d = c; c = b; b = a; a = (t1 + t2) >>> 0;
      }
      H[0] += a; H[1] += b; H[2] += c; H[3] += d; H[4] += e; H[5] += f; H[6] += g; H[7] += h;
    }
    return [...H].map((x) => x.toString(16).padStart(8, '0')).join('');
  }
  async function sha256Hex(str) {
    const data = new TextEncoder().encode(str);
    const subtle = globalThis.crypto && globalThis.crypto.subtle;
    if (!subtle) return sha256Fallback(data);
    const buf = await subtle.digest('SHA-256', data);
    return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');
  }
  const r1 = (x) => (num(x) == null ? null : round(x, 1));
  // 원본 프레임(정리 전 값)을 시간순으로 직렬화해 지문을 만든다. 봉인 시각이 들어가므로 봉인할 때마다 지문이 다르다.
  // 엔진은 지문과 직렬화한 본문(canonical)을 돌려줄 뿐이고, 앱이 둘을 함께 기기에 저장한다.
  async function sealEvidence(frames, m, ctx) {
    ctx = ctx || {};
    // 프레임 시각은 트리거 기준이다(에어백 시각을 다시 맞췄으면 metrics.impact.anchoredMs 에 남는다).
    const rows = (Array.isArray(frames) ? frames : []).filter((f) => f && num(f.t) != null && f.t <= 0).slice().sort(byTimeThenContent);
    // 상태값은 받은 값(문자열·참거짓·숫자)을 그대로, 기록이 없던 칸은 null 로 남긴다 (꺼짐·안 밟음과 구분).
    // 속도·방향·핸들은 0.1 단위로 반올림한다 (앱 이식 때 숫자 표기 차이로 지문이 달라지지 않도록).
    const prim = (x) => (typeof x === 'string' || typeof x === 'boolean' || num(x) != null ? x : null);
    const payload = {
      app: 'first30', version: 4,
      sealedAt: ctx.now || new Date().toISOString(),
      trigger: m && m.impact ? m.impact.source : 'unknown',
      frames: rows.map((f) => ({
        t: f.t, v: r1(f.speedKph), h: r1(f.headingDeg),
        ts: prim(f.turnSignal), st: r1(f.steeringDeg),
        b: prim(f.brake), ab: prim(f.airbag),
      })),
      metrics: m,
      place: { roadType: roadTypeOf(ctx), navCautions: ctx.navCautions || [] },
    };
    const canonical = stableStringify(payload);
    return { hash: await sha256Hex(canonical), canonical, payload };
  }

  // ---------- 7. 전체 파이프라인 ----------
  // 안전 카드와 봉인은 판정이 실패해도 반드시 나온다.
  // opts.onJudgeFailure: 'label'(기본, 대체 판정기 결과를 표시와 함께 사용) | 'abstain'(판정 모델 실패 시 판단 보류)
  async function run(frames, ctx, judge, opts) {
    ctx = ctx || {}; opts = opts || {};
    let metrics, analysisError = null;
    try { metrics = analyzeWindow(frames, ctx); }
    catch (e) { analysisError = String((e && e.message) || e); metrics = analyzeWindow([], ctx); }

    let seal = null, sealError = null;
    try { seal = await sealEvidence(frames, metrics, ctx); } catch (e) { sealError = String((e && e.message) || e); }

    const started = Date.now();
    let facts = null, request = null, judged = null, judgeError = null, result, analysisFailed = false;
    try {
      facts = renderFacts(metrics, ctx);
      request = buildJevRequest(facts, opts);
      try {
        judged = await (judge || F.localJudge)(request, { metrics, ctx });
        if (!judged || !judged.answers) throw new Error('판정 응답이 비어 있습니다');
      } catch (e) {
        judgeError = String((e && e.message) || e);
        judged = await F.localJudge(request, { metrics, ctx });
      }
      result = computeFault(judged.answers, metrics, ctx);
      if (judgeError && opts.onJudgeFailure === 'abstain') {
        result = { status: 'abstain', option: null, reasons: ['판정 모델에 연결하지 못해 과실 범위를 계산하지 않았음'], confidence: 0, evidenceChecklist: evidenceChecklist(metrics, ctx), confirm: null };
      }
    } catch (e) {
      result = { status: 'abstain', option: null, reasons: ['기록 분석 중 문제가 생겨 과실 범위를 계산하지 않았음'], confidence: 0, evidenceChecklist: evidenceChecklist(metrics, ctx), confirm: null };
      analysisError = analysisError || String((e && e.message) || e);
      analysisFailed = true;
    }
    const latencyMs = Date.now() - started;
    const standIn = !judged || judged.model === 'local-standin';
    result.judge = {
      model: judged ? judged.model || null : null,
      provider: judged ? judged.provider || null : null,
      standIn,
      fallback: !!judgeError,
      usedStandIn: standIn && !(judgeError && opts.onJudgeFailure === 'abstain') && !analysisFailed,
    };
    return { metrics, facts, request, judged, judgeError, analysisError, sealError, latencyMs, result, seal, flow: companion(result, metrics, ctx, seal) };
  }

  Object.assign(F, {
    THRESH, IMPACT_TRIGGERS, angDiff, circularMean, prepare, roadBaseline, lateralSeries, trace, analyzeWindow, renderFacts, buildJevRequest,
    computeFault, recordConflict, respondMode, roadContext, companion, sealEvidence, stableStringify, sha256Fallback, run, redact, impactOf,
    confirmQuestion, SPEECH_PRINCIPLE: SPEECH_GUIDE.principle,
  });
})(typeof module !== 'undefined' && typeof require === 'function' ? require('./ns.js') : (globalThis.First30 = globalThis.First30 || {}));
