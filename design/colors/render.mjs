// Renders the Glass Home TV home screen (Icons layout) in a few colour themes, at 1920x1080,
// from the launcher's own wallpapers, font and marks. Usage: node colors.mjs <launcher dir> <out dir>
import puppeteer from 'puppeteer-core';
import fs from 'node:fs';
import path from 'node:path';

const [launcher, out] = process.argv.slice(2);
fs.mkdirSync(out, { recursive: true });
const b64 = (p) => fs.readFileSync(p).toString('base64');
const wall = (id) => `data:image/jpeg;base64,${b64(path.join(launcher, 'assets', `wall-${id}.jpg`))}`;
const font = `data:font/ttf;base64,${b64(path.join(launcher, 'assets', 'geist.ttf'))}`;

// Android vector drawable -> inline SVG in one colour.
function mark(id, color) {
  const xml = fs.readFileSync(path.join(launcher, 'res', 'drawable', `mk_${id}.xml`), 'utf8');
  const vw = xml.match(/viewportWidth="([\d.]+)"/)[1], vh = xml.match(/viewportHeight="([\d.]+)"/)[1];
  const tx = (xml.match(/translateX="([-\d.]+)"/) || [0, 0])[1], ty = (xml.match(/translateY="([-\d.]+)"/) || [0, 0])[1];
  const paths = [...xml.matchAll(/<path([^>]*)\/>/g)].map(([, a]) => {
    const get = (k) => (a.match(new RegExp('android:' + k + '="([^"]+)"')) || [])[1];
    const d = get('pathData'), stroke = get('strokeColor'), fill = get('fillColor');
    if (stroke && (!fill || fill === '#00000000')) {
      return `<path d="${d}" fill="none" stroke="${color}" stroke-width="${get('strokeWidth') || 1}" stroke-linecap="${get('strokeLineCap') || 'butt'}" stroke-linejoin="${get('strokeLineJoin') || 'miter'}"/>`;
    }
    return `<path d="${d}" fill="${color}"${get('fillAlpha') ? ` fill-opacity="${get('fillAlpha')}"` : ''}/>`;
  }).join('');
  return `<svg viewBox="0 0 ${vw} ${vh}"><g transform="translate(${tx} ${ty})">${paths}</g></svg>`;
}

const themes = [
  {
    file: 'colors-1-neutral', name: 'Neutral', note: 'today: clear glass, white focus',
    bg: `url(${wall('night')}) center/cover`, wallName: 'Desert stars',
    ink: '#f6f1e7', sub: 'rgba(246,241,231,.78)', icon: '#f7f4ee',
    glass: 'rgba(255,255,255,.16)', glassHi: 'rgba(255,255,255,.07)', edge: 'rgba(255,255,255,.28)',
    focus: 'linear-gradient(170deg, rgba(255,255,255,.46), rgba(255,255,255,.24) 58%, rgba(255,255,255,.34))',
    veil: 'linear-gradient(90deg, rgba(4,8,10,.32), rgba(4,8,10,0) 40%)',
  },
  {
    file: 'colors-2-mint', name: 'Mint', note: 'accent taken from the wallpaper, cool glass',
    bg: `url(${wall('misty')}) center/cover`, wallName: 'Misty coast', bright: true,
    ink: '#f2fbf8', sub: 'rgba(232,248,243,.80)', icon: '#effaf6',
    glass: 'rgba(170,236,220,.16)', glassHi: 'rgba(170,236,220,.06)', edge: 'rgba(214,255,244,.34)',
    focus: 'linear-gradient(170deg, rgba(150,232,212,.62), rgba(110,205,190,.38) 58%, rgba(140,225,205,.50))',
    veil: 'linear-gradient(90deg, rgba(4,20,22,.34), rgba(4,20,22,0) 42%)',
  },
  {
    file: 'colors-3-sand', name: 'Sand', note: 'warm glass, icons in the accent',
    bg: `url(${wall('golden')}) center/cover`, wallName: 'Golden hour', bright: true,
    ink: '#fff6ea', sub: 'rgba(255,242,224,.82)', icon: '#ffe2bd',
    glass: 'rgba(255,214,170,.17)', glassHi: 'rgba(255,214,170,.07)', edge: 'rgba(255,232,206,.36)',
    focus: 'linear-gradient(170deg, rgba(255,206,150,.62), rgba(240,170,110,.40) 58%, rgba(255,196,140,.52))',
    veil: 'linear-gradient(90deg, rgba(30,14,4,.36), rgba(30,14,4,0) 42%)',
  },
  {
    file: 'colors-4-lavender', name: 'Lavender', note: 'colour instead of a photo, light glass, dark text',
    bg: `radial-gradient(48% 62% at 12% 18%, rgba(169,159,240,.70), transparent 72%),
         radial-gradient(42% 55% at 46% 4%, rgba(127,151,230,.50), transparent 70%),
         radial-gradient(50% 60% at 92% 88%, rgba(143,216,200,.70), transparent 72%),
         radial-gradient(38% 48% at 70% 46%, rgba(95,182,201,.34), transparent 70%),
         linear-gradient(315deg, #dff4ee, #e7ecf8 50%, #e4defa)`, wallName: 'Glass gradient',
    ink: '#1b2233', sub: 'rgba(27,34,51,.66)', icon: '#2a3150',
    glass: 'rgba(255,255,255,.46)', glassHi: 'rgba(255,255,255,.26)', edge: 'rgba(255,255,255,.95)',
    focus: 'linear-gradient(170deg, rgba(255,255,255,.96), rgba(255,255,255,.80) 58%, rgba(255,255,255,.90))',
    veil: 'none', light: true,
  },
];

