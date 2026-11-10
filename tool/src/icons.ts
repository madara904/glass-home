// Line icons, drawn on a 24 grid with round caps; they take the text colour.

const svg = (body: string, size = 20) =>
  `<svg class="ic" width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${body}</svg>`;

export const icon = {
  tv: (s?: number) => svg('<rect x="3" y="5" width="18" height="12" rx="3"/><path d="M9 20.5h6"/>', s),
  chevron: (s?: number) => svg('<path d="M10 7l5 5-5 5"/>', s),
  chevronDown: (s?: number) => svg('<path d="M7 10l5 5 5-5"/>', s),
  check: (s?: number) => svg('<path d="M5.5 12.5l4 4 9-9"/>', s),
  cross: (s?: number) => svg('<path d="M7 7l10 10M17 7L7 17"/>', s),
  minus: (s?: number) => svg('<path d="M7 12h10"/>', s),
  code: (s?: number) => svg('<path d="M9 8l-4 4 4 4M15 8l4 4-4 4"/>', s),
  reload: (s?: number) => svg('<path d="M19 12a7 7 0 1 1-2.05-4.95"/><path d="M19 5v4h-4"/>', s),
  winMin: (s?: number) => svg('<path d="M6 12h12"/>', s),
  winMax: (s?: number) => svg('<rect x="6" y="6" width="12" height="12" rx="3"/>', s),
  github: (s?: number) => `<svg class="ic" width="${s ?? 20}" height="${s ?? 20}" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M12 2a10 10 0 0 0-3.16 19.49c.5.09.68-.22.68-.48v-1.7c-2.78.6-3.37-1.34-3.37-1.34-.45-1.16-1.11-1.47-1.11-1.47-.91-.62.07-.6.07-.6 1 .07 1.53 1.03 1.53 1.03.9 1.52 2.34 1.08 2.91.83.09-.65.35-1.09.63-1.34-2.22-.25-4.55-1.11-4.55-4.94 0-1.09.39-1.98 1.03-2.68-.1-.25-.45-1.27.1-2.64 0 0 .84-.27 2.75 1.02a9.6 9.6 0 0 1 5 0c1.91-1.29 2.75-1.02 2.75-1.02.55 1.37.2 2.39.1 2.64.64.7 1.03 1.59 1.03 2.68 0 3.84-2.34 4.68-4.57 4.93.36.31.68.92.68 1.85v2.74c0 .27.18.58.69.48A10 10 0 0 0 12 2z"/></svg>`,
  spinner: (s?: number) => svg('<path d="M12 4a8 8 0 0 1 8 8" class="spin"/>', s),
  search: (s?: number) => svg('<circle cx="11" cy="11" r="6"/><path d="M16 16l4 4"/>', s),
  upload: (s?: number) => svg('<path d="M12 15V5M8 9l4-4 4 4"/><path d="M5 15v2a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-2"/>', s),
  image: (s?: number) => svg('<rect x="3.5" y="5" width="17" height="14" rx="3"/><circle cx="9" cy="10" r="1.6"/><path d="M5 17l4.5-4.5 3 3L15 13l4 4"/>', s),
  info: (s?: number) => svg('<circle cx="12" cy="12" r="8.5"/><path d="M12 11v5M12 8h.01"/>', s),
  settings: (s?: number) => svg('<path d="M4 7h10M18 7h2M4 17h4M12 17h8"/><circle cx="16" cy="7" r="2"/><circle cx="10" cy="17" r="2"/>', s),
  press: (s?: number) => svg('<circle cx="12" cy="12" r="8.5"/><circle cx="12" cy="12" r="3"/>', s),
  toggle: (s?: number) => svg('<rect x="3" y="7" width="18" height="10" rx="5"/><circle cx="16" cy="12" r="2.5"/>', s),
  wifi: (s?: number) => svg('<path d="M4.5 10a11 11 0 0 1 15 0M7.5 13.2a6.5 6.5 0 0 1 9 0"/><path d="M12 17h.01"/>', s),
};

/** The app's mark: the rope from the app icon, without its plate. */
export const mark = (size = 22) => `
  <svg class="mark" width="${size}" height="${size}" viewBox="20 30 70 58" aria-hidden="true">
    <defs><linearGradient id="markRope" x1="0.1" y1="0.1" x2="0.9" y2="0.95">
      <stop offset="0" stop-color="#6fc9b6"/><stop offset=".35" stop-color="#4aa3bb"/><stop offset=".7" stop-color="#6b84dc"/><stop offset="1" stop-color="#9488e8"/>
    </linearGradient></defs>
    <path d="M 27 40 C 21 58 30 79 50 80 C 62 81 70 70 67 59 C 64 48 52 48 47 56 C 43 63 50 68 57 63 C 64 58 72 48 84 44"
      fill="none" stroke="url(#markRope)" stroke-width="7" stroke-linecap="round" stroke-linejoin="round"/>
  </svg>`;

/** Flags for the language menu, drawn small and rounded. */
export const flag = (l: 'en' | 'de') => l === 'de'
  ? `<svg class="flag" width="20" height="14" viewBox="0 0 5 3" aria-hidden="true"><rect width="5" height="1" fill="#1b1b1b"/><rect y="1" width="5" height="1" fill="#dd2a2a"/><rect y="2" width="5" height="1" fill="#f6c63f"/></svg>`
  : `<svg class="flag" width="20" height="14" viewBox="0 0 60 30" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
      <clipPath id="ukT"><path d="M30,15 h30 v15 z v15 h-30 z h-30 v-15 z v-15 h30 z"/></clipPath>
      <rect width="60" height="30" fill="#1f3a8a"/>
      <path d="M0,0 L60,30 M60,0 L0,30" stroke="#fff" stroke-width="6"/>
      <path d="M0,0 L60,30 M60,0 L0,30" clip-path="url(#ukT)" stroke="#d22f3c" stroke-width="4"/>
      <path d="M30,0 v30 M0,15 h60" stroke="#fff" stroke-width="10"/>
      <path d="M30,0 v30 M0,15 h60" stroke="#d22f3c" stroke-width="6"/>
    </svg>`;
