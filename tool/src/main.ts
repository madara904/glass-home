import { api, type App, type DebloatOptions, type Device, type Found, type DeviceState, type Step, type UiOptions } from './api';
import { flag, icon, mark } from './icons';
import { lang, setLang, t, tr, type Lang } from './i18n';
import { AUTHORS, REPO, openDevtools, openGithub, windowAction, windowButtons } from './titlebar';
import { splash } from './splash';

// The desktop tool in the look of the app icon: its colours as the backdrop, frosted white glass
// on top, a list like the TV's settings. The left column says where you are; the glass panel on
// the right holds the choices and then the progress of whatever runs.

const root = document.getElementById('app')!;
let device: Device | null = null;
let state: DeviceState | null = null;
let open: string | null = null;
let view: () => void = () => {};

const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]!));
const lines = (s: string) => esc(s).replace(/\n/g, '<br>');

const INTRO = 'glasshome.intro';
const introSeen = () => { try { return localStorage.getItem(INTRO) === '1'; } catch { return false; } };

const LANGS: Record<Lang, string> = { en: 'English', de: 'Deutsch' };
let shown = '';

/** Draws a screen. The left column fades in only when the screen itself changes, not when a
 *  panel opens or closes or the language switches. */
function frame(key: string, side: string, panel = '') {
  const enter = key !== shown;
  shown = key;
  const pill = device
    ? `<div class="pill">${icon.tv(18)}<b>${esc(device.name)}</b>${esc(device.ip)}</div>
       <button class="quiet" data-act="disconnect">${t.disconnect}</button>`
    : `<div class="pill">${icon.tv(18)}${t.noDevice}</div>`;
  root.innerHTML = `
    <div class="sky"></div>
    <div class="top">${mark()}<div class="word">Glass Home</div><div class="spacer"></div>${pill}
      <div class="lang"><button class="quiet" data-act="lang-menu">${flag(lang)}${t.langName}${icon.chevronDown(16)}</button>
        <div class="menu">${(["en", "de"] as Lang[]).map((l) =>
          `<button class="item" data-lang="${l}">${flag(l)}<span>${LANGS[l]}</span>${l === lang ? icon.check(16) : ""}</button>`).join("")}</div></div>
      ${windowButtons()}</div>
    <div class="side"><div class="${enter ? "fade" : ""}">${side}</div></div>${panel}
    <div class="byline">${icon.github(16)}<button class="quiet" data-gh="${REPO}">${REPO}</button><span>·</span>${AUTHORS.map((a) => `<button class="quiet" data-gh="${a}">${a}</button>`).join('<span>·</span>')}</div>`;
}

// ---------------------------------------------------------------- intro

function introView() {
  view = introView;
  const step = (ic: string, name: string, path: string) =>
    `<div class="row small static">${ic}<span class="name">${name}<span class="path">${path}</span></span></div>`;
  frame('intro', `
      <h1>${lines(t.introTitle)}</h1>
      <p class="sub">${t.introSub}</p>
      <div class="list">
        ${step(icon.settings(), t.introAbout, t.introAboutPath)}
        ${step(icon.press(), t.introPress, t.introPressPath)}
        ${step(icon.toggle(), t.introAdb, t.introAdbPath)}
        ${step(icon.wifi(), t.introWifi, t.introWifiPath)}
      </div>
      <div class="below"><button class="go" data-act="intro-done">${t.introGo}</button><span class="aside">${icon.info(16)}${t.introVega}</span></div>
    `);
}

// ---------------------------------------------------------------- connect

/** The connect screen. The network search starts by itself; typing the address is the fallback.
 *  A redraw (language switch) keeps the last results instead of searching again. */
function connectView(error = '', search = true) {
  view = () => connectView('', false);
  device = null;
  state = null;
  frame('connect', `
      <h1>${lines(t.connectTitle)}</h1>
      <p class="sub">${t.connectSub}</p>
      <div class="found" id="found">${foundList()}</div>
      <div class="below">
        <button class="quiet" data-act="scan" ${searching ? 'disabled' : ''}>${icon.reload(16)}${t.scan}</button>
        <button class="quiet" data-act="intro">${icon.info(16)}${t.howTo}</button>
      </div>
      <div class="manual">
        <p class="label">${t.manualTitle}</p>
        <div class="field">
          <input id="ip" placeholder="192.168.x.x" spellcheck="false" autocomplete="off" />
          <button class="go" data-act="connect">${t.connect}</button>
        </div>
        <p class="hint">${t.manualHint}</p>
      </div>
      <p class="error" id="err">${esc(error)}</p>
    `);
  const ip = document.getElementById('ip') as HTMLInputElement;
  ip.addEventListener('keydown', (e) => { if (e.key === 'Enter') connect(ip.value); });
  if (search && !searching) scan();
}