const apps = [['netflix', 'Netflix'], ['primevideo', 'Prime Video'], ['disneyplus', 'Disney+'], ['youtube', 'YouTube']];

function page(t) {
  const tile = (id, i) => {
    const on = i === 0;
    const y = 350 + i * 166 + (on ? 0 : 12);
    const w = on ? 272 : 248, h = on ? 152 : 138, x = on ? 138 : 150;
    return `<div class="tile ${on ? 'on' : ''}" style="left:${x}px;top:${y}px;width:${w}px;height:${h}px">
      <div class="mk">${mark(id, on && t.light ? '#1b2233' : t.icon)}</div></div>`;
  };
  const pill = (id, label) => `<div class="pill"><span class="pm">${mark(id, t.ink)}</span>${label}</div>`;
  return `<!doctype html><html><head><meta charset="utf-8"><style>
    @font-face { font-family: Geist; src: url(${font}); font-weight: 100 900; }
    * { margin: 0; box-sizing: border-box; }
    body { width: 1920px; height: 1080px; overflow: hidden; position: relative; font-family: Geist; color: ${t.ink}; background: ${t.bg}; }
    .veil { position: absolute; inset: 0; background: ${t.veil}; }
    .shade { position: absolute; left: 0; right: 0; top: 0; height: ${t.bright ? 360 : 340}px;
      background: linear-gradient(rgba(4,10,12,${t.bright ? .55 : .36}), rgba(4,10,12,${t.bright ? .26 : .16}) 50%, rgba(4,10,12,0)); }
    .dim { position: absolute; left: 380px; top: 280px; width: 1640px; height: 600px; border-radius: 50%;
      background: radial-gradient(closest-side, rgba(4,8,10,${t.bright ? .59 : .47}), rgba(4,8,10,0)); }
    .clock { position: absolute; left: 150px; top: 72px; font-size: 64px; font-weight: 500; letter-spacing: -1px; line-height: 1; }
    .date { position: absolute; left: 152px; top: 146px; font-size: 26px; color: ${t.sub}; }
    .pills { position: absolute; right: 150px; top: 78px; display: flex; gap: 18px; }
    .pill { height: 58px; padding: 0 26px 0 20px; border-radius: 29px; display: flex; align-items: center; gap: 12px; font-size: 25px; font-weight: 500;
      background: linear-gradient(170deg, ${t.glass}, ${t.glassHi} 58%, ${t.glass}); box-shadow: inset 0 1px 0 ${t.edge}; backdrop-filter: blur(24px) saturate(140%); }
    .pm svg { width: 24px; height: 24px; display: block; }
    .tile { position: absolute; border-radius: 20px; display: grid; place-items: center;
      background: linear-gradient(170deg, ${t.glass}, ${t.glassHi} 58%, ${t.glass}); box-shadow: inset 0 1px 0 ${t.edge}; backdrop-filter: blur(28px) saturate(150%); }
    .tile.on { background: ${t.focus}; box-shadow: inset 0 1px 0 ${t.edge}, 0 26px 50px -24px rgba(0,0,0,${t.light ? '.22' : '.55'}); }
    .mk svg { width: 64px; height: 64px; display: block; }
    .tile.on .mk svg { width: 70px; height: 70px; }
    .hero { position: absolute; left: 880px; top: 560px; }
    .hero h1 { font-size: 104px; font-weight: 500; letter-spacing: -3px; line-height: 1; ${t.light ? '' : 'text-shadow: 0 2px 18px rgba(4,6,8,.45);'} }
    .hero p { margin-top: 22px; font-size: 28px; color: ${t.sub}; }
    .wall { position: absolute; right: 150px; bottom: 60px; font-size: 20px; color: ${t.sub}; }
  </style></head><body><div class="veil"></div>${t.light ? '' : '<div class="shade"></div><div class="dim"></div>'}
    <div class="clock">18:10</div><div class="date">Tuesday, November 10</div>
    <div class="pills">${pill('apps', 'Apps')}${pill('art', 'Background')}${pill('settings', 'Settings')}</div>
    ${apps.map(([id], i) => tile(id, i)).join('')}
    <div class="hero"><h1>${apps[0][1]}</h1><p>OK opens · Menu: options</p></div>
    <div class="wall">${t.wallName}</div>
  </body></html>`;
}

const browser = await puppeteer.launch({ executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless: true });
const p = await browser.newPage();
await p.setViewport({ width: 1920, height: 1080, deviceScaleFactor: 1 });
for (const t of themes) {
  await p.setContent(page(t), { waitUntil: 'load' });
  await p.evaluate(() => document.fonts.ready);
  await p.screenshot({ path: path.join(out, t.file + '.png') });
  console.log('saved', t.file, '-', t.name + ':', t.note);
}
await browser.close();
