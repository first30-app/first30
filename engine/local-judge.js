// 퍼스트30 — 오프라인 대체 판정기 (Jev 자리 표시자)
//
// 이 파일은 Jev API 키가 없거나 네트워크가 끊겼을 때, 그리고 데모·테스트에서 쓰는 "규칙 기반 대체 판정기"다.
// Jev 와 같은 응답 모양(Choice: choice/probabilities/confidence, Noul: noul)을 돌려주지만
// 확률 값은 학습된 모델의 출력이 아니라 사람이 정한 규칙값이다. 화면과 문서에서 반드시 이 사실을 표시한다.
//
// 원칙
// - 사고 유형은 코드가 잰 차로변경 상태(metrics.laneChange.status)를 먼저 따르고, 진술은 보조로만 쓴다.
// - 진술에서 뽑은 가감 요소 Noul 값은 0.6 을 넘기지 않는다 → 규칙만으로는 어떤 가감 요소도 "반영"되지 않고 범위에만 들어간다.
// - 주어(내가/상대가)와 부정("실선은 아니고", "끼어든 적 없어요"), 의도("바꾸려고"), 명사형("끼어들기 단속")을 가린다.
//   "신호"는 신호등과 헷갈려 쓰지 않는다.
'use strict';
(function (F) {
  const NOUL_CAP = 0.6;

  const SIG = '(깜빡이|깜박이|깜빡이등|방향 ?지시등)';
  const RX = {
    noSignal: new RegExp(`${SIG}(을|를|도|은|는|이|가)?\\s?.{0,6}?(없이|안 ?켜|안 ?켰|안 ?켠|안 ?넣|안 ?했|안 ?하|없었|없더|없던|못 ?켜|못 ?켰|못 ?켠|켜지 ?않|넣지 ?않)`, 'g'),
    forgotSignal: new RegExp(`${SIG}(을|를)? ?(켜는 ?(걸|것을?)|켜야 (하는데|했는데)|넣는 ?걸) ?(깜빡|잊|까먹|못)`, 'g'),
    lateSignal: new RegExp(`${SIG}(을|를|도|은|는)?\\s?[^.!?]{0,14}?(늦게|들어오면서|들어오며|넘어오면서|동시에 켜)`, 'g'),
    lateSignal2: new RegExp(`(들어오|넘어오|들어가|넘어가|끼어들)(면서|며) ?${SIG}(을|를)? ?(켜|켰|넣)`, 'g'),
    // 상대(또는 나)도 "안 켰다" — 앞에서 깜빡이를 말한 뒤 주어만 바꿔 덧붙이는 꼴
    alsoNoSignal: /(상대방?|상대 ?차|그 ?차|옆 ?차|앞 ?차|저 ?차|저|제|나|내)(도|는|가)? ?(안|못) ?(켰|켜|넣)/g,
    // 기본 주어: 상대. "끼어들기"(명사)는 행위가 아니다.
    comeIn: /(끼어 ?(들(?!기)|든|듦)|들어 ?(오|와|왔|온|올)|밀고 들어|칼치기|넘어 ?(오|와|왔|온)|(차로|차선)에서 (갑자기 )?나(오|와|왔|온)|(갑자기|훅|불쑥) ?(나왔|나와|튀어나)|(차선|차로) ?쪽으로 ?(붙|밀고|넘어|들어))/g,
    // 기본 주어: 나. "진로변경 금지"·"차선 변경 위반 단속"(명사)은 행위가 아니다.
    goIn: /(들어 ?(가|갔|간|갈|감)|넘어 ?(가|갔|간)|(차선|차로)(을|를)? ?(바꾸|바꿨|바꿔|바꾼|옮기|옮겼|옮겨|옮긴|넘은|넘었|넘어서|넘는)|(차선|차로|진로) ?변경(?! ?(금지|위반|단속|구간))|\d ?차로로 ?(옮기|옮겼|옮겨|옮긴|이동|들어)|(차선|차로)(으로|로) ?(이동하|이동했|이동해|옮기|옮겼|옮겨|옮긴))/g,
    // "켜는 동시에", "밟는 동시에"처럼 "~하는 동시에"(그 순간)는 동시 진로변경이 아니다. 진로변경 말과 묶일 때만 본다.
    simultaneous: /((?<!는 |은 |ㄴ )동시에 ?(같은 ?(차선|차로)|들어|넘어|(차선|차로)(을|를)? ?(바꾸|바꿨|바꿔|바꾼|변경)|진로)|같이 (들어|차선|차로|진로)|둘 ?다 ([가-힣]+ )?(들어|바꾸|바꿨|차선|차로)|양쪽에서 (동시에 |같이 )?(들어|바꾸|바꿨)|서로 같은 차(선|로))/,
    solidLine: /(실선|진로 ?변경 ?금지|차선 ?변경 ?금지)/g,
    // 전용차로 "위반" 정황만 본다 ("버스가 전용차로에서 나오다가"는 위반이 아니다)
    busLane: /((버스 ?전용 ?차로|버스 ?차로|전용 ?차로)(를|을)? ?(위반|침범|타고|달리|로 달리|로 끼어|에서 (갑자기 )?끼어)|(버스 ?전용 ?차로|전용 ?차로) ?위반)/g,
    rearEnd: /(뒤에서 (박|받|들이|추돌)|(뒷|뒤) ?차가 (박|받|들이|와서 박)|추돌)/,
    otherType: /(교차로|사거리|삼거리|좌회전|우회전|유턴|주차장|주차하|주차 중|후진|골목|횡단보도)/,
    // 내 진로변경을 직접 부정: "차선 안 바꿨어요", "차로 변경 안 했어요", "끼어든 적 없어요"
    selfDeny: /((차선|차로|진로)(을|를|은|는)? ?(안|못) ?(바꾸|바꿨|바꿔|옮기|옮겼|변경)|(차선|차로|진로) ?변경(을|은|는)? ?(안|못) ?(했|하)|(끼어든|들어간|바꾼|옮긴) ?적(이|은|도)? ?(없|않))/g,
  };
  const SELF_SUBJ = /(내가|제가|나는|저는|난 |전 |내 ?차가|내 ?차는|제 ?차가|제 ?차는|저희 ?차가|저희 ?차는|우리 ?차가|우리 ?차는|저희가|우리가|나도|저도)/g;
  const OTHER_SUBJ = /(상대방?(이|가|은|는|도)|상대방 |상대 ?차량(이|은|도)?|상대 ?차(가|는|도)|그 ?차(가|는|도)|저 ?차(가|는)|옆 ?차(가|는|도)|앞 ?차(가|는)|앞에 ?차가|뒤 ?차(가|는)|뒷 ?차(가|는)|(오른|왼)쪽 ?차(가|는)|택시가|버스가|트럭이|오토바이가|저쪽에서|옆에서)/g;
  // 언급 바로 뒤의 부정 (문장부호를 넘지 않는다)
  //  - 명사: "실선은 아니고", "실선이 아닌", "실선 아님", "실선은 없었어", "실선 넘어간 거 아니에요"
  //  - 동사: "들어온 게 아니라", "동시에 들어간 건 아니고", "안 켰다는 건 아니고", "들어오지 않았어", "차선 변경 안 했어요", "끼어든 적 없어요"
  const NEG_NOUN = /^([^.,!?]{0,3}?(은|는|이|가)?\s?(아니|아냐|아닌|아님)|[^.,!?]{0,4}?(없|않)|[^.,!?]{0,8}?(거|게|건|것)\s?(아니|아냐|아닌|아님))/;
  const NEG_VERB = /^([^.,!?\s]{0,3}\s?(게|건|것은|거)\s?(아니|아냐|아닌|아님)|[^.,!?\s]{0,3}지\s?(않|못)|\s?안\s?(했|해|하|함)|[^.,!?\s]{0,2}\s?적(이|은|도)?\s?(없|않))/;
  // "실선이었는지 (모르겠어요)" — 명사 뒤 얼버무림
  const HEDGE_NOUN = /^[^.,!?]{0,4}(는지|ㄴ지|은지|던지)/;
  // 뒤에 오는 주어: "끼어든 건 저예요"(나), "끼어들었어요, 제가"(나), "차선 변경하던 옆 차가"(상대), "차선 변경 중인 차를"(상대),
  // "차선 바꾼 건 제가 아니라 상대예요"(상대)
  const NOT_SELF_AFTER = /^.{0,6}?(건|것은|게)\s?(제가|내가|저희가|우리가|저|나)\s?(아니|아냐|아닌)/;
  const SELF_AFTER = /^(.{0,6}?(건|것은|게|거)\s?(저|제|나|내|저희|우리)(예요|에요|요|야|였|입니다|이에요|임|$|\s)|[^.!?]{0,6},\s?(제가|내가|저희가|우리가)(?!\s?아니)|[^.!?,\s]{0,4}\s(내가|제가)(\s|$|[.!?]))/;
  const OTHER_AFTER = /^(.{0,4}?(던|는|ㄴ|하던|하는|한|중인|중이던)\s?(옆 ?차|상대|그 ?차|앞 ?차|뒤 ?차|뒷 ?차|(오른|왼)쪽 ?차|택시|버스|트럭|오토바이|차(를|가|와|랑|하고|에|는|도))|.{0,6}?(건|것은|게)\s?(상대|그 ?차|옆 ?차|저 ?차|앞 ?차|뒤 ?차))/;
  const HEDGE = /(모르겠|모름|모르고|기억이 안|기억 안|확실하지 않|확실치 않|잘 모르|어려워|어렵|애매|단정하기|말하긴 힘|말하기 힘)/;
  const HEDGE_AFTER = /^\s?(는지|ㄴ지|은지|는 지)/; // "안 켰는지 (모르겠어)"
  const INTENT_AFTER = /^\s?(하)?(려|으려)(고|는|던|면|\s)/; // "변경하려고", "들어오려는" — 하려던 것이지 일어난 일이 아니다
  const ATTEMPT_AFTER = /^\s?(하)?(려|으려)다/;          // "끼어들려다가" — 하던 중에 사고가 났다(주어가 없으면 나)
  const RESULT_AFTER = /^\s?있(었|던|는|다|어)/;   // "들어와 있었는데" — 이미 들어와 있던 상태(사고 순간의 진로변경이 아님)
  // 내가 한 일처럼 보여도 인정이 아닌 꼴: 남의 말 옮기기("제가 끼어들었다고 우겨요"), 가정("바꿨으면"), 질문("바꾼 거냐고")
  const REPORTED_AFTER = /^[^.,!?]{0,4}?(다|라|냐|자)고/;
  const CONDITIONAL_AFTER = /^[^.,!?\s]{0,3}?(으면|다면|더라면|라면)/;
  const QUESTION_AFTER = /^[^.,!?]{0,4}?(나요|냐|는지|은지|을까|ㄹ까)/;
  const notOwnAct = (rest) => REPORTED_AFTER.test(rest) || CONDITIONAL_AFTER.test(rest) || QUESTION_AFTER.test(rest);
  // 따옴표 안의 말은 다른 사람의 말로 보고 해석하지 않는다 ('상대가 "제가 바꿨어요"라고 했어요')
  const QUOTED = /["“”'‘’「」『』][^"“”'‘’「」『』]{0,80}["“”'‘’「」『』]/g;
  const PUSHED_BEFORE = /밀려(서|나)?\s?[^.!?]{0,10}$/; // "밀려서 옆 차로로 넘어갔어요" — 충돌 뒤 밀림

  const all = (re, s) => { const g = new RegExp(re.source, 'g'); const out = []; let m; while ((m = g.exec(s))) { out.push(m); if (!m[0].length) g.lastIndex++; } return out; };
  function lastEnd(re, s) { let e = -1; for (const m of all(re, s)) e = m.index + m[0].length; return e; }
  // 일어난 일(idx 위치)의 주어: 뒤에 오는 주어를 먼저 보고, 없으면 바로 앞 14자 안에서 가장 가까운 주어. 같은 자리에서 끝나면(예: "내 차가") 나로 본다.
  function subjectOf(s, idx, end) {
    if (end != null) {
      const rest = s.slice(end);
      if (NOT_SELF_AFTER.test(rest)) return 'other';
      if (SELF_AFTER.test(rest)) return 'self';
      if (OTHER_AFTER.test(rest)) return 'other';
    }
    const win = s.slice(Math.max(0, idx - 14), idx);
    const se = lastEnd(SELF_SUBJ, win), oe = lastEnd(OTHER_SUBJ, win);
    if (se < 0 && oe < 0) return null;
    return se >= oe ? 'self' : 'other';
  }
  const negated = (s, m) => NEG_VERB.test(s.slice(m.index + m[0].length)) || /(^|\s)(안|못)\s?$/.test(s.slice(Math.max(0, m.index - 3), m.index));
  // 부정되지 않은 언급이 하나라도 있는가 ("실선은 아니고" → 없음)
  function positiveMatches(re, s, neg = NEG_NOUN) {
    return all(re, s).filter((m) => { const rest = s.slice(m.index + m[0].length); return !neg.test(rest) && !(neg === NEG_NOUN && HEDGE_NOUN.test(rest)); });
  }
  // 장소·위반 언급(실선·전용차로)의 주체: 언급 자체의 주어를 먼저 보고, 없으면 뒤따르는 진로변경 동사의 주어
  function violatorOf(s, m) {
    const end = m.index + m[0].length;
    const own = subjectOf(s, m.index, end);
    if (own) return own;
    const tail = s.slice(end, end + 24);
    const verbs = [...all(RX.goIn, tail).map((x) => ({ x, def: 'self' })), ...all(RX.comeIn, tail).map((x) => ({ x, def: 'other' }))]
      .sort((a, b) => a.x.index - b.x.index);
    if (!verbs.length) return null;
    const v = verbs[0];
    return subjectOf(s, end + v.x.index, end + v.x.index + v.x[0].length) || v.def;
  }

  function parseStatement(raw) {
    const s = String(raw || '').replace(/\s+/g, ' ').replace(QUOTED, ' (인용) ').trim();
    const cues = { otherLC: false, selfLC: false, selfDeny: false, intentOnly: false, otherNoSignal: false, selfNoSignal: false, otherLateSignal: false, selfLateSignal: false,
      simultaneous: all(RX.simultaneous, s).some((m) => !NEG_VERB.test(s.slice(m.index + m[0].length))),
      solidLine: false, solidLineBy: null, busLane: false, busLaneBy: null, rearEnd: RX.rearEnd.test(s), otherType: RX.otherType.test(s) };
    const hedged = HEDGE.test(s);
    const skipAct = (m) => {
      const end = m.index + m[0].length;
      const rest = s.slice(end);
      if (!ATTEMPT_AFTER.test(rest) && INTENT_AFTER.test(rest)) { cues.intentOnly = true; return true; }
      if (RESULT_AFTER.test(rest)) return true;
      if (PUSHED_BEFORE.test(s.slice(Math.max(0, m.index - 14), m.index))) return true;
      return false;
    };
    for (const m of all(RX.comeIn, s)) {
      if (skipAct(m)) continue;
      const end = m.index + m[0].length;
      const who = subjectOf(s, m.index, end) || (ATTEMPT_AFTER.test(s.slice(end)) ? 'self' : null);
      if (negated(s, m)) { if (who === 'self') cues.selfDeny = true; continue; } // "옆 차가 들어온 게 아니라"
      if (who === 'self') { if (notOwnAct(s.slice(end))) { cues.reportedSelf = true; continue; } cues.selfLC = true; } else cues.otherLC = true;
    }
    for (const m of all(RX.goIn, s)) {
      if (skipAct(m)) continue;
      const end = m.index + m[0].length;
      const who = subjectOf(s, m.index, end);
      if (negated(s, m)) { if (who !== 'other') cues.selfDeny = true; continue; }
      if (who === 'other') cues.otherLC = true;
      else if (notOwnAct(s.slice(end))) cues.reportedSelf = true;
      else cues.selfLC = true;
    }
    for (const m of all(RX.selfDeny, s)) {
      const who = subjectOf(s, m.index, m.index + m[0].length);
      if (who !== 'other') cues.selfDeny = true;
    }
    for (const m of all(RX.noSignal, s)) {
      const end = m.index + m[0].length;
      if (hedged || HEDGE_AFTER.test(s.slice(end)) || negated(s, m)) continue;
      const active = /(못 ?켰|안 ?켰|안 ?켜고|안 ?넣었|못 ?넣었)/.test(m[0]); // 내가 한 행동을 말하는 꼴
      const who = subjectOf(s, m.index, end) || (cues.otherLC && !cues.selfLC ? 'other' : cues.selfLC && !cues.otherLC ? 'self' : active && !cues.otherLC ? 'self' : null);
      if (who === 'self') cues.selfNoSignal = true; else if (who === 'other') cues.otherNoSignal = true;
    }
    if (!hedged && /(깜빡이|깜박이|방향 ?지시등)/.test(s)) {
      for (const m of all(RX.alsoNoSignal, s)) {
        if (['저', '제', '나', '내'].includes(m[1])) cues.selfNoSignal = true; else cues.otherNoSignal = true;
      }
    }
    for (const m of all(RX.forgotSignal, s)) {
      const who = subjectOf(s, m.index, m.index + m[0].length);
      if (who === 'other') cues.otherNoSignal = true; else cues.selfNoSignal = true;
    }
    for (const re of [RX.lateSignal, RX.lateSignal2]) {
      for (const m of all(re, s)) {
        if (hedged || negated(s, m)) continue;
        const who = subjectOf(s, m.index, m.index + m[0].length) || 'other';
        if (who === 'self') cues.selfLateSignal = true; else cues.otherLateSignal = true;
      }
    }
    // 내 진로변경을 부정했으면 같은 문장의 "들어갔다" 류는 상대 이야기이거나 부정의 일부로 본다
    if (cues.selfDeny && cues.selfLC && !/(제가|내가|저희가|우리가) [^.!?]{0,12}(들어갔|넘어갔|바꿨|옮겼)/.test(s)) cues.selfLC = false;
    const solid = positiveMatches(RX.solidLine, s);
    if (solid.length) { cues.solidLine = true; cues.solidLineBy = violatorOf(s, solid[0]); }
    const bus = positiveMatches(RX.busLane, s);
    if (bus.length) { cues.busLane = true; cues.busLaneBy = violatorOf(s, bus[0]); }
    return cues;
  }

  function normalize(probs) {
    const s = Object.values(probs).reduce((a, b) => a + b, 0);
    const out = {};
    for (const [k, v] of Object.entries(probs)) out[k] = Math.round((v / s) * 1000) / 1000;
    return out;
  }

  const P = {
    rearEnd: { none: 0.9, c252_me_A: 0.06, c252_me_B: 0.02, c252_2: 0.02 },
    otherType: { none: 0.85, c252_me_A: 0.05, c252_me_B: 0.05, c252_2: 0.05 },
  };

  async function localJudge(request, { metrics, ctx = {} } = {}) {
    const c = parseStatement(ctx.driverStatement);
    const lc = metrics && metrics.laneChange ? metrics.laneChange.status : 'unmeasurable';
    const cautions = ctx.navCautions || [];
    const otherClaim = c.otherLC || c.otherNoSignal || c.otherLateSignal;
    const confirmed = ctx.laneChangeConfirmed;

    let probs, evidence;
    if (c.otherType) { probs = P.otherType; evidence = 0.6; } // 교차로·주차장·회전·후진은 차로변경 도표 밖
    else if (lc === 'detected') {
      if (c.simultaneous) { probs = { c252_2: 0.74, c252_me_B: 0.18, none: 0.06, c252_me_A: 0.02 }; evidence = 0.8; }
      else if (c.otherLC && !c.selfLC && confirmed !== true) { probs = { c252_me_B: 0.62, c252_2: 0.28, none: 0.06, c252_me_A: 0.04 }; evidence = 0.6; }
      else { probs = { c252_me_B: 0.88, c252_2: 0.07, none: 0.04, c252_me_A: 0.01 }; evidence = 0.85; }
    } else if (lc === 'none') {
      if (c.rearEnd && !otherClaim) { probs = P.rearEnd; evidence = 0.7; }
      else if (c.selfLC || confirmed === true) { probs = { none: 0.45, c252_me_B: 0.3, c252_2: 0.15, c252_me_A: 0.1 }; evidence = 0.3; } // 진술·확인과 기록이 어긋남
      else if (otherClaim) { probs = { c252_me_A: 0.86, none: 0.08, c252_2: 0.04, c252_me_B: 0.02 }; evidence = 0.8; }
      else { probs = { none: 0.7, c252_me_A: 0.15, c252_2: 0.08, c252_me_B: 0.07 }; evidence = 0.3; }
    } else if (lc === 'completed') {
      probs = { none: 0.62, c252_me_B: 0.3, c252_2: 0.04, c252_me_A: 0.04 }; evidence = 0.45;
    } else if (lc === 'starting') {
      // 충돌 직전에 시작한 움직임: 운전자가 확인 질문에 "예"라고 했을 때만 내 진로변경으로 본다
      if (confirmed === true && c.simultaneous) probs = { c252_2: 0.7, c252_me_B: 0.2, none: 0.06, c252_me_A: 0.04 };
      else if (confirmed === true) probs = { c252_me_B: 0.8, c252_2: 0.1, none: 0.06, c252_me_A: 0.04 };
      else if (c.otherLC && !c.selfLC) probs = { c252_me_A: 0.5, c252_me_B: 0.25, c252_2: 0.15, none: 0.1 };
      else if (c.selfLC) probs = { c252_me_B: 0.55, c252_2: 0.15, none: 0.2, c252_me_A: 0.1 };
      else probs = { none: 0.6, c252_me_B: 0.2, c252_me_A: 0.15, c252_2: 0.05 };
      evidence = confirmed === true ? 0.65 : 0.45;
    } else { // uncertain · too_slow · unmeasurable — 차량 기록으로 유형을 뒷받침할 수 없음
      if (c.rearEnd && !otherClaim) probs = P.rearEnd;
      else if (otherClaim && !c.selfLC) probs = { c252_me_A: 0.55, none: 0.35, c252_2: 0.05, c252_me_B: 0.05 };
      else if (c.selfLC) probs = { c252_me_B: 0.55, none: 0.35, c252_2: 0.05, c252_me_A: 0.05 };
      else probs = { none: 0.75, c252_me_A: 0.1, c252_me_B: 0.1, c252_2: 0.05 };
      evidence = 0.3;
    }
    probs = normalize(probs);
    const ranked = Object.entries(probs).sort((a, b) => b[1] - a[1]);
    const top = F.CHART_OPTIONS && F.CHART_OPTIONS[ranked[0][0]];
    // 실선·전용차로 가감 요소는 "진로를 바꾼 차"의 위반이다. 진술에서 위반한 차를 가렸으면 그 차가 진로를 바꾼 쪽일 때만 본다.
    const laneChanger = !top || !top.chart ? null : (top.me === 'B' || top.chart === '252-2') ? 'self' : 'other';
    const byChanger = (by) => by == null || laneChanger == null || by === laneChanger;

    const cap = (x) => Math.min(NOUL_CAP, x);
    const otherSignal = c.otherNoSignal || c.otherLateSignal ? cap(0.6) : 0.15;
    const camera = cautions.includes('LANE_CHANGE_CAMERA_ALERT') || cautions.includes('CUT_IN_VIOLATION_ENFORCEMENT');
    const forbidden = c.solidLine && byChanger(c.solidLineBy) ? cap(0.6) : camera ? 0.45 : 0.05;
    const busNotice = cautions.includes('BUS_LANE_AVAILABLE_NOTICE') || cautions.includes('BUS_LANE_VIOLATION_ENFORCEMENT');
    const bus = c.busLane && byChanger(c.busLaneBy) ? cap(0.6) : busNotice ? 0.45 : 0.05;
    // 진술에서 자기 진로변경을 말했으면 확인 답이 "아니요"여도 높게 둔다 — 서로 어긋나면 "상대 진로변경" 숫자를 막는다
    const selfLaneChange = confirmed === true ? 0.95 : c.selfLC && !c.selfDeny ? 0.9 : confirmed === false || c.selfDeny ? 0.02 : 0.1;

    return {
      model: 'local-standin',
      provider: 'offline-rules',
      cues: c,
      answers: {
        chart: { type: 'choice', choice: ranked[0][0], probabilities: probs, confidence: Math.round((ranked[0][1] - ranked[1][1]) * 100) / 100 },
        evidence_sufficient: { type: 'noul', noul: evidence },
        self_lane_change: { type: 'noul', noul: selfLaneChange },
        other_signal_fail: { type: 'noul', noul: otherSignal },
        forbidden_zone: { type: 'noul', noul: forbidden },
        bus_lane: { type: 'noul', noul: bus },
      },
    };
  }

  F.localJudge = localJudge;
  F.parseStatement = parseStatement;
  F.LOCAL_NOUL_CAP = NOUL_CAP;
})(typeof module !== 'undefined' && typeof require === 'function' ? require('./ns.js') : (globalThis.First30 = globalThis.First30 || {}));
