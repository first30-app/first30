#!/usr/bin/env node
// 퍼스트30 — golden fixture dumper for the Kotlin port (Node >= 18, no deps).
//
//   npm run golden   (= node test/dump-golden.js)
//
// Writes into android/core/src/test/resources/golden/ (the directory is wiped and rebuilt each run):
//   index.json              list of every file below, test names, call counts, pass/fail
//   tests/tNNN[-slug].json  one per node:test test in test/engine.test.js, in registration order:
//                           { index, name, status, calls: [ { fn, args: [...], result } | { fn, args, threw: {"$error": msg} } ] }
//                           Only TOP-LEVEL calls into exported F.* functions are recorded (calls the engine makes
//                           internally — e.g. run() -> F.localJudge — are not). Args are snapshotted before the call,
//                           results after the (awaited) return.
//   scenarios/<id>.json     per F.SCENARIOS entry: spec, frames, ctx, expect, analyzeWindow(frames, ctx) and the full run()
//                           output for ctx as given / laneChangeConfirmed:true / :false / key removed (now fixed, F.localJudge).
//   synth.json              F.synthFrames(opts) outputs for scenario specs (fromS -10 and -20) and extra option sets.
//   frames/<id>.json        deduplicated frame arrays (see "$frames" below).
//   FORMAT.md               this description, longer.
//
// Value encoding (JSON with tags; a tag object always has exactly one key starting with '$'):
//   numbers                 plain JSON numbers (JSON.stringify = shortest round-trip double repr)
//   undefined               {"$undef":true}   — a key PRESENT with value undefined. A missing key is simply absent.
//   NaN / ±Infinity / -0    {"$num":"NaN"} / {"$num":"Infinity"} / {"$num":"-Infinity"} / {"$num":"-0"}
//   function                {"$fn":"<name>"}
//   Error / thrown value    {"$error":"<message>"}
//   Uint8Array              {"$u8":"<hex>"}
//   frame array             {"$frames":"<id>"} — any array of >= 10 plain objects that all have a numeric "t";
//                           content is in frames/<id>.json (id = first 16 hex of sha256 of the encoded array JSON).
//
// Determinism: Date is frozen (new Date() / Date.now() -> 2026-01-01T00:00:00.000Z) while dumping, so run().latencyMs is
// always 0 and seal.payload.sealedAt falls back to the frozen clock when ctx.now is missing (e.g. ctx null). Kotlin must
// ignore latencyMs. Every { hash, canonical } object seen is checked: sha256(canonical) === hash.
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const Module = require('node:module');
const { AsyncLocalStorage } = require('node:async_hooks');

const ROOT = path.join(__dirname, '..');
const OUT = path.join(ROOT, 'android', 'core', 'src', 'test', 'resources', 'golden');
const FIXED_NOW = '2026-10-23T15:00:00.000Z'; // same as test/engine.test.js
const FROZEN_CLOCK = '2026-01-01T00:00:00.000Z';

// ---------- frozen clock ----------
const RealDate = Date;
const FROZEN_MS = RealDate.parse(FROZEN_CLOCK);
class FrozenDate extends RealDate {
  constructor(...a) { if (a.length === 0) super(FROZEN_MS); else super(...a); }
  static now() { return FROZEN_MS; }
}
globalThis.Date = FrozenDate;

// ---------- encoder ----------
const sha256 = (s) => crypto.createHash('sha256').update(s, 'utf8').digest('hex');
const frameStore = new Map(); // id -> encoded array
const sealChecks = { ok: 0, bad: [] };
const hazards = { nan: 0, inf: 0, negZero: 0, undef: 0 };

function isPlain(o) { const p = Object.getPrototypeOf(o); return p === Object.prototype || p === null; }

function enc(v, stack = []) {
  if (v === undefined) { hazards.undef++; return { $undef: true }; }
  if (v === null) return null;
  switch (typeof v) {
    case 'number':
      if (Number.isNaN(v)) { hazards.nan++; return { $num: 'NaN' }; }
      if (v === Infinity) { hazards.inf++; return { $num: 'Infinity' }; }
      if (v === -Infinity) { hazards.inf++; return { $num: '-Infinity' }; }
      if (Object.is(v, -0)) { hazards.negZero++; return { $num: '-0' }; }
      return v;
    case 'string': case 'boolean': return v;
    case 'bigint': return { $bigint: String(v) };
    case 'function': return { $fn: v.name || '' };
    case 'symbol': throw new Error('symbol value in output');
  }
  if (stack.includes(v)) throw new Error('cyclic value');
  if (v instanceof Error) return { $error: String(v.message) };
  if (v instanceof Uint8Array) return { $u8: Buffer.from(v).toString('hex') };
  if (ArrayBuffer.isView(v)) throw new Error('unsupported typed array ' + v.constructor.name);
  const st = stack.concat([v]);
  if (Array.isArray(v)) {
    const arr = [];
    for (let i = 0; i < v.length; i++) arr.push(enc(v[i], st));
    if (arr.length >= 10 && arr.every((f) => f && typeof f === 'object' && !Array.isArray(f) && typeof f.t === 'number' && !Object.keys(f).some((k) => k[0] === '$'))) {
      const s = JSON.stringify(arr);
      const id = sha256(s).slice(0, 16);
      frameStore.set(id, arr);
      return { $frames: id };
    }
    return arr;
  }
  if (!isPlain(v)) throw new Error('unsupported object type ' + (v.constructor && v.constructor.name));
  if (typeof v.canonical === 'string' && typeof v.hash === 'string') {
    if (sha256(v.canonical) === v.hash) sealChecks.ok++; else sealChecks.bad.push(v.hash);
  }
  const o = {};
  for (const k of Object.keys(v)) {
    if (k[0] === '$') throw new Error('data key starts with $: ' + k);
    o[k] = enc(v[k], st);
  }
  return o;
}