async function connect(ip: string) {
  ip = ip.trim();
  const err = document.getElementById('err')!;
  if (!/^\d{1,3}(\.\d{1,3}){3}$/.test(ip)) { err.textContent = t.badIp; return; }
  const go = root.querySelector<HTMLButtonElement>('[data-act=connect]')!;
  go.disabled = true;
  go.textContent = t.connecting;
  err.textContent = t.allow;
  err.classList.add('calm');
  try {
    const d = await api.connect(ip);
    if (!d.supported) { connectView(tr(d.reason) || t.unsupported, false); return; }
    device = d;
    await refresh();
  } catch (e) {
    connectView(tr(String(e)), false);
  }
}

let found: Found[] | null = null;
let searching = false;
let searchError = '';

/** The search results: Fire TVs first, then other devices with ADB open; Google TVs greyed out. */
function foundList(): string {
  if (searching && !found?.length) {
    return `<div class="list"><div class="row small static">${icon.spinner(18)}<span class="name">${t.scanning}</span></div></div>`;
  }
  if (searchError) return `<p class="error">${esc(tr(searchError))}</p>`;
  if (!found) return '';
  if (!found.length) return `<p class="error calm">${t.scanNone}</p>`;
  const row = (d: Found) => {
    const name = esc(d.name || d.ip);
    const what = d.model || (d.kind === 'fire' ? t.fireTv : d.vendor);
    const path = `<span class="path">${what ? `${esc(what)} · ` : ''}${esc(d.ip)}</span>`;
    if (d.kind === 'google') {
      return `<button class="row small" disabled>${icon.tv()}<span class="name">${name}${path}</span><span class="value">${t.notFire}</span></button>`;
    }
    if (!d.adb) {
      return `<button class="row small" data-act="intro">${icon.tv()}<span class="name">${name}${path}</span><span class="value">${t.turnOnAdb}</span>${icon.chevron(18)}</button>`;
    }
    return `<button class="row small" data-ip="${esc(d.ip)}">${icon.tv()}<span class="name">${name}${path}</span><span class="value">${t.adbOpen}</span>${icon.chevron(18)}</button>`;
  };
  return `<div class="list">${found.map(row).join('')}</div>`;
}

async function scan() {
  searching = true;
  searchError = '';
  const redraw = () => {
    const el = document.getElementById('found');
    if (!el) return; // the screen changed meanwhile
    el.innerHTML = foundList();
    const again = root.querySelector<HTMLButtonElement>('[data-act=scan]');
    if (again) again.disabled = searching;
  };
  redraw();
  try {
    found = await api.discover();
  } catch (e) {
    found = null;
    searchError = String(e);
  } finally {
    searching = false;
  }
  redraw();
}

// ---------------------------------------------------------------- device

async function refresh() {
  state = await api.state();
  open = null;
  deviceView();
}

function deviceView() {
  if (!device || !state) return;
  view = reopen;
  const s = state;
  const changed = s.debloated || s.glassUi;
  frame('device', `
      <h1>${esc(device.name)}</h1>
      <p class="sub">${esc(tr(device.fireOs))} · ${esc(device.model)}</p>
      <div class="list">
        <button class="row ${open === 'debloat' ? 'on' : ''}" data-act="debloat"><span class="name">${t.debloat}</span>
          <span class="value">${s.debloated ? t.debloatOn(s.disabled) : t.notApplied}</span>${icon.chevron(18)}</button>
        <button class="row ${open === 'ui' ? 'on' : ''}" data-act="ui"><span class="name">${t.glassUi}</span>
          <span class="value">${s.glassUi ? t.installed(esc(s.glassVersion ?? '')) : t.notInstalled}</span>${icon.chevron(18)}</button>
        <button class="row ${open === 'restore' ? 'on' : ''}" data-act="restore" ${changed ? '' : 'disabled'}><span class="name">${t.restore}</span>
          <span class="value">${changed ? t.restoreValue : t.nothingToDo}</span>${icon.chevron(18)}</button>
      </div>
      <p class="foot">${t.foot}</p>
    `);
}

