#!/usr/bin/env node
'use strict';
// 퍼스트30 — number formatting corpus for the Kotlin port (Node >= 18, no deps).
//
//   npm run numbers   (= node test/dump-numbers.js)
//
// Writes android/core/src/test/resources/numbers.json (outside golden/, which dump-golden.js wipes):
//   { generatedBy, node, count, cases: [ [bitsHex, String(x), JSON.stringify(x)], ... ] }
// bitsHex is the 16-hex-digit IEEE-754 bit pattern (lossless, keeps -0 and NaN payloads apart).
// The Kotlin side (seal/CanonicalTest.kt) checks jsNumberToString / stableStringify against every row.
const fs = require('fs');
const path = require('path');

const OUT = path.join(__dirname, '..', 'android', 'core', 'src', 'test', 'resources', 'numbers.json');

const buf = new DataView(new ArrayBuffer(8));
const bitsOf = (x) => { buf.setFloat64(0, x); return buf.getBigUint64(0).toString(16).padStart(16, '0'); };
const fromBits = (big) => { buf.setBigUint64(0, BigInt.asUintN(64, big)); return buf.getFloat64(0); };

// mulberry32 — deterministic 32-bit PRNG
let seed = 0x30f1257;
function rnd32() {
  seed = (seed + 0x6d2b79f5) >>> 0;
  let t = seed;
  t = Math.imul(t ^ (t >>> 15), t | 1);
  t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
  return ((t ^ (t >>> 14)) >>> 0);
}
const rnd = () => rnd32() / 4294967296;
const rnd64 = () => (BigInt(rnd32()) << 32n) | BigInt(rnd32());

const seen = new Set();
const cases = [];
function add(x) {
  const b = bitsOf(x);
  if (seen.has(b)) return;
  seen.add(b);
  cases.push([b, String(x), JSON.stringify(x)]);
}
const addPm = (x) => { add(x); add(-x); };

// 1. edge cases
[0, -0, NaN, Infinity, -Infinity, 1, 0.1, 0.2, 0.1 + 0.2, 0.3, 1 / 3, 2 / 3, Math.PI, Math.E,
  Number.MIN_VALUE, Number.MAX_VALUE, Number.EPSILON, Number.MAX_SAFE_INTEGER, Number.MIN_SAFE_INTEGER,
  2 ** 53, 2 ** 53 + 2, 2 ** 63, 2 ** 64, 1e21, 1e21 - 65536, 999999999999999900000, 123456789012345680000,
  1e-6, 1e-7, 1.5e-7, 0.000001, 0.0000012345, 9.999999999999999e-7, 5e-324, 1e-323, 2.2250738585072014e-308,
  2.225073858507201e-308, 1.7976931348623157e308, 4.35, 0.15, 1.005, 100, 1e100, 1e-100, 123.456,
  -273.15, 37.5, 59.95, 0.5, 1.5, 2.5, 1e15, 1e16, 1e17, 9007199254740993, 4294967295, 4294967296,
].forEach(addPm);
// powers of two and ten, and their neighbours
for (let e = -1074; e <= 1023; e++) addPm(2 ** e);
for (let e = -323; e <= 308; e++) {
  const x = Number('1e' + e);
  addPm(x);
  if (x > 0 && Number.isFinite(x)) {
    const b = BigInt('0x' + bitsOf(x));
    addPm(fromBits(b + 1n)); addPm(fromBits(b - 1n));
  }
}
// thresholds of the exponent notation
for (let i = -3; i <= 3; i++) { addPm(1e21 + i * 131072); addPm(1e-6 * (1 + i * 1e-15)); addPm(1e-7 * (1 + i * 1e-15)); }
// subnormals and the largest doubles
for (let i = 1n; i <= 64n; i++) { addPm(fromBits(i)); addPm(fromBits(0x7fefffffffffffffn - i + 1n)); addPm(fromBits(0x0010000000000000n - i)); }

// 2. random bit patterns (all exponents)
for (let i = 0; i < 6000; i++) {
  const x = fromBits(rnd64());
  if (!Number.isNaN(x)) add(x);
}
// 3. random mantissa at exponents in the everyday range (|x| in 1e-9 .. 1e25)
for (let i = 0; i < 5000; i++) {
  const exp = Math.floor(rnd() * 115) - 30; // 2^-30 .. 2^84
  const mant = (BigInt(rnd32() & 0xfffff) << 32n) | BigInt(rnd32());
  const bits = (BigInt(exp + 1023) << 52n) | mant;
  const x = fromBits(bits);
  add(rnd() < 0.5 ? x : -x);
}
// 4. decimals as the engine makes them: round(x * 10) / 10 and friends
for (let i = 0; i < 6000; i++) {
  const mag = 10 ** (Math.floor(rnd() * 8) - 2);
  const v = (rnd() * 2 - 1) * mag * 360;
  const k = [1, 10, 100, 1000][i % 4];
  add(Math.round(v * k) / k);
  if (i % 3 === 0) add((Math.round(v * 10) / 10) * 0.1);
}
// 5. short decimal strings parsed back, sums of tenths
for (let i = 0; i < 2000; i++) {
  const digits = 1 + Math.floor(rnd() * 17);
  let s = '';
  for (let j = 0; j < digits; j++) s += String(Math.floor(rnd() * 10));
  const e = Math.floor(rnd() * 60) - 30;
  add(Number(s + 'e' + e));
}
let acc = 0;
for (let i = 0; i < 300; i++) { acc += 0.1; add(acc); add(-acc * 3); }

fs.writeFileSync(OUT, JSON.stringify({
  generatedBy: 'test/dump-numbers.js', node: process.version, count: cases.length, cases,
}) + '\n');
console.log(`wrote ${cases.length} cases -> ${path.relative(process.cwd(), OUT)}`);