// ---------- recorder ----------
const F = require('../engine/index.js');
require('../scenarios/scenarios.js');

const als = new AsyncLocalStorage();
const isThenable = (x) => x && (typeof x === 'object' || typeof x === 'function') && typeof x.then === 'function';

const realFns = {};
for (const k of Object.keys(F)) {
  const orig = F[k];
  if (typeof orig !== 'function') continue;
  realFns[k] = orig;
  const wrapper = function (...args) {
    const store = als.getStore();
    if (!store || store.inCall) return orig.apply(this, args);
    const call = { fn: k, args: enc(args) };
    store.rec.calls.push(call);
    const inner = { rec: store.rec, inCall: true };
    let out;
    try { out = als.run(inner, () => orig.apply(this, args)); }
    catch (e) { call.threw = { $error: String((e && e.message) || e) }; throw e; }
    if (isThenable(out)) {
      return Promise.resolve(out).then(
        (r) => { call.result = enc(r); return r; },
        (e) => { call.threw = { $error: String((e && e.message) || e) }; throw e; },
      );
    }
    call.result = enc(out);
    return out;
  };
  Object.defineProperty(wrapper, 'name', { value: orig.name || k });
  F[k] = wrapper;
}

// ---------- scenario + synth dumps (run before tests; not attributed to any test) ----------
function scenarioSpeedFns() {
  // spec.speed is a closure (brakingSpeed) for some scenarios; recover its parameters from the source text.
  const src = fs.readFileSync(path.join(ROOT, 'scenarios', 'scenarios.js'), 'utf8');
  const out = {};
  const re = /id:\s*'([^']+)'[\s\S]*?spec:\s*\{\s*speed:\s*brakingSpeed\(\s*([-\d.]+)\s*,\s*([-\d.]+)\s*,\s*([-\d.]+)\s*\)/g;
  let m;
  while ((m = re.exec(src))) {
    // guard: make sure no other "id:" sits between the id and the match
    if (/id:\s*'/.test(m[0].slice(4))) continue;
    out[m[1]] = { kind: 'brakingSpeed', cruise: +m[2], fromS: +m[3], decelKphPerS: +m[4], formula: 't < fromS ? cruise : max(0, cruise - (t - fromS) * decelKphPerS)' };
  }
  return out;
}

async function dumpScenarios(speedFns) {
  const files = [];
  for (const sc of F.SCENARIOS) {
    const ctxBase = sc.ctx;
    const variants = {
      given: { ...ctxBase, now: FIXED_NOW },
      confirmedTrue: { ...ctxBase, laneChangeConfirmed: true, now: FIXED_NOW },
      confirmedFalse: { ...ctxBase, laneChangeConfirmed: false, now: FIXED_NOW },
      confirmedRemoved: (() => { const c = { ...ctxBase, now: FIXED_NOW }; delete c.laneChangeConfirmed; return c; })(),
    };
    const runs = {};
    for (const [name, ctx] of Object.entries(variants)) {
      const out = await realFns.run(sc.frames, ctx, realFns.localJudge);
      runs[name] = { ctx: enc(ctx), output: enc(out) };
    }
    const doc = {
      id: sc.id, title: sc.title,
      spec: enc(sc.spec), speedFn: speedFns[sc.id] || null,
      frames: enc(sc.frames), ctx: enc(sc.ctx), expect: enc(sc.expect),
      analyzeWindow: enc(realFns.analyzeWindow(sc.frames, sc.ctx)),
      runs,
    };
    files.push({ file: `scenarios/${sc.id}.json`, id: sc.id, title: sc.title, doc });
  }
  return files;
}

function dumpSynth(speedFns) {
  const entries = [];
  const add = (label, opts, speedFn = null) => entries.push({ label, opts: enc(opts), speedFn, frames: enc(realFns.synthFrames(opts)) });
  for (const sc of F.SCENARIOS) {
    add(`scenario:${sc.id}`, sc.spec, speedFns[sc.id] || null);
    add(`scenario:${sc.id}:fromS-20`, { ...sc.spec, fromS: -20 }, speedFns[sc.id] || null);
  }
  add('speed60', { speed: 60 });
  add('speed100-fromS-20', { speed: 100, fromS: -20 });
  add('speed80-fromS-14-heading350', { speed: 80, fromS: -14, baseHeading: 350 });
  add('speed120-slow-lc-fromS-20', { speed: 120, fromS: -20, laneChange: { startS: -8, durS: 7.5, dir: 'LEFT' }, signal: { fromS: -10, dir: 'LEFT' } });
  add('speed50-lc-signal-toS', { speed: 50, laneChange: { startS: -4, durS: 4, dir: 'RIGHT' }, signal: { fromS: -6, toS: -3, dir: 'RIGHT' }, brakeFromS: -0.5, airbag: true });
  add('speed33.35-rounding-fromS-1', { speed: 33.35, fromS: -1 });
  return entries;
}