let slide = true;

/** After a language switch: the same screen again, with its panel standing still if one was open. */
function reopen() {
  const was = open;
  deviceView();
  slide = false;
  if (was === 'debloat') debloatPanel();
  else if (was === 'ui') uiPanel();
  else if (was === 'restore') restorePanel();
  slide = true;
}

/** Opens a panel next to the list as it stands: the left side is not drawn again. */
function showPanel(key: string, html: string) {
  open = key;
  if (shown !== 'device' || !root.querySelector('.side .list')) deviceView();
  root.querySelectorAll<HTMLElement>('.side .row[data-act]').forEach((r) => r.classList.toggle('on', r.dataset.act === key));
  root.querySelector('.panel')?.remove();
  root.insertAdjacentHTML('beforeend', `<div class="panel ${slide ? '' : 'on still'}">${html}</div>`);
  const panel = root.querySelector<HTMLElement>('.panel')!;
  if (slide) requestAnimationFrame(() => requestAnimationFrame(() => panel.classList.add('on')));
  return panel;
}

/** Slides the panel out; the list stays where it is. */
function closePanel() {
  open = null;
  root.querySelectorAll('.side .row.on').forEach((r) => r.classList.remove('on'));
  const panel = root.querySelector<HTMLElement>('.panel');
  if (!panel) return;
  panel.classList.remove('on', 'still');
  setTimeout(() => panel.remove(), 320);
}

const option = (key: string, name: string, value: string) =>
  `<button class="row small" data-opt="${key}"><span class="name">${name}</span><span class="value">${value}</span></button>`;

async function debloatPanel() {
  const o: DebloatOptions = { keepPrime: true, keepAlexa: true, keepUpdates: true, dnsFilter: true, remove: [] };
  const label = (k: keyof DebloatOptions) => (k === 'dnsFilter' ? (o.dnsFilter ? t.on : t.off) : o[k] ? t.keep : t.off);
  const panel = showPanel('debloat', `
    <header><h2>${t.debloat}</h2><p>${t.debloatSub}</p></header>
    <div class="body">
      ${option('keepPrime', t.primeVideo, label('keepPrime'))}
      ${option('keepAlexa', t.alexa, label('keepAlexa'))}
      ${option('keepUpdates', t.updates, label('keepUpdates'))}
      ${option('dnsFilter', t.dnsFilter, label('dnsFilter'))}
      <div class="label">${t.yourApps}</div>
      <div id="apps"><p class="note">${t.readingApps}</p></div>
      <p class="note">${t.removeNote}</p>
    </div>
    <footer><button class="quiet" data-act="close">${t.cancel}</button><button class="go" data-act="go">${t.start}</button></footer>`);
  panel.querySelectorAll<HTMLElement>('[data-opt]').forEach((r) => r.addEventListener('click', () => {
    const k = r.dataset.opt as 'keepPrime' | 'keepAlexa' | 'keepUpdates' | 'dnsFilter';
    o[k] = !o[k];
    r.querySelector('.value')!.textContent = label(k);
  }));
  panel.querySelector('[data-act=go]')!.addEventListener('click', () => run(panel, t.debloat, (s) => api.debloat(o, s)));
  const apps: App[] = await api.apps().catch(() => []);
  const box = panel.querySelector('#apps');
  if (!box || !panel.isConnected) return;
  box.innerHTML = apps.length
    ? apps.map((a) => `<button class="row small" data-app="${esc(a.pkg)}"><span class="name">${esc(a.label)}<span class="path mono">${esc(a.pkg)}</span></span><span class="value">${t.stays}</span></button>`).join('')
    : `<p class="note">${t.noApps}</p>`;
  panel.querySelectorAll<HTMLElement>('[data-app]').forEach((r) => r.addEventListener('click', () => {
    const pkg = r.dataset.app!;
    const gone = !o.remove.includes(pkg);
    o.remove = gone ? [...o.remove, pkg] : o.remove.filter((p) => p !== pkg);
    const v = r.querySelector('.value')!;
    v.textContent = gone ? t.remove : t.stays;
    v.classList.toggle('remove', gone);
  }));
}

