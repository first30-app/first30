// demo/src.html 의 <!--ENGINE--> 자리에 엔진 파일을 그대로 넣어 단일 HTML 두 가지를 만든다.
//   demo/index.html    — 브라우저로 바로 여는 단독 실행 파일 (doctype·charset·viewport·lang 포함)
//   demo/artifact.html — 게시용 조각 (게시할 때 문서 뼈대가 덧씌워지므로 <html>/<head>/<body> 없이)
'use strict';
const fs = require('fs');
const path = require('path');
const root = path.join(__dirname, '..');
const files = ['engine/charts.js', 'engine/engine.js', 'engine/local-judge.js', 'engine/jev-client.js', 'scenarios/scenarios.js'];
const engine = files.map((f) => `// ---- ${f} ----\n` + fs.readFileSync(path.join(root, f), 'utf8')).join('\n');
const src = fs.readFileSync(path.join(__dirname, 'src.html'), 'utf8');
if (!src.includes('<!--ENGINE-->')) throw new Error('src.html 에 <!--ENGINE--> 자리가 없습니다');
const fragment = src.replace('<!--ENGINE-->', `<script>\nwindow.First30 = window.First30 || {};\n${engine.replace(/<\/script/gi, '<\\/script')}\n</script>`);

const cut = fragment.indexOf('</style>');
if (cut < 0) throw new Error('src.html 에 </style> 이 없습니다');
const head = fragment.slice(0, cut + '</style>'.length);
const body = fragment.slice(cut + '</style>'.length);
const standalone = `<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<style>body { margin: 0; } [hidden] { display: none !important; }</style>
${head}
</head>
<body>
${body}
</body>
</html>
`;
fs.writeFileSync(path.join(__dirname, 'index.html'), standalone);
fs.writeFileSync(path.join(__dirname, 'artifact.html'), fragment);
console.log('demo/index.html', Buffer.byteLength(standalone, 'utf8'), 'bytes · demo/artifact.html', Buffer.byteLength(fragment, 'utf8'), 'bytes');
