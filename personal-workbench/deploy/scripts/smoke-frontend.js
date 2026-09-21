// 前端冒烟自检（M9-T04）：DOM id 完整性、tab 视图配对、CDN 外链、可访问性尺寸、JS 语法
// 用法：node deploy/scripts/smoke-frontend.js
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const root = path.resolve(__dirname, "..", "..");
const staticDir = path.join(root, "server", "src", "main", "resources", "static");
const read = (name) => fs.readFileSync(path.join(staticDir, name), "utf8");

const html = read("index.html");
const css = read("style.css");
const appJs = read("app.js");
const apiJs = read("api.js");

let failures = 0;
const fail = (msg) => { failures++; console.error("  [FAIL] " + msg); };
const ok = (msg) => console.log("  [ OK ] " + msg);

// 1. JS 语法检查
for (const [name, code] of [["app.js", appJs], ["api.js", apiJs]]) {
  try {
    new vm.Script(code, { filename: name });
    ok(name + " 语法正确");
  } catch (error) {
    fail(name + " 语法错误：" + error.message);
  }
}

// 2. app.js 中所有 $("id") 引用必须存在于 index.html
const htmlIds = new Set();
for (const match of html.matchAll(/id="([^"]+)"/g)) htmlIds.add(match[1]);
const jsIds = new Set();
for (const match of appJs.matchAll(/\$\("([^"]+)"\)/g)) jsIds.add(match[1]);
// 动态 id（edit-xxx、confirm-xxx、favorite-edit-xxx 等带前缀模板）跳过前缀匹配校验
for (const id of jsIds) {
  if (!htmlIds.has(id)) fail("app.js 引用了不存在的 DOM id：" + id);
}
const missing = [...jsIds].filter((id) => !htmlIds.has(id));
if (missing.length === 0) ok("全部 " + jsIds.size + " 个静态 DOM id 引用均存在");

// 3. 模板字符串里的动态 id 前缀（如 edit-${task.id}）必须有对应生成处
for (const prefix of ["edit-", "confirm-category-", "confirm-title-", "confirm-due-", "confirm-priority-", "confirm-start-", "favorite-edit-"]) {
  const produced = appJs.includes('id="' + prefix) || appJs.includes("id=\"" + prefix);
  if (!produced) fail("动态 id 前缀未在渲染函数中生成：" + prefix);
}
ok("动态 id 前缀生成处检查完成");

// 4. data-tab 与 view 配对
const tabs = [...html.matchAll(/data-tab="([^"]+)"/g)].map((m) => m[1]);
for (const tab of tabs) {
  if (!htmlIds.has("view-" + tab)) fail("tab「" + tab + "」缺少对应视图 view-" + tab);
}
const views = [...html.matchAll(/id="view-([^"]+)"/g)].map((m) => m[1]);
for (const view of views) {
  if (!tabs.includes(view)) fail("视图 view-" + view + " 没有对应 tab");
}
if (failures === 0) ok(tabs.length + " 个 tab 与视图全部配对");

// 5. 禁止 CDN / 外链资源（允许命名空间与注释中的说明性文字）
const external = [...html.matchAll(/(?:src|href)\s*=\s*"https?:\/\/[^"]+"/g)];
if (external.length > 0) fail("index.html 存在外链资源：" + external.join(", "));
const cssExternal = [...css.matchAll(/url\(\s*['"]?https?:\/\/[^)]+\)/g)];
if (cssExternal.length > 0) fail("style.css 存在外链资源：" + cssExternal.join(", "));
if (external.length === 0 && cssExternal.length === 0) ok("无 CDN 与外链资源");

// 6. 可访问性尺寸：按钮 ≥44px、输入框字号 ≥16px
if (!/min-height:\s*4[4-9]px/.test(css) && !/height:\s*4[4-9]px/.test(css) && !/padding:[^;}]*1[2-9]px/.test(css)) {
  fail("style.css 未发现按钮 ≥44px 的尺寸规则");
} else ok("按钮尺寸规则存在");
if (!/font-size:\s*1[6-9]px/.test(css)) {
  fail("style.css 未发现输入框字号 ≥16px 的规则");
} else ok("输入框字号规则存在");

// 7. localStorage 不得存业务数据
if (/localStorage\.(setItem|getItem)/.test(appJs)) fail("app.js 仍在使用 localStorage");
else ok("未使用 localStorage");

// 8. 渲染函数无环：render 函数不得调用其他 render 函数
const renderFns = [...appJs.matchAll(/function (render\w+)\(/g)].map((m) => m[1]);
for (const fn of renderFns) {
  const body = appJs.split("function " + fn + "(")[1];
  if (!body) continue;
  const segment = body.slice(0, body.indexOf("\nfunction ") === -1 ? body.length : body.indexOf("\nfunction "));
  for (const other of renderFns) {
    if (other !== fn && new RegExp(other + "\\(").test(segment)) {
      fail("渲染函数存在调用：" + fn + " → " + other);
    }
  }
}
if (failures === 0) ok(renderFns.length + " 个渲染函数无相互调用（无环）");

console.log("");
if (failures > 0) {
  console.error("前端冒烟失败：" + failures + " 项");
  process.exit(1);
}
console.log("前端冒烟全部通过。");