function uiPanel() {
  const o: UiOptions = { layout: state?.layout ?? 'dock', tiles: state?.tiles ?? 'original' };
  const thumbs: Record<UiOptions['layout'], string> = {
    dock: '<i class="g" style="left:14%;right:14%;bottom:9px;height:20px"></i><i style="left:20%;bottom:13px;width:14%;height:12px"></i><i style="left:37%;bottom:13px;width:14%;height:12px"></i><i style="left:54%;bottom:13px;width:14%;height:12px"></i>',
    list: '<i class="g" style="left:9px;top:10px;width:58%;height:15px"></i><i style="left:13px;top:13px;width:14px;height:9px"></i><i style="left:13px;top:31px;width:14px;height:9px;opacity:.6"></i><i style="left:13px;top:49px;width:14px;height:9px;opacity:.6"></i>',
    icons: '<i style="left:12px;top:10px;width:22px;height:13px"></i><i style="left:12px;top:29px;width:22px;height:13px;opacity:.6"></i><i style="left:12px;top:48px;width:22px;height:13px;opacity:.6"></i><i style="left:48%;top:26px;width:32%;height:9px"></i>',
  };
  const tilesLabel = () => (o.tiles === 'glass' ? t.glassSymbols : t.originalBanners);
  const panel = showPanel('ui', `
    <header><h2>${t.glassUi}</h2><p>${t.uiSub}</p></header>
    <div class="body">
      <div class="label">${t.layout}</div>
      <div class="choices">${(Object.keys(thumbs) as UiOptions['layout'][]).map((k) =>
        `<button class="choice ${k === o.layout ? 'on' : ''}" data-layout="${k}"><div class="thumb">${thumbs[k]}</div><span>${t[k]}</span></button>`).join('')}</div>
      <div class="label">${t.tiles}</div>
      ${option('tiles', t.look, tilesLabel())}
    </div>
    <footer><button class="quiet" data-act="close">${t.cancel}</button><button class="go" data-act="go">${state?.glassUi ? t.update : t.install}</button></footer>`);
  panel.querySelectorAll<HTMLElement>('[data-layout]').forEach((b) => b.addEventListener('click', () => {
    o.layout = b.dataset.layout as UiOptions['layout'];
    panel.querySelectorAll('[data-layout]').forEach((x) => x.classList.toggle('on', x === b));
  }));
  panel.querySelector('[data-opt=tiles]')!.addEventListener('click', (e) => {
    o.tiles = o.tiles === 'original' ? 'glass' : 'original';
    (e.currentTarget as HTMLElement).querySelector('.value')!.textContent = tilesLabel();
  });
  panel.querySelector('[data-act=go]')!.addEventListener('click', () => run(panel, t.glassUi, (s) => api.installUi(o, s)));
}

function restorePanel() {
  const line = (s: string) => `<div class="step">${icon.chevron(16)}${s}</div>`;
  const panel = showPanel('restore', `
    <header><h2>${t.restore}</h2><p>${t.restoreSub}</p></header>
    <div class="body">
      ${line(t.restoreHome)}${line(t.restorePackages)}${line(t.restoreSettings)}${line(t.restoreFilter)}
      <p class="note" style="margin-top:14px">${t.restoreNote}</p>
    </div>
    <footer><button class="quiet" data-act="close">${t.cancel}</button><button class="go" data-act="go">${t.restore}</button></footer>`);
  panel.querySelector('[data-act=go]')!.addEventListener('click', () => run(panel, t.restore, (s) => api.restore(s)));
}

const stepIcon: Record<Step['state'], () => string> = {
  pending: () => '<span class="ic"></span>',
  running: () => icon.spinner(18),
  done: () => icon.check(18),
  failed: () => icon.cross(18),
  skipped: () => icon.minus(18),
};

