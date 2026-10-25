// 퍼스트30 — TypeSafe Jev 호출부
//
// 공개 문서 기준 요청 형식: POST https://api.typesafe.ai/v1/systemone
//   Authorization: Bearer <TYPESAFE_API_KEY>
//   { model, state, questions: { name: {type:'choice', instructions, criteria:{key:desc}} | {type:'noul', instructions} } }
// 응답: { answers: { name: {type:'choice', choice, probabilities, confidence} | {type:'noul', noul} }, usage }
//
// 차량 앱에 API 키를 넣지 않는다. 실제 배포에서는 키를 가진 중계 서버(endpoint)를 두고 앱은 그 서버를 부른다.
'use strict';
(function (F) {
  const DEFAULT_ENDPOINT = 'https://api.typesafe.ai/v1/systemone';

  const own = (o, k) => o != null && Object.prototype.hasOwnProperty.call(o, k);
  const prob = (x) => typeof x === 'number' && Number.isFinite(x) && x >= 0 && x <= 1;

  function validateAnswers(request, body) {
    if (!body || typeof body !== 'object' || !body.answers || typeof body.answers !== 'object') throw new Error('Jev 응답에 answers 가 없습니다');
    for (const [name, q] of Object.entries(request.questions)) {
      const a = own(body.answers, name) ? body.answers[name] : null;
      if (!a || typeof a !== 'object') throw new Error(`Jev 응답에 ${name} 답이 없습니다`);
      if (q.type === 'choice') {
        if (typeof a.choice !== 'string' || !own(q.criteria, a.choice)) throw new Error(`${name}: 선택지 밖의 답 ${a.choice}`);
        if (!a.probabilities || typeof a.probabilities !== 'object') throw new Error(`${name}: probabilities 없음`);
        for (const [k, p] of Object.entries(a.probabilities)) {
          if (!own(q.criteria, k)) throw new Error(`${name}: 선택지 밖의 확률 키 ${k}`);
          if (!prob(p)) throw new Error(`${name}: 확률 값 오류 ${k}`);
        }
        if (!own(a.probabilities, a.choice)) throw new Error(`${name}: 고른 답의 확률이 없습니다`);
      } else if (q.type === 'noul') {
        if (!prob(a.noul)) throw new Error(`${name}: noul 값 오류`);
      }
    }
    return body;
  }

  function makeJevJudge({ apiKey, endpoint = DEFAULT_ENDPOINT, timeoutMs = 3000, fetchImpl } = {}) {
    const doFetch = fetchImpl || globalThis.fetch;
    return async function jevJudge(request) {
      if (!doFetch) throw new Error('fetch 를 쓸 수 없는 환경입니다');
      const ctrl = new AbortController();
      const timer = setTimeout(() => ctrl.abort(), timeoutMs);
      try {
        const headers = { 'Content-Type': 'application/json' };
        if (apiKey) headers.Authorization = `Bearer ${apiKey}`;
        const res = await doFetch(endpoint, { method: 'POST', headers, body: JSON.stringify(request), signal: ctrl.signal });
        if (!res.ok) throw new Error(`Jev HTTP ${res.status}`);
        const body = await res.json();
        const ok = validateAnswers(request, body);
        return { ...ok, model: ok.model || request.model, provider: ok.provider || 'TypeSafe' };
      } finally {
        clearTimeout(timer);
      }
    };
  }

  F.makeJevJudge = makeJevJudge;
  F.validateJevAnswers = validateAnswers;
  F.JEV_ENDPOINT = DEFAULT_ENDPOINT;
})(typeof module !== 'undefined' && typeof require === 'function' ? require('./ns.js') : (globalThis.First30 = globalThis.First30 || {}));
