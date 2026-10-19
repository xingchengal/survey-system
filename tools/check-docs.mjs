#!/usr/bin/env node
/**
 * 文档一致性检查（docs/ARCHITECTURE.md / docs/AUDIT.md / SPRINT.md / docs/HISTORY.md）
 *
 * 检查项：
 *   1. 端点总数：标题 = §二汇总 = 四表活行 = Controller 注解计数
 *   2. §四 管理端点的权限点列与 Controller 注解一致
 *   3. 文档内部相对链接全部存在
 *   4. README 的规模行与 AUDIT 事实快照一致
 *   5. ARCHITECTURE / SPRINT 不出现轮次、改造前等历史叙事
 *   6. HISTORY 行数在 600~900（超出只警告，不失败）
 *
 * 用法：node tools/check-docs.mjs
 * 退出码：0 = 通过；1 = 有硬错误
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');
const docs = path.join(root, 'docs');
const errors = [];
const warnings = [];

const read = (p) => fs.readFileSync(p, 'utf8').replace(/\r\n/g, '\n');
const num = (s) => Number(String(s).replace(/[^\d]/g, ''));

// ---------------------------------------------------------------- 1. 端点计数
const audit = read(path.join(docs, 'AUDIT.md'));
const title = num((audit.match(/全量 \*\*(\d+)\*\* 个方法级端点/) || [])[1]);
const summary = num((audit.match(/\| \*\*合计\*\* \| \*\*(\d+)\*\* \|/) || [])[1]);
const liveRows = audit
  .split('\n')
  .filter((l) => /^\| `(GET|POST|PUT|DELETE|PATCH) \/api\//.test(l) && !l.includes('~~')).length;

const controllerDir = path.join(root, 'survey-backend/src/main/java/com/survey/controller');
if (!fs.existsSync(controllerDir)) {
  errors.push(`Controller 目录不存在：${controllerDir}`);
}
const controllerFiles = fs.existsSync(controllerDir)
  ? fs.readdirSync(controllerDir).filter((f) => f.endsWith('.java'))
  : [];
let annotations = 0;
for (const f of controllerFiles) {
  annotations += (read(path.join(controllerDir, f)).match(/@(Get|Post|Put|Delete|Patch)Mapping/g) || []).length;
}

const counts = { title, summary, liveRows, annotations };
const countValues = Object.values(counts);
if (countValues.some((v) => !Number.isFinite(v) || v <= 0)) {
  errors.push(`端点计数解析失败：${JSON.stringify(counts)}`);
} else if (new Set(countValues).size !== 1) {
  errors.push(`端点计数不一致：${JSON.stringify(counts)}（标题 / 汇总 / 四表活行 / 注解计数必须相等）`);
} else {
  console.log(`[OK] endpoints: ${JSON.stringify(counts)}`);
}

// ---------------------------------------------------------------- 2. 管理端点权限点
const permSrc = read(path.join(root, 'survey-backend/src/main/java/com/survey/common/Permission.java'));
const constMap = {};
for (const m of permSrc.matchAll(/public\s+static\s+final\s+String\s+([A-Z_]+)\s*=\s*"([^"]+)"/g)) {
  constMap[m[1]] = m[2];
}

function collect(lines, from, to) {
  let admin = false;
  let perm = null;
  for (let k = from; k < to && k < lines.length; k++) {
    const t = lines[k].trim();
    if (!t.startsWith('@')) continue;
    if (t.startsWith('@RequireAdmin')) admin = true;
    const m = t.match(/@RequirePermission\(\s*(?:"([^"]+)"|Permission\.([A-Z_]+))/);
    if (m) perm = m[1] || constMap[m[2]] || m[2];
  }
  return { admin, perm };
}
function before(lines, idx) {
  let admin = false;
  let perm = null;
  for (let k = idx - 1; k >= 0; k--) {
    const t = lines[k].trim();
    if (!t || t.startsWith('*') || t.startsWith('//') || t.startsWith('/*') || t.startsWith('*/')) continue;
    if (!t.startsWith('@')) break;
    if (t.startsWith('@RequireAdmin')) admin = true;
    const m = t.match(/@RequirePermission\(\s*(?:"([^"]+)"|Permission\.([A-Z_]+))/);
    if (m) perm = m[1] || constMap[m[2]] || m[2];
  }
  return { admin, perm };
}