/** The panel turns into the steps of the running task. */
async function run(panel: HTMLElement, title: string, task: (onStep: (s: Step) => void) => Promise<boolean>) {
  view = () => {};
  const steps = new Map<string, Step>();
  panel.innerHTML = `
    <header><h2>${esc(title)}</h2><p>${t.keepOn}</p></header>
    <div class="body"><div id="steps"></div><div id="summary"></div></div>
    <footer><button class="go" data-act="done" disabled>${t.done}</button></footer>`;
  const list = panel.querySelector('#steps')!;
  const draw = () => {
    list.innerHTML = [...steps.values()].map((s) =>
      `<div class="step ${s.state}">${stepIcon[s.state]()}${esc(tr(s.label))}${s.detail ? `<span class="detail">${esc(tr(s.detail))}</span>` : ''}</div>`).join('');
  };
  const summary = panel.querySelector('#summary')!;
  try {
    const ok = await task((s) => { steps.set(s.id, s); draw(); });
    summary.outerHTML = ok ? `<p class="summary">${t.allGood}</p>` : `<p class="summary bad">${t.someFailed}</p>`;
  } catch (e) {
    summary.outerHTML = `<p class="summary bad">${esc(tr(String(e)))}</p>`;
  }
  panel.querySelector<HTMLButtonElement>('[data-act=done]')!.disabled = false;
}

// ---------------------------------------------------------------- events

root.addEventListener('click', (e) => {
  const el = (e.target as HTMLElement).closest<HTMLElement>('[data-act], [data-ip], [data-lang], [data-win], [data-gh]');
  const menu = root.querySelector('.lang');
  if (el?.dataset.act !== 'lang-menu') menu?.classList.remove('open');
  if (!el) return;
  if (el.dataset.win) { windowAction(el.dataset.win); return; }
  if (el.dataset.gh) { openGithub(el.dataset.gh); return; }
  if (el.dataset.ip) { connect(el.dataset.ip); return; }
  if (el.dataset.lang) {
    if (el.dataset.lang !== lang) { setLang(el.dataset.lang as Lang); view(); }
    return;
  }
  switch (el.dataset.act) {
    case 'lang-menu': menu?.classList.toggle('open'); break;
    case 'intro': introView(); break;
    case 'intro-done': try { localStorage.setItem(INTRO, '1'); } catch { /* storage may be off */ } connectView(); break;
    case 'connect': connect((document.getElementById('ip') as HTMLInputElement).value); break;
    case 'scan': scan(); break;
    case 'disconnect': api.disconnect().then(() => connectView()); break;
    case 'debloat': debloatPanel(); break;
    case 'ui': uiPanel(); break;
    case 'restore': restorePanel(); break;
    case 'close': closePanel(); break;
    case 'done': closePanel(); setTimeout(refresh, 320); break;
  }
});
document.addEventListener('keydown', (e) => {
  if (e.key !== 'Escape') return;
  root.querySelector('.lang')?.classList.remove('open');
  if (open && !root.querySelector('.panel [data-act=done]')) closePanel();
});
document.addEventListener('click', (e) => { if (!root.contains(e.target as Node)) root.querySelector('.lang')?.classList.remove('open'); });

// ---------------------------------------------------------------- right-click menu

// The webview's own menu (back, print, save as …) is replaced by two entries: the web
// inspector and a reload. It lives outside #app, so redrawing a screen never takes it away.
// Text fields keep their own menu for copy and paste.
const ctx = document.createElement('div');
ctx.className = 'menu ctx';
document.body.append(ctx);
const hideCtx = () => ctx.classList.remove('show');

document.addEventListener('contextmenu', (e) => {
  if ((e.target as Element).closest('input')) return;
  e.preventDefault();
  ctx.innerHTML = `
    <button class="item" data-ctx="devtools">${icon.code(16)}<span>${t.devtools}</span></button>
    <button class="item" data-ctx="reload">${icon.reload(16)}<span>${t.reload}</span></button>`;
  ctx.classList.add('show');
  const r = ctx.getBoundingClientRect();
  ctx.style.left = `${Math.min(e.clientX, innerWidth - r.width - 8)}px`;
  ctx.style.top = `${Math.min(e.clientY, innerHeight - r.height - 8)}px`;
});
ctx.addEventListener('click', (e) => {
  const b = (e.target as Element).closest<HTMLElement>('[data-ctx]');
  hideCtx();
  if (b?.dataset.ctx === 'devtools') openDevtools();
  if (b?.dataset.ctx === 'reload') location.reload();
});
document.addEventListener('mousedown', (e) => { if (!ctx.contains(e.target as Node)) hideCtx(); });
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') hideCtx(); });
window.addEventListener('blur', hideCtx);

splash(() => (introSeen() ? connectView() : introView()));
