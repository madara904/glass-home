// The window has no system frame on Windows: the top bar is the title bar. Empty parts of it
// drag the window (double-click maximizes), and three line icons on the right minimize,
// maximize and close. On macOS the native traffic lights stay; the bar only makes room for them.

import { getCurrentWindow } from '@tauri-apps/api/window';
import { invoke } from '@tauri-apps/api/core';
import { icon } from './icons';

const inTauri = '__TAURI_INTERNALS__' in window;
export const isMac = /Mac/i.test(navigator.platform || navigator.userAgent);

document.documentElement.classList.toggle('mac', isMac);

/** Window buttons for the right end of the top bar; none on macOS. */
export function windowButtons(): string {
  if (isMac) return '';
  return `<div class="winbtns">
    <button class="winbtn" data-win="min" aria-label="Minimize">${icon.winMin(16)}</button>
    <button class="winbtn" data-win="max" aria-label="Maximize">${icon.winMax(16)}</button>
    <button class="winbtn close" data-win="close" aria-label="Close">${icon.cross(16)}</button>
  </div>`;
}

export function windowAction(action: string) {
  if (!inTauri) return;
  const w = getCurrentWindow();
  if (action === 'min') void w.minimize();
  else if (action === 'max') void w.toggleMaximize();
  else if (action === 'close') void w.close();
}

export const AUTHORS = ['madara904', 'denniskribl'];
/** The repository, linked first in the byline so people who got the app elsewhere find the source. */
export const REPO = 'glass-home';
const PAGES: Record<string, string> = {
  [REPO]: 'https://github.com/madara904/glass-home',
  madara904: 'https://github.com/madara904',
  denniskribl: 'https://github.com/denniskribl',
};

export function openGithub(user: string) {
  const url = PAGES[user];
  if (!url) return;
  if (inTauri) void invoke('open_github', { user });
  else window.open(url, '_blank', 'noopener');
}

export function openDevtools() {
  if (inTauri) void invoke('open_devtools');
}

/** Pointer-downs on these never move the window. */
const NO_DRAG = 'button, a, input, [data-no-drag], .menu';

// Dragging: a press on an empty part of the top bar starts a native window move; a double
// click maximizes, as on a system title bar.
document.addEventListener('mousedown', (e) => {
  if (!inTauri || e.button !== 0) return;
  const target = e.target as Element;
  if (!target.closest('.top') || target.closest(NO_DRAG)) return;
  if (e.detail === 2) void getCurrentWindow().toggleMaximize();
  else void getCurrentWindow().startDragging();
});