const actual = new Map();
for (const f of controllerFiles) {
  const lines = read(path.join(controllerDir, f)).split('\n');
  const classIdx = lines.findIndex((l) => /public\s+class\s+/.test(l));
  if (classIdx < 0) continue;
  const prefix = (lines.slice(0, classIdx).join('\n').match(/@RequestMapping\(\s*"([^"]+)"/) || [])[1] || '';
  let classAdmin = false;
  let classPerm = null;
  for (let k = classIdx - 1; k >= 0; k--) {
    const t = lines[k].trim();
    if (!t || t.startsWith('*') || t.startsWith('//') || t.startsWith('/*') || t.startsWith('*/')) continue;
    if (!t.startsWith('@')) break;
    if (t.startsWith('@RequireAdmin')) classAdmin = true;
    const m = t.match(/@RequirePermission\(\s*(?:"([^"]+)"|Permission\.([A-Z_]+))/);
    if (m) classPerm = m[1] || constMap[m[2]] || m[2];
  }
  for (let i = 0; i < lines.length; i++) {
    const m = lines[i].match(/@(Get|Post|Put|Delete|Patch)Mapping(?:\(\s*(?:value\s*=\s*)?"([^"]*)"[^)]*\))?/);
    if (!m) continue;
    const method = m[1].toUpperCase();
    const sub = m[2] || '';
    let end = i + 1;
    while (end < lines.length && !/^\s*(public|protected)\s/.test(lines[end])) end++;
    const b = before(lines, i);
    const a = collect(lines, i + 1, end);
    const perm = a.perm || b.perm || classPerm;
    const admin = a.admin || b.admin || classAdmin;
    const tag = perm ? `@RequirePermission("${perm}")` : admin ? '@RequireAdmin' : '需登录/公开';
    const full = (prefix + (sub ? (sub.startsWith('/') ? sub : '/' + sub) : '')).replace(/\/+/g, '/') || prefix;
    actual.set(`${method} ${full}`, tag);
  }
}

const adminLines = audit
  .split('\n')
  .filter((l) => /^\| `(GET|POST|PUT|DELETE|PATCH) \/api\/[^`]+` \| `[^`]+` \|/.test(l));
let permMismatch = 0;
for (const line of adminLines) {
  const m = line.match(/^\| `([A-Z]+) (\/api\/[^`]+)` \| `([^`]+)` \|/);
  if (!m) continue;
  const key = `${m[1]} ${m[2]}`;
  const expect = actual.get(key);
  if (!expect) {
    errors.push(`AUDIT §四 端点未在 Controller 中找到：${key}`);
    permMismatch++;
  } else if (expect !== m[3]) {
    errors.push(`权限点不一致：${key}，AUDIT=${m[3]}，Controller=${expect}`);
    permMismatch++;
  }
}
for (const [key, tag] of actual) {
  if (tag !== '需登录/公开' && !adminLines.some((l) => l.includes(`\`${key}\``))) {
    // 管理端点必须登记在 §四；这里只提示，不当作硬错误（有些公开端点也可能有注解？没有）
    warnings.push(`Controller 中有权限注解但 AUDIT §四 未登记：${key} (${tag})`);
  }
}
if (permMismatch === 0) {
  console.log(`[OK] admin permissions: ${adminLines.length} rows matched`);
}

// ---------------------------------------------------------------- 3. 链接
const linkFiles = [
  path.join(root, 'README.md'),
  path.join(root, 'SPRINT.md'),
  ...fs.readdirSync(docs).filter((f) => f.endsWith('.md')).map((f) => path.join(docs, f)),
];
let linkCount = 0;
for (const f of linkFiles) {
  if (!fs.existsSync(f)) continue;
  const text = read(f);
  for (const m of text.matchAll(/\]\(([^)]+)\)/g)) {
    let target = m[1].trim();
    if (!target || target.startsWith('http://') || target.startsWith('https://') || target.startsWith('#') || target.startsWith('mailto:')) continue;
    target = target.split('#')[0].trim();
    if (!target) continue;
    linkCount++;
    const p = path.resolve(path.dirname(f), target);
    if (!fs.existsSync(p)) errors.push(`断链：${path.relative(root, f)} -> ${target}`);
  }
}
if (!errors.some((e) => e.startsWith('断链'))) console.log(`[OK] links: ${linkFiles.length} files, ${linkCount} links`);

// ---------------------------------------------------------------- 4. README 规模
const readme = read(path.join(root, 'README.md'));
const scale = readme.match(
  /规模：\*\*(\d+)\*\* 个 REST 端点 · \*\*(\d+)\*\* 张表 · \*\*(\d+)\*\* 个后端用例（(\d+) 类）· \*\*(\d+)\*\* 个前端页面/
);
const snap = (label) => {
  const m = audit.match(new RegExp(`\\| ${label} \\| \\*\\*(\\d+)`));
  return m ? Number(m[1]) : null;
};
const expected = [snap('方法级端点'), snap('数据库表'), snap('后端测试'), null, snap('前端页面')];
if (!scale) {
  errors.push('README 未找到“规模：…”行，无法核对');
} else {
  const got = scale.slice(1, 6).map(Number);
  if (got[0] !== expected[0] || got[1] !== expected[1] || got[2] !== expected[2] || got[4] !== expected[4]) {
    errors.push(`README 规模与 AUDIT 不一致：README=${got.join('/')}，AUDIT=${expected.join('/')}`);
  } else {
    console.log(`[OK] README scale: ${got.join('/')}`);
  }
}

// ---------------------------------------------------------------- 5. 历史叙事泄漏
for (const f of [path.join(docs, 'ARCHITECTURE.md'), path.join(root, 'SPRINT.md')]) {
  if (!fs.existsSync(f)) {
    errors.push(`缺少文档：${path.relative(root, f)}`);
    continue;
  }
  const text = read(f);
  const hits = [...text.matchAll(/第[一二三四五六七八九十百]+轮|改造前|原来/g)];
  if (hits.length) {
    errors.push(`${path.relative(root, f)} 出现历史叙事 ${hits.length} 处（应只写现状；历史进 HISTORY）`);
  }
}
if (!errors.some((e) => e.includes('历史叙事'))) console.log('[OK] history leak: 0');

// ---------------------------------------------------------------- 6. HISTORY 体量
const historyPath = path.join(docs, 'HISTORY.md');
if (fs.existsSync(historyPath)) {
  const n = read(historyPath).split('\n').length;
  if (n < 600 || n > 900) warnings.push(`HISTORY.md 行数=${n}，维护约定是 600~900`);
  else console.log(`[OK] HISTORY lines: ${n}`);
}

// ---------------------------------------------------------------- 汇总
for (const w of warnings) console.log(`[WARN] ${w}`);
if (errors.length) {
  for (const e of errors) console.log(`[FAIL] ${e}`);
  process.exit(1);
}
console.log('\n文档一致性检查通过。');
