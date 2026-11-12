import { api, THEMES, type App, type DebloatOptions, type Plan, type PlanItem, type Device, type Found, type DeviceState, type Step, type UiOptions, type Theme } from './api';
import { flag, icon, mark } from './icons';
import { lang, setLang, t, tr, type Lang } from './i18n';
import { AUTHORS, REPO, isPhone, openDevtools, openGithub, windowAction, windowButtons } from './titlebar';
import { splash } from './splash';

// The tool in the look of the app icon: its colours as the sky, frosted white glass on top.
// Without a TV: one centred column to connect. With a TV: a sidebar (the TV, the pages, the
// credits) and one page at a time; a page that changes the TV has its action bottom right.
// On a phone the sidebar goes away: Overview lists the pages, the others have a back arrow.

const root = document.getElementById('app')!;
let device: Device | null = null;
let state: DeviceState | null = null;

type Page = 'overview' | 'debloat' | 'ui' | 'restore';
let page: Page = 'overview';
/** A task runs: the other pages and Disconnect wait for it. */
let busy = false;
/** Redraws the current screen, after a language switch. */
let view: () => void = () => {};

const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]!));
const oneLine = (s: string) => esc(s).replace(/\n/g, ' ');
const LANGS: Record<Lang, string> = { en: 'English', de: 'Deutsch' };

// ---------------------------------------------------------------- frame

function langMenu(): string {
  return `<div class="lang"><button class="bar-btn" data-act="lang-menu">${isPhone ? flag(lang) : t.langName}${icon.chevronDown(14)}</button>
    <div class="menu">${(['en', 'de'] as Lang[]).map((l) =>
      `<button class="item" data-lang="${l}">${flag(l)}<span>${LANGS[l]}</span>${l === lang ? icon.check(16) : ''}</button>`).join('')}</div></div>`;
}

/** Left end of the top bar: the name, or on a phone's inner pages the way back. */
function topLeft(): string {
  if (isPhone && device && page !== 'overview' && !busy) {
    return `<button class="back" data-nav="overview">${icon.back(20)}${t.overview}</button>`;
  }
  return `<div class="brand">${mark(20)}<span>Glass Home</span></div>`;
}

function credits(): string {
  return `<div class="credits">${icon.github(14)}<button data-gh="${REPO}">${REPO}</button>${AUTHORS.map((a) => `<span>·</span><button data-gh="${a}">${a}</button>`).join('')}</div>`;
}

function deviceCard(): string {
  const d = device!;
  return `<div class="device">${icon.tv(20)}<div class="who"><div class="n">${esc(d.name)}</div><div class="m">${esc(tr(d.fireOs))} · ${esc(d.ip)}</div></div>
    <button class="leave" data-act="disconnect" title="${t.disconnect}" aria-label="${t.disconnect}" ${busy ? 'disabled' : ''}>${icon.leave(18)}</button></div>`;
}

function sidebar(): string {
  const s = state!;
  const changed = s.debloated || s.glassUi;
  const item = (p: Page, ic: string, name: string, value: string, off = false) =>
    `<button class="nav-item ${p === page ? 'on' : ''}" data-nav="${p}" ${(busy && p !== page) || off ? 'disabled' : ''}>${ic}<span>${name}</span><span class="s">${esc(value)}</span></button>`;
  return `<nav class="side">${deviceCard()}
    <div class="nav">
      ${item('overview', icon.overview(18), t.overview, '')}
      ${item('debloat', icon.filter(18), t.debloat, s.debloated ? t.on : '')}
      ${item('ui', icon.screen(18), t.glassUi, s.glassUi ? s.glassVersion ?? '' : '')}
      ${item('restore', icon.undo(18), t.restore, '', !changed)}
    </div>
    <div class="grow"></div>${credits()}</nav>`;
}

