// The bridge to the Rust side. Outside Tauri (a plain browser, for design work) every call is
// answered by a mock that mirrors a real Fire TV Stick, so the interface can be judged on its own.

export interface Device {
  serial: string;
  ip: string;
  model: string;      // ro.product.model, e.g. AFTSS
  name: string;       // retail name from the catalog, e.g. "Fire TV Stick Lite"
  fireOs: string;     // "Fire OS 7"
  sdk: number;
  supported: boolean;
  reason?: string;    // why not, e.g. Vega OS
}

/** A device the network search found, before connecting. */
export interface Found {
  ip: string;
  name?: string | null;    // its own name ("Living Room Fire TV"), else the router's host name
  model?: string | null;   // only where it says so before connecting (Chromecast)
  vendor?: string | null;  // "Amazon" from the MAC prefix
  kind: 'fire' | 'unknown' | 'google';
  adb: boolean;            // answers on adb's port 5555
}

export interface DeviceState {
  debloated: boolean;
  disabled: number;
  dnsFilter: boolean;
  glassUi: boolean;
  glassVersion?: string;
  layout: 'dock' | 'list' | 'icons';
  tiles: 'original' | 'glass';
  theme: Theme;
  arrows: boolean;
}

export interface Unfinished {
  task: 'debloat' | 'ui' | 'restore';
  name: string;
  ip: string;
}

export interface App {
  pkg: string;
  label: string;
}

export interface DebloatOptions {
  keepPrime: boolean;
  keepAlexa: boolean;
  keepUpdates: boolean;
  dnsFilter: boolean;
  remove: string[];
  /** Packages of the lists the owner unchecked: they stay on. */
  skip: string[];
}

export interface PlanItem {
  pkg: string;
  desc: string;
  /** Off on the TV already. */
  off: boolean;
}

/** What Debloat would turn off on this TV, grouped as in the lists. */
export interface Plan {
  groups: { key: string; items: PlanItem[] }[];
  alexa: PlanItem[];
  protected: PlanItem[];
  /** The updater's packages: they stay while Fire OS updates are kept. */
  ota: string[];
}

/** The launcher's colour schemes, as stored in glass.theme on the TV. */
export type Theme = 'neutral' | 'auto' | 'mint' | 'sand' | 'lavender';
export const THEMES: Theme[] = ['neutral', 'auto', 'mint', 'sand', 'lavender'];

export interface UiOptions {
  layout: 'dock' | 'list' | 'icons';
  tiles: 'original' | 'glass';
  theme: Theme;
  /** Chevrons beside the dock where more apps are. */
  arrows: boolean;
}

export type StepState = 'pending' | 'running' | 'done' | 'failed' | 'skipped';
export interface Step {
  id: string;
  label: string;
  state: StepState;
  detail?: string;
}

const inTauri = '__TAURI_INTERNALS__' in window;

async function invoke<T>(cmd: string, args?: Record<string, unknown>): Promise<T> {
  const core = await import('@tauri-apps/api/core');
  return core.invoke<T>(cmd, args);
}

/** Runs a task on the Rust side and reports each step as it changes. */
async function runTask(cmd: string, args: Record<string, unknown>, onStep: (s: Step) => void): Promise<boolean> {
  const { listen } = await import('@tauri-apps/api/event');
  const stop = await listen<Step>('step', (e) => onStep(e.payload));
  try {
    return await invoke<boolean>(cmd, args);
  } finally {
    stop();
  }
}

// ------------------------------------------------------------------ mock

