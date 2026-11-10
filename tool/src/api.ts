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
}

/** The launcher's colour schemes, as stored in glass.theme on the TV. */
export type Theme = 'neutral' | 'auto' | 'mint' | 'sand' | 'lavender';
export const THEMES: Theme[] = ['neutral', 'auto', 'mint', 'sand', 'lavender'];

export interface UiOptions {
  layout: 'dock' | 'list' | 'icons';
  tiles: 'original' | 'glass';
  theme: Theme;
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
const mockState: DeviceState = { debloated: true, disabled: 34, dnsFilter: true, glassUi: true, glassVersion: '1.0', layout: 'icons', tiles: 'original', theme: 'neutral' };
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
  apps: (): Promise<App[]> => (inTauri ? invoke('user_apps') : wait(300).then(() => mockApps)),
  disconnect: (): Promise<void> => (inTauri ? invoke('disconnect') : Promise.resolve()),
  debloat: (o: DebloatOptions, onStep: (s: Step) => void) =>
    inTauri ? runTask('run_debloat', { options: o }, onStep)
      : mockRun(['Turn off Amazon packages', 'Privacy settings', 'Throttle background services',
          'Set up DNS filter', 'Restart', 'Check'], onStep),
  installUi: (o: UiOptions, onStep: (s: Step) => void) =>
    inTauri ? runTask('run_install_ui', { options: o }, onStep)
      : mockRun(['Install Glass Home', 'Take over the home screen', 'Apply layout', 'Check'], onStep),
  restore: (onStep: (s: Step) => void) =>
    inTauri ? runTask('run_restore', {}, onStep)
      : mockRun(["Bring back Amazon's home screen", 'Turn packages back on', 'Reset settings',
          'Remove DNS filter', 'Restart'], onStep),
};