/** Draws a screen: top bar, sidebar when a TV is connected, the page, and its action bar. */
function frame(main: string, actions = '') {
  const wide = !device;
  root.innerHTML = `
    <div class="sky"></div>
    <div class="top">${topLeft()}<div class="spacer"></div>${langMenu()}${windowButtons()}</div>
    ${wide ? '' : sidebar()}
    <main class="main ${wide ? 'wide' : ''} ${actions ? 'has-actions' : ''}"><div class="page">${main}${wide ? credits() : ''}</div></main>
    ${actions ? `<div class="actions ${wide ? 'wide' : ''}">${actions}</div>` : ''}`;
}

// ---------------------------------------------------------------- connect

let found: Found[] | null = null;
let searching = false;
let searchError = '';
let notice = '';

function connectView(error = '', search = true) {
  view = () => connectView('', false);
  device = null;
  state = null;
  page = 'overview';
  const step = (n: number, name: string, text: string) => `<div><b><em>${n}</em>${name}</b>${text}</div>`;
  frame(`
    <h1>${oneLine(t.connectTitle)}</h1>
    <p class="sub">${t.findSub}</p>
    <div class="card table" id="found">${foundTable()}</div>
    <div class="under">
      <input class="field" id="ip" placeholder="192.168.x.x" spellcheck="false" autocomplete="off" inputmode="decimal" />
      <button class="btn" data-act="connect">${t.connectAt}</button>
      <span class="spacer"></span>
      <button class="link" data-act="scan" ${searching ? 'disabled' : ''}>${icon.reload(15)}${t.scan}</button>
    </div>
    <p class="error" id="err">${esc(error)}</p>
    <div class="label">${t.onTvOnce}</div>
    <div class="card howto">
      ${step(1, t.step1, t.step1Text)}${step(2, t.step2, t.step2Text)}${step(3, t.step3, isPhone ? t.step3TextPhone : t.step3Text)}
    </div>`);
  const ip = document.getElementById('ip') as HTMLInputElement;
  ip.addEventListener('keydown', (e) => { if (e.key === 'Enter') connect(ip.value); });
  if (search && !searching) scan();
  showNotice();
}

/** Found devices as a table: name, model, address, and what can be done with each. */
function foundTable(): string {
  const head = `<div class="thead"><span>${t.colName}</span><span>${t.colModel}</span><span>${t.colAddress}</span><span></span></div>`;
  const note = (text: string) => `${head}<div class="trow empty"><span>${text}</span></div>`;
  if (searching && !found?.length) return note(t.scanning);
  if (searchError) return note(esc(tr(searchError)));
  if (!found) return note(t.scanning);
  if (!found.length) return note(t.scanNone);
  const row = (d: Found) => {
    const model = d.model || (d.kind === 'fire' ? t.fireTv : d.kind === 'google' ? 'Google TV' : d.vendor || '');
    const cells = `<span class="nm">${icon.tv(18)}<span>${esc(d.name || d.ip)}</span></span><span class="c2">${esc(model)}</span><span class="c3">${esc(d.ip)}</span>`;
    if (d.kind === 'google') return `<div class="trow dim">${cells}<span class="c4">${t.unsupported}</span></div>`;
    if (!d.adb) return `<div class="trow">${cells}<span class="c4">${t.adbIsOff}</span></div>`;
    return `<div class="trow">${cells}<span class="c4"><button class="btn primary small" data-ip="${esc(d.ip)}">${t.connect}</button></span></div>`;
  };
  return head + found.map(row).join('');
}