const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));
const mockDevice: Device = {
  serial: '192.168.1.20:5555', ip: '192.168.1.20', model: 'AFTSS',
  name: 'Fire TV Stick Lite', fireOs: 'Fire OS 7', sdk: 28, supported: true,
};
const mockState: DeviceState = { debloated: true, disabled: 34, dnsFilter: true, glassUi: true, glassVersion: '0.2.0', layout: 'dock', tiles: 'original', theme: 'neutral', arrows: false };
const pi = (pkg: string, desc: string, off = false): PlanItem => ({ pkg, desc, off });
const mockPlan: Plan = {
  groups: [
    { key: 'ads, promos and shopping', items: [pi('com.amazon.hybridadidservice', 'advertising ID', true), pi('com.amazon.sneakpeek', 'autoplaying promos', true), pi('com.amazon.media.recommendations', 'sponsored rows', true), pi('com.amazon.shoptv.client', 'shopping on the TV', true)] },
    { key: 'tracking what you watch', items: [pi('com.amazon.tv.acr', 'Automatic Content Recognition: fingerprints the screen, HDMI included', true), pi('com.amazon.hedwig', 'watch time and engagement', true)] },
    { key: 'telemetry, logging, crash reporting', items: [pi('com.amazon.tv.fw.metrics', 'system usage metrics', true), pi('com.amazon.logan', 'log collection and upload', true), pi('com.amazon.ceviche', 'analytics pipeline, uploads to unagi-*.amazon.com', true)] },
    { key: 'experiments and remote config', items: [pi('com.amazon.ale', 'experiments (A/B tests)', true), pi('com.amazon.katoch', 'remote feature switches', true)] },
    { key: 'update nags', items: [pi('com.amazon.tv.forcedotaupdater.v2', 'forced update prompts', true)] },
    { key: 'demos, tutorials and empty stubs', items: [pi('com.amazon.tmm.tutorial', 'tutorial videos', true), pi('com.amazon.dummy.music', 'empty placeholder app', true)] },
  ],
  alexa: [pi('com.amazon.aca', 'the app that owns the microphone'), pi('com.amazon.rtcsessioncontroller', 'Alexa calling'), pi('com.amazon.aria', 'Alexa visual cards')],
  protected: [pi('com.amazon.device.metrics', 'device metrics'), pi('com.amazon.device.crashmanager', 'crash reports')],
  ota: ['com.amazon.device.software.ota', 'com.amazon.device.software.ota.override', 'com.amazon.tv.forcedotaupdater.v2'],
};
const mockApps: App[] = [
  { pkg: 'com.ionitech.airscreen', label: 'AirScreen' },
  { pkg: 'com.disney.disneyplus', label: 'Disney+' },
  { pkg: 'com.esaba.downloader', label: 'Downloader' },
  { pkg: 'com.plexapp.android', label: 'Plex' },
  { pkg: 'com.netflix.ninja', label: 'Netflix' },
];

async function mockRun(steps: string[], onStep: (s: Step) => void): Promise<boolean> {
  for (const [i, label] of steps.entries()) {
    const id = String(i);
    onStep({ id, label, state: 'running' });
    await wait(700);
    onStep({ id, label, state: 'done' });
  }
  return true;
}

// ------------------------------------------------------------------ calls

export const api = {
  discover: (): Promise<Found[]> =>
    inTauri ? invoke('discover') : wait(1400).then(() => [
      { ip: '192.168.1.20', name: 'Living Room Fire TV', vendor: 'Amazon', kind: 'fire', adb: true },
      { ip: '192.168.1.31', name: 'Fire TV Cube', vendor: 'Amazon', kind: 'fire', adb: false },
      { ip: '192.168.1.28', name: 'Living Room TV', model: 'Google TV', kind: 'google', adb: true },
    ] as Found[]),
  connect: (ip: string): Promise<Device> =>
    inTauri ? invoke('connect', { ip }) : wait(900).then(() => ({ ...mockDevice, ip, serial: ip + ':5555' })),
  state: (): Promise<DeviceState> => (inTauri ? invoke('device_state') : wait(300).then(() => mockState)),
  /** What Debloat would turn off on this TV. */
  plan: (): Promise<Plan> => (inTauri ? invoke<Plan>('debloat_plan') : wait(400).then(() => mockPlan)),
  apps: (): Promise<App[]> => (inTauri ? invoke('user_apps') : wait(300).then(() => mockApps)),
  /** The owner's photo (a 1920 x 1080 JPEG) as the TV's wallpaper, with a colour scheme. */
  setWallpaper: (jpeg: Uint8Array, theme: Theme): Promise<void> =>
    inTauri ? invoke<void>('set_wallpaper', { image: Array.from(jpeg), theme }) : wait(1200).then(() => undefined),
  /** A task the last run of the app did not finish (killed, or the phone died), once. */
  unfinished: (): Promise<Unfinished | null> => (inTauri ? invoke<Unfinished | null>('unfinished') : Promise.resolve(null)),
  disconnect: (): Promise<void> => (inTauri ? invoke('disconnect') : Promise.resolve()),
  debloat: (o: DebloatOptions, onStep: (s: Step) => void) =>
    inTauri ? runTask('run_debloat', { options: o }, onStep)
      : mockRun(['Turn off Amazon packages', 'Privacy settings', 'Throttle background services',
          'Set up DNS filter', 'Restart', 'Check'], onStep),
  installUi: (o: UiOptions, onStep: (s: Step) => void) =>
    inTauri ? runTask('run_install_ui', { options: o }, onStep)
      : mockRun(['Install Glass Home', 'Take over the home screen', 'Apply layout', 'Check'], onStep),
  /** Turns ADB debugging off on the TV; the connection ends with it. */
  adbOff: (): Promise<void> => (inTauri ? invoke<void>('adb_off') : wait(1500).then(() => undefined)),
  restore: (onStep: (s: Step) => void) =>
    inTauri ? runTask('run_restore', {}, onStep)
      : mockRun(["Bring back Amazon's home screen", 'Turn packages back on', 'Reset settings',
          'Remove DNS filter', 'Restart'], onStep),
};
