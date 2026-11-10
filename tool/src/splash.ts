// The start screen: the app's rope is drawn in one stroke, as if swung, then the name comes in
// and the whole screen fades into the app. A click skips it; reduced motion shows the app at once.

const ROPE = 'M 27 40 C 21 58 30 79 50 80 C 62 81 70 70 67 59 C 64 48 52 48 47 56 C 43 63 50 68 57 63 C 64 58 72 48 84 44';

const DRAW_MS = 1300;
const HOLD_MS = 650;
const OUT_MS = 450;

/** Shows the start screen; `ready` draws the first screen just as the start screen fades out. */
export function splash(ready: () => void) {
  if (matchMedia('(prefers-reduced-motion: reduce)').matches) {
    ready();
    return;
  }
  const el = document.createElement('div');
  el.className = 'splash';
  el.innerHTML = `
    <div class="sky"></div>
    <div class="splash-mark">
      <svg width="132" height="110" viewBox="20 30 70 58" aria-hidden="true">
        <defs><linearGradient id="splashRope" x1="0.1" y1="0.1" x2="0.9" y2="0.95">
          <stop offset="0" stop-color="#6fc9b6"/><stop offset=".35" stop-color="#4aa3bb"/><stop offset=".7" stop-color="#6b84dc"/><stop offset="1" stop-color="#9488e8"/>
        </linearGradient></defs>
        <path class="rope" d="${ROPE}" pathLength="1" fill="none" stroke="url(#splashRope)" stroke-width="6.4" stroke-linecap="round" stroke-linejoin="round"/>
      </svg>
      <div class="splash-word">Glass Home</div>
    </div>`;
  document.body.append(el);

  let done = false;
  const finish = () => {
    if (done) return;
    done = true;
    ready();
    el.classList.add('out');
    setTimeout(() => el.remove(), OUT_MS + 50);
  };
  const timer = setTimeout(finish, DRAW_MS + HOLD_MS);
  el.addEventListener('click', () => { clearTimeout(timer); finish(); });
}