async function scan() {
  searching = true;
  searchError = '';
  const redraw = () => {
    const el = document.getElementById('found');
    if (!el) return; // the screen changed meanwhile
    el.innerHTML = foundTable();
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

async function connect(ip: string) {
  ip = ip.trim();
  const err = document.getElementById('err')!;
  if (!/^\d{1,3}(\.\d{1,3}){3}$/.test(ip)) { err.textContent = t.badIp; err.classList.remove('calm'); return; }
  root.querySelectorAll<HTMLButtonElement>('[data-ip], [data-act=connect]').forEach((b) => { b.disabled = true; });
  const go = root.querySelector<HTMLButtonElement>(`[data-ip="${ip}"]`) ?? root.querySelector<HTMLButtonElement>('[data-act=connect]');
  if (go) go.textContent = t.connecting;
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

// ---------------------------------------------------------------- pages

async function refresh() {
  state = await api.state();
  showPage(page);
}

function showPage(p: Page) {
  if (!device || !state) return;
  page = p;
  view = () => showPage(page);
  if (p === 'debloat') debloatPage();
  else if (p === 'ui') uiPage();
  else if (p === 'restore') restorePage();
  else overviewPage();
  root.querySelector('.main')?.scrollTo(0, 0);
}

const chevron = icon.chevron(16);

function overviewPage() {
  const s = state!;
  const changed = s.debloated || s.glassUi;
  const row = (to: Page, name: string, desc: string, value: string, off = false) =>
    `<button class="row go-row" data-nav="${to}" ${off ? 'disabled' : ''}><span class="t"><span class="n">${name}</span><span class="d">${desc}</span></span><span class="v">${esc(value)}${off ? '' : chevron}</span></button>`;
  frame(`
    ${isPhone ? deviceCard() : ''}
    <h1>${esc(device!.name)}</h1>
    <p class="sub">${t.foot}</p>
    <div class="label">${t.onThisTv}</div>
    <div class="card">
      ${row('debloat', t.debloat, s.debloated ? t.debloatDescOn(s.disabled) : t.debloatDescOff, s.debloated ? t.on : t.notApplied)}
      ${row('debloat', t.dnsFilter, s.dnsFilter ? t.filterDescOn : t.filterDescOff, s.dnsFilter ? t.active : t.off)}
      ${row('ui', t.glassUi, s.glassUi ? t.uiDescOn : t.uiDescOff, s.glassUi ? t.installed(s.glassVersion ?? '') : t.notInstalled)}
      ${row('restore', t.restore, t.restoreDesc, changed ? '' : t.nothingToDo, !changed)}
    </div>
    <div class="label">${t.connection}</div>
    <div class="card">
      <div class="row"><span class="t"><span class="n">${t.adbDebugging}</span><span class="d">${t.adbRowDesc}</span></span>
        <button class="btn rose" data-act="adb-off">${t.turnOff}</button></div>
    </div>
    ${isPhone ? credits() : ''}`);
}

/** A row with a switch; the whole row toggles it. */
const switchRow = (key: string, name: string, desc: string, on: boolean, extra = '') =>
  `<button class="row" data-opt="${key}" role="switch" aria-checked="${on}"><span class="t"><span class="n">${name}</span>${desc ? `<span class="d">${desc}</span>` : ''}</span>${extra}<span class="sw ${on ? 'on' : ''}"></span></button>`;

function toggleSwitch(row: HTMLElement, on: boolean) {
  row.setAttribute('aria-checked', String(on));
  row.querySelector('.sw')!.classList.toggle('on', on);
}

/** The page's choices survive a language switch; they start fresh with each connection. */
let debloatOpts: DebloatOptions | null = null;
let appList: App[] | null = null;
let plan: Plan | null = null;
let planOpen = false;

/** Whether a package of the plan goes: checked, and not held back by Alexa or updates. */
function planState(o: DebloatOptions, it: PlanItem, alexa: boolean): 'go' | 'skip' | 'held' {
  if (alexa && o.keepAlexa) return 'held';
  if (!alexa && o.keepUpdates && plan!.ota.includes(it.pkg)) return 'held';
  return o.skip.includes(it.pkg) ? 'skip' : 'go';
}

/** The accordion: every Amazon package Debloat would turn off, checked by default. */
function planCard(o: DebloatOptions): string {
  const head = (count: string) => `<button class="row acc-head" data-act="acc" aria-expanded="${planOpen}">
      <span class="t"><span class="n">${t.amazonPackages}</span><span class="d">${t.planHint}</span></span>
      <span class="v">${count}</span><span class="acc-chev">${icon.chevronDown(16)}</span></button>`;
  if (!plan) return head(t.readingPlan);
  const all = [...plan.groups.flatMap((g) => g.items.map((it) => [it, false] as const)), ...plan.alexa.map((it) => [it, true] as const)];
  const counted = all.filter(([it, a]) => planState(o, it, a) !== 'held');
  const chosen = counted.filter(([it, a]) => planState(o, it, a) === 'go').length;
  const item = (it: PlanItem, alexa: boolean) => {
    const st = planState(o, it, alexa);
    const note = st === 'held' ? (alexa ? t.alexaStays : t.keptForUpdates) : it.off ? t.alreadyOff : '';
    return `<button class="row check" data-pkg="${esc(it.pkg)}" data-alexa="${alexa}" role="checkbox" aria-checked="${st === 'go'}" ${st === 'held' ? 'disabled' : ''}>
      <span class="cb ${st === 'go' ? 'on' : ''}">${icon.check(14)}</span>
      <span class="t"><span class="n">${esc(it.desc || it.pkg)}</span><span class="d mono">${esc(it.pkg)}</span></span>
      <span class="v">${note}</span></button>`;
  };
  const group = (title: string, items: string) => `<div class="sub-head">${esc(title)}</div>${items}`;
  const body = [
    ...plan.groups.map((g) => group(t.groupName[g.key] ?? g.key, g.items.map((it) => item(it, false)).join(''))),
    plan.alexa.length ? group(t.alexa, plan.alexa.map((it) => item(it, true)).join('')) : '',
    plan.protected.length ? group(t.throttled, `<div class="row"><span class="t"><span class="d">${t.throttledHint}</span></span></div>` +
      plan.protected.map((it) => `<div class="row check ro"><span class="cb ro">${icon.minus(14)}</span><span class="t"><span class="n">${esc(it.desc || it.pkg)}</span><span class="d mono">${esc(it.pkg)}</span></span></div>`).join('')) : '',
  ].join('');
  return head(t.selectedOf(chosen, counted.length)) + `<div class="acc-body" ${planOpen ? '' : 'hidden'}>${body}</div>`;
}

function bindPlan(o: DebloatOptions) {
  const box = document.getElementById('plan');
  if (!box) return;
  box.innerHTML = planCard(o);
  box.querySelector('[data-act=acc]')!.addEventListener('click', () => {
    planOpen = !planOpen;
    bindPlan(o);
  });
  box.querySelectorAll<HTMLElement>('[data-pkg]').forEach((r) => r.addEventListener('click', () => {
    const pkg = r.dataset.pkg!;
    o.skip = o.skip.includes(pkg) ? o.skip.filter((p) => p !== pkg) : [...o.skip, pkg];
    bindPlan(o);
  }));
}

async function debloatPage() {
  const o = (debloatOpts ??= { keepPrime: true, keepAlexa: true, keepUpdates: true, dnsFilter: true, remove: [], skip: [] });
  frame(`
    <h1>${t.debloat}</h1>
    <p class="sub">${t.debloatSub}</p>
    <div class="label">${t.whatGoes}</div>
    <div class="card acc" id="plan"></div>
    <div class="label">${t.whatStays}</div>
    <div class="card">
      ${switchRow('keepPrime', t.primeVideo, '', o.keepPrime)}
      ${switchRow('keepAlexa', t.alexa, '', o.keepAlexa)}
      ${switchRow('keepUpdates', t.updates, '', o.keepUpdates)}
      ${switchRow('dnsFilter', t.dnsFilter, t.filterDescOn, o.dnsFilter)}
    </div>
    <div class="label">${t.yourApps}</div>
    <div class="card" id="apps">${appRows(o)}</div>
    <p class="note">${t.removeNote}</p>`,
    `<span class="note">${t.debloatNote}</span><button class="btn primary" data-act="go">${t.start}</button>`);
  root.querySelectorAll<HTMLElement>('[data-opt]').forEach((r) => r.addEventListener('click', () => {
    const k = r.dataset.opt as 'keepPrime' | 'keepAlexa' | 'keepUpdates' | 'dnsFilter';
    o[k] = !o[k];
    toggleSwitch(r, o[k]);
    // Alexa and updates decide whether their packages can go at all.
    if (k === 'keepAlexa' || k === 'keepUpdates') bindPlan(o);
  }));
  root.querySelector('[data-act=go]')!.addEventListener('click', () => run(t.debloat, (s) => api.debloat(o, s)));
  bindPlan(o);
  if (!plan) {
    plan = await api.plan().catch(() => null);
    if (page === 'debloat') bindPlan(o);
  }
  if (!appList) {
    appList = await api.apps().catch(() => []);
    const box = document.getElementById('apps');
    if (box && page === 'debloat') box.innerHTML = appRows(o);
  }
  bindApps(o);
}

function appRows(o: DebloatOptions): string {
  if (!appList) return `<div class="row"><span class="t"><span class="d">${t.readingApps}</span></span></div>`;
  if (!appList.length) return `<div class="row"><span class="t"><span class="d">${t.noApps}</span></span></div>`;
  return appList.map((a) => {
    const gone = o.remove.includes(a.pkg);
    return `<button class="row app" data-app="${esc(a.pkg)}" role="switch" aria-checked="${gone}">
      <span class="t"><span class="n">${esc(a.label)}</span>${a.label !== a.pkg ? `<span class="d mono">${esc(a.pkg)}</span>` : ''}</span>
      <span class="v ${gone ? 'rose' : ''}">${gone ? t.removeApp : t.stays}</span><span class="sw rose ${gone ? 'on' : ''}"></span></button>`;
  }).join('');
}

function bindApps(o: DebloatOptions) {
  root.querySelectorAll<HTMLElement>('[data-app]').forEach((r) => r.addEventListener('click', () => {
    const pkg = r.dataset.app!;
    const gone = !o.remove.includes(pkg);
    o.remove = gone ? [...o.remove, pkg] : o.remove.filter((p) => p !== pkg);
    toggleSwitch(r, gone);
    const v = r.querySelector('.v')!;
    v.textContent = gone ? t.removeApp : t.stays;
    v.classList.toggle('rose', gone);
  }));
}

/**
 * A picked photo as the TV wants it: cropped to 16:9 from the middle (like the launcher's own
 * photos), scaled to 1920 x 1080, JPEG; plus a small preview for the page.
 */
async function tvPhoto(f: File): Promise<{ jpeg: Uint8Array; thumb: string }> {
  const bmp = await createImageBitmap(f);
  const draw = (w: number, h: number) => {
    const c = document.createElement('canvas');
    c.width = w;
    c.height = h;
    const s = Math.max(w / bmp.width, h / bmp.height), sw = w / s, sh = h / s;
    c.getContext('2d')!.drawImage(bmp, (bmp.width - sw) / 2, (bmp.height - sh) / 2, sw, sh, 0, 0, w, h);
    return c;
  };
  const big = draw(1920, 1080);
  const blob = await new Promise<Blob>((ok, no) => big.toBlob((b) => (b ? ok(b) : no(new Error('encode'))), 'image/jpeg', .9));
  const thumb = draw(240, 135).toDataURL('image/jpeg', .85);
  bmp.close();
  return { jpeg: new Uint8Array(await blob.arrayBuffer()), thumb };
}

let uiOpts: UiOptions | null = null;
let photoThumb = '';

function uiPage() {
  const s = state!;
  const o = (uiOpts ??= { layout: s.layout ?? 'dock', tiles: s.tiles ?? 'original', theme: s.theme ?? 'neutral', arrows: s.arrows ?? false });
  const thumbs: Record<UiOptions['layout'], string> = {
    dock: '<i class="g" style="left:13%;right:13%;bottom:12%;height:24%"></i><i style="left:19%;bottom:17%;width:15%;height:14%"></i><i style="left:37%;bottom:17%;width:15%;height:14%"></i><i style="left:55%;bottom:17%;width:15%;height:14%"></i>',
    list: '<i class="g" style="left:6%;top:13%;width:58%;height:18%"></i><i style="left:9%;top:17%;width:9%;height:10%"></i><i style="left:9%;top:39%;width:9%;height:10%;opacity:.6"></i><i style="left:9%;top:61%;width:9%;height:10%;opacity:.6"></i>',
    icons: '<i style="left:8%;top:13%;width:15%;height:15%"></i><i style="left:8%;top:36%;width:15%;height:15%;opacity:.6"></i><i style="left:8%;top:59%;width:15%;height:15%;opacity:.6"></i><i style="left:47%;top:33%;width:32%;height:10%"></i>',
  };
  const tile = (k: UiOptions['tiles'], name: string) => `<button data-tiles="${k}" class="${o.tiles === k ? 'on' : ''}">${name}</button>`;
  frame(`
    <h1>${t.glassUi}</h1>
    <p class="sub">${t.uiSub}</p>
    <div class="label">${t.layout}</div>
    <div class="layouts">${(Object.keys(thumbs) as UiOptions['layout'][]).map((k) =>
      `<button class="lay ${k === o.layout ? 'on' : ''}" data-layout="${k}"><span class="th">${thumbs[k]}</span><span class="nm">${t[k]}</span></button>`).join('')}</div>
    <div class="label">${t.appearance}</div>
    <div class="card">
      <div class="row wrap"><span class="t"><span class="n">${t.colours}</span><span class="d">${t.coloursShort}</span></span>
        <span class="chips">${THEMES.map((k) =>
          `<button class="chip ${k === o.theme ? 'on' : ''}" data-theme="${k}"><span class="c sw-${k}"><i class="g"></i><i class="f"></i></span>${t.themeName[k]}</button>`).join('')}</span></div>
      <div class="row wrap"><span class="t"><span class="n">${t.wallpaper}</span><span class="d" id="photoStatus">${t.wallpaperDesc}</span></span>
        <button class="photo" data-act="pick-photo" aria-label="${t.choose}">${photoThumb ? `<img src="${photoThumb}" alt="">` : icon.image(20)}</button>
        <button class="btn" data-act="pick-photo">${icon.upload(15)}${t.choose}</button>
        <input type="file" id="photoFile" accept="image/jpeg,image/png,image/webp" hidden></div>
      <div class="row wrap"><span class="t"><span class="n">${t.tiles}</span><span class="d">${t.tilesDesc}</span></span>
        <span class="seg">${tile('original', t.originalBanners)}${tile('glass', t.glassSymbols)}</span></div>
      ${switchRow('arrows', t.dockArrows, t.arrowsDesc, o.arrows)}
    </div>`,
    `<span class="note">${s.glassUi ? t.uiNoteUpdate : t.uiNoteInstall}</span><button class="btn primary" data-act="go">${s.glassUi ? t.update : t.install}</button>`);

  const pick = (sel: string, attr: string, set: (v: string) => void) =>
    root.querySelectorAll<HTMLElement>(sel).forEach((b) => b.addEventListener('click', () => {
      set(b.getAttribute(attr)!);
      root.querySelectorAll(sel).forEach((x) => x.classList.toggle('on', x === b));
    }));
  pick('[data-layout]', 'data-layout', (v) => { o.layout = v as UiOptions['layout']; });
  pick('[data-theme]', 'data-theme', (v) => { o.theme = v as Theme; });
  pick('[data-tiles]', 'data-tiles', (v) => { o.tiles = v as UiOptions['tiles']; });
  root.querySelector<HTMLElement>('[data-opt=arrows]')!.addEventListener('click', (e) => {
    o.arrows = !o.arrows;
    toggleSwitch(e.currentTarget as HTMLElement, o.arrows);
  });

  const file = root.querySelector<HTMLInputElement>('#photoFile')!;
  root.querySelectorAll('[data-act=pick-photo]').forEach((b) => b.addEventListener('click', () => file.click()));
  file.addEventListener('change', async () => {
    const f = file.files?.[0];
    file.value = '';
    if (!f) return;
    const status = root.querySelector('#photoStatus')!;
    status.classList.remove('bad');
    let photo: { jpeg: Uint8Array; thumb: string };
    try {
      photo = await tvPhoto(f);
    } catch {
      status.textContent = t.photoUnreadable;
      status.classList.add('bad');
      return;
    }
    photoThumb = photo.thumb;
    root.querySelector('.photo')!.innerHTML = `<img src="${photo.thumb}" alt="">`;
    // Auto takes its colour from this photo; Lavender would hide it behind its colour field.
    o.theme = 'auto';
    root.querySelectorAll<HTMLElement>('[data-theme]').forEach((x) => x.classList.toggle('on', x.dataset.theme === 'auto'));
    status.textContent = t.sendingPhoto;
    try {
      await api.setWallpaper(photo.jpeg, o.theme);
      status.textContent = t.photoSent;
    } catch (e) {
      status.textContent = tr(String(e));
      status.classList.add('bad');
    }
  });
  root.querySelector('[data-act=go]')!.addEventListener('click', () => run(t.glassUi, (st) => api.installUi(o, st)));
}

function restorePage() {
  const line = (s: string) => `<div class="row"><span class="t"><span class="n plain">${s}</span></span></div>`;
  frame(`
    <h1>${t.restore}</h1>
    <p class="sub">${t.restoreSub}</p>
    <div class="label">${t.willHappen}</div>
    <div class="card">${line(t.restoreHome)}${line(t.restorePackages)}${line(t.restoreSettings)}${line(t.restoreFilter)}</div>
    <p class="note">${t.restoreNote}</p>`,
    `<span class="note">${t.debloatNote}</span><button class="btn primary" data-act="go">${t.restore}</button>`);
  root.querySelector('[data-act=go]')!.addEventListener('click', () => run(t.restore, (s) => api.restore(s)));
}

// ---------------------------------------------------------------- a running task

const stepIcon: Record<Step['state'], () => string> = {
  pending: () => '<span class="ic"></span>',
  running: () => icon.arrow(17),
  done: () => icon.check(17),
  failed: () => icon.cross(17),
  skipped: () => icon.minus(17),
};

/** The page turns into the steps of the task; the other pages wait until it is done. */
async function run(title: string, task: (onStep: (s: Step) => void) => Promise<boolean>) {
  busy = true;
  view = () => {};
  frame(`
    <h1>${esc(title)}</h1>
    <p class="sub">${isPhone ? t.keepOpen : t.keepOn}</p>
    <div class="label">${t.steps}</div>
    <div class="card" id="steps"></div>
    <div class="progress"><span class="track"><i id="bar"></i></span><span id="stepOf"></span></div>
    <p class="summary" id="summary"></p>`,
    `<span class="note">${t.runNote}</span><button class="btn primary" data-act="done" disabled>${t.done}</button>`);
  const steps = new Map<string, Step>();
  const list = root.querySelector('#steps')!;
  const draw = () => {
    const all = [...steps.values()];
    list.innerHTML = all.map((s) =>
      `<div class="step ${s.state}">${stepIcon[s.state]()}<span>${esc(tr(s.label))}</span>${s.detail ? `<span class="v">${esc(tr(s.detail))}</span>` : ''}</div>`).join('');
    const finished = all.filter((s) => s.state !== 'pending' && s.state !== 'running').length;
    const now = all.findIndex((s) => s.state === 'running');
    (root.querySelector('#bar') as HTMLElement).style.width = `${all.length ? (finished / all.length) * 100 : 0}%`;
    root.querySelector('#stepOf')!.textContent = all.length ? t.stepOf(now >= 0 ? now + 1 : finished, all.length) : '';
  };
  const summary = root.querySelector('#summary')!;
  try {
    const ok = await task((s) => { steps.set(s.id, s); draw(); });
    summary.textContent = ok ? t.allGood : t.someFailed;
    summary.classList.toggle('bad', !ok);
  } catch (e) {
    summary.textContent = tr(String(e));
    summary.classList.add('bad');
  }
  busy = false;
  appList = null;
  plan = null;
  // The sidebar unlocks; the steps stay on screen until Done.
  const side = root.querySelector('.side');
  if (side) side.outerHTML = sidebar();
  root.querySelector<HTMLButtonElement>('[data-act=done]')!.disabled = false;
}

// ---------------------------------------------------------------- the ADB dialog

/** Turning ADB off: what it gives, what keeps working, how to undo it. */
function adbOffDialog() {
  root.insertAdjacentHTML('beforeend', `
    <div class="scrim"><div class="dialog" role="dialog" aria-modal="true">
      <h2>${t.adbOffTitle}</h2>
      <p class="sub">${t.adbOffSub}</p>
      <div class="facts"><div>${icon.check(16)}${t.adbOffKeeps}</div><div class="rose">${icon.info(16)}${t.adbOffAfter}</div></div>
      <p class="note">${t.adbOffBack}</p>
      <p class="error" id="adbErr"></p>
      <div class="dialog-actions"><button class="btn" data-act="close">${t.cancel}</button><button class="btn danger" data-act="adb-go">${t.adbOffGo}</button></div>
    </div></div>`);
  const go = root.querySelector<HTMLButtonElement>('[data-act=adb-go]')!;
  go.addEventListener('click', async () => {
    go.disabled = true;
    go.textContent = t.adbOffBusy;
    try {
      await api.adbOff();
      closeDialog();
      connectView(t.adbOffDone, false);
      document.getElementById('err')?.classList.add('calm');
    } catch (e) {
      root.querySelector('#adbErr')!.textContent = tr(String(e));
      go.disabled = false;
      go.textContent = t.adbOffGo;
    }
  });
}

function closeDialog() {
  root.querySelector('.scrim')?.remove();
}

// ---------------------------------------------------------------- events

root.addEventListener('click', (e) => {
  const el = (e.target as HTMLElement).closest<HTMLElement>('[data-act], [data-ip], [data-lang], [data-win], [data-gh], [data-nav]');
  const menu = root.querySelector('.lang');
  if (el?.dataset.act !== 'lang-menu') menu?.classList.remove('open');
  if ((e.target as HTMLElement).classList.contains('scrim')) { closeDialog(); return; }
  if (!el || (el as HTMLButtonElement).disabled) return;
  if (el.dataset.win) { windowAction(el.dataset.win); return; }
  if (el.dataset.gh) { openGithub(el.dataset.gh); return; }
  if (el.dataset.ip) { connect(el.dataset.ip); return; }
  if (el.dataset.nav) { if (!busy) showPage(el.dataset.nav as Page); return; }
  if (el.dataset.lang) {
    if (el.dataset.lang !== lang) { setLang(el.dataset.lang as Lang); view(); }
    return;
  }
  switch (el.dataset.act) {
    case 'lang-menu': menu?.classList.toggle('open'); break;
    case 'connect': connect((document.getElementById('ip') as HTMLInputElement).value); break;
    case 'scan': scan(); break;
    case 'disconnect':
      debloatOpts = null; uiOpts = null; appList = null; plan = null; photoThumb = '';
      api.disconnect().then(() => connectView());
      break;
    case 'adb-off': adbOffDialog(); break;
    case 'close': closeDialog(); break;
    case 'done': page = 'overview'; refresh(); break;
  }
});
document.addEventListener('keydown', (e) => {
  if (e.key !== 'Escape') return;
  root.querySelector('.lang')?.classList.remove('open');
  closeDialog();
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

// ---------------------------------------------------------------- start

/** After a start: says which task the last run did not finish, on the connect screen. */
async function noteUnfinished() {
  const u = await api.unfinished().catch(() => null);
  if (!u) return;
  const task = u.task === 'debloat' ? t.debloat : u.task === 'ui' ? t.glassUi : t.restore;
  notice = t.unfinished(task, u.name || u.ip);
  showNotice();
}

function showNotice() {
  const err = document.getElementById('err');
  if (!notice || !err || err.textContent) return;
  err.textContent = notice;
  err.classList.add('calm');
  notice = '';
}

splash(() => {
  connectView();
  void noteUnfinished();
});