// ---------- node:test interception ----------
const realTest = require('node:test');
const tests = [];
function wrapTest(name, a, b) {
  const fn = typeof a === 'function' ? a : b;
  const opts = typeof a === 'function' ? undefined : a;
  const rec = { index: tests.length + 1, name: String(name), status: 'pending', calls: [] };
  tests.push(rec);
  const wrapped = async function (t, ...rest) {
    try {
      const r = await als.run({ rec, inCall: false }, () => fn.call(this, t, ...rest));
      rec.status = 'pass';
      return r;
    } catch (e) { rec.status = 'fail'; rec.error = String((e && e.message) || e); throw e; }
  };
  return opts === undefined ? realTest(name, wrapped) : realTest(name, opts, wrapped);
}
Object.assign(wrapTest, realTest);
wrapTest.test = wrapTest;
const origLoad = Module._load;
Module._load = function (request, parent, isMain) {
  if (request === 'node:test' || request === 'test') return wrapTest;
  return origLoad.apply(this, arguments);
};

// ---------- output ----------
function slugOf(name) {
  const s = name.replace(/[^A-Za-z0-9_]+/g, '-').replace(/^-+|-+$/g, '').replace(/-{2,}/g, '-');
  return s.slice(0, 48).replace(/-+$/, '');
}
const writeJson = (rel, obj) => {
  const p = path.join(OUT, rel);
  fs.mkdirSync(path.dirname(p), { recursive: true });
  const s = JSON.stringify(obj, null, 1) + '\n';
  fs.writeFileSync(p, s);
  return s.length;
};

const FORMAT_MD = (() => {
  const lines = fs.readFileSync(__filename, 'utf8').split('\n').slice(1);
  const out = [];
  for (const l of lines) { if (!l.startsWith('//')) break; out.push(l.replace(/^\/\/ ?/, '')); }
  return out;
})();

(async () => {
  fs.rmSync(OUT, { recursive: true, force: true });
  fs.mkdirSync(OUT, { recursive: true });
  const speedFns = scenarioSpeedFns();
  const scenarioFiles = await dumpScenarios(speedFns);
  const synth = dumpSynth(speedFns);

  process.on('exit', () => {
    const index = { generatedBy: 'test/dump-golden.js', fixedNow: FIXED_NOW, frozenClock: FROZEN_CLOCK, tests: [], scenarios: [], synth: 'synth.json', frames: [] };
    for (const rec of tests) {
      const slug = slugOf(rec.name);
      const file = `tests/t${String(rec.index).padStart(3, '0')}${slug ? '-' + slug : ''}.json`;
      writeJson(file, rec);
      const fnCounts = {};
      for (const c of rec.calls) fnCounts[c.fn] = (fnCounts[c.fn] || 0) + 1;
      index.tests.push({ index: rec.index, name: rec.name, file, status: rec.status, calls: rec.calls.length, fnCounts });
    }
    for (const s of scenarioFiles) { writeJson(s.file, s.doc); index.scenarios.push({ id: s.id, title: s.title, file: s.file }); }
    writeJson('synth.json', { entries: synth });
    for (const id of [...frameStore.keys()].sort()) { writeJson(`frames/${id}.json`, frameStore.get(id)); index.frames.push(id); }
    index.sealChecks = { verified: sealChecks.ok, mismatched: sealChecks.bad.length };
    index.hazardCounts = hazards;
    writeJson('index.json', index);
    fs.writeFileSync(path.join(OUT, 'FORMAT.md'), '# Golden fixtures format\n\n```\n' + FORMAT_MD.join('\n') + '\n```\n');

    const failed = tests.filter((t) => t.status !== 'pass');
    const empty = tests.filter((t) => t.calls.length === 0);
    process.stderr.write(`\n[golden] ${tests.length} tests (${failed.length} not passing), ${scenarioFiles.length} scenarios, ${synth.length} synth entries, ${frameStore.size} frame sets\n`);
    process.stderr.write(`[golden] seal checks: ${sealChecks.ok} ok, ${sealChecks.bad.length} mismatched; hazards ${JSON.stringify(hazards)}\n`);
    if (empty.length) process.stderr.write(`[golden] tests with no F.* calls: ${empty.map((t) => t.index + ' ' + t.name).join(' | ')}\n`);
    if (failed.length || sealChecks.bad.length) process.exitCode = 1;
  });

  require('./engine.test.js');
})().catch((e) => { console.error(e); process.exit(1); });
