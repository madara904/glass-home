// Every word the window shows, in English and German. English is the default; German when
// picked in the top bar, remembered from then on. The backend reports in English; its fixed
// messages are translated here by text, the ones with numbers by pattern.

export type Lang = 'en' | 'de';

const en = {
  langName: 'English',
  devtools: 'Developer tools',
  reload: 'Reload',
  noDevice: 'No Fire TV connected',
  disconnect: 'Disconnect',

  introTitle: 'Before you\nconnect',
  introSub: 'Glass Home talks to the Fire TV over your Wi-Fi. Turn that on once, on the TV.',
  introAbout: 'Open About',
  introAboutPath: 'Settings › My Fire TV › About',
  introPress: 'Become a developer',
  introPressPath: 'Press OK on the device name seven times',
  introAdb: 'Turn on ADB debugging',
  introAdbPath: 'My Fire TV › Developer options',
  introWifi: 'Same Wi-Fi',
  introWifiPath: 'The Fire TV and this computer on one network',
  introVega: 'Fire TVs with Vega OS are not supported.',
  introGo: 'Continue',

  connectTitle: 'Connect\nyour Fire TV',
  connectSub: 'Glass Home looks for it on your network.',
  manualTitle: 'Not in the list? Enter its address.',
  manualHint: 'It is under Settings › My Fire TV › About › Network.',
  connect: 'Connect',
  connecting: 'Connecting …',
  allow: 'If the TV asks, confirm “Allow” there.',
  badIp: 'An address looks like this: 192.168.1.20',
  scan: 'Search again',
  scanning: 'Searching …',
  scanNone: 'No Fire TV found. Is ADB debugging on?',
  adbOpen: 'ADB open',
  turnOnAdb: 'Turn on ADB',
  notFire: 'Google TV, not supported',
  fireTv: 'Fire TV',
  howTo: 'How to turn on ADB',
  unsupported: 'This device is not supported.',

  debloat: 'Debloat',
  debloatOn: (n: number) => `On · ${n} packages off`,
  notApplied: 'Not applied',
  glassUi: 'Glass UI',
  installed: (v: string) => `Installed · ${v}`,
  notInstalled: 'Not installed',
  restore: 'Restore',
  restoreValue: 'Amazon as shipped',
  nothingToDo: 'Nothing to do',
  foot: 'Nothing in the system itself changes. Everything can be restored.',
  adbOff: 'Turn off ADB debugging',
  adbOffTitle: 'Turn off ADB debugging?',
  adbOffSub: 'Recommended once you are done: nobody on your network can control this Fire TV over ADB any more.',
  adbOffKeeps: 'Glass Home, your colours, photos and the DNS filter keep working.',
  adbOffAfter: 'This window can’t reach the TV afterwards.',
  adbOffBack: 'To restore, update or debloat later, turn it back on at the TV yourself: Settings › My Fire TV › Developer options › ADB debugging.',
  adbOffGo: 'Turn off',
  adbOffBusy: 'Turning off…',
  adbOffDone: 'ADB debugging is off on the Fire TV. Turn it back on at the TV when you need this window again.',

  debloatSub: 'What stays. Everything else of Amazon’s ads and tracking goes off.',
  keep: 'Keep',
  off: 'Off',
  on: 'On',
  primeVideo: 'Prime Video',
  alexa: 'Alexa',
  updates: 'Fire OS updates',
  dnsFilter: 'DNS filter',
  yourApps: 'Your apps',
  readingApps: 'Reading your apps’ names …',
  noApps: 'No apps of your own found.',
  removeNote: 'Remove deletes an app together with its data.',
  stays: 'Stays',
  remove: 'Remove',
  cancel: 'Cancel',
  start: 'Start',

  uiSub: 'The home screen made of glass. Everything else you set on the Fire TV.',
  layout: 'Layout',
  dock: 'Dock',
  list: 'List',
  icons: 'Icons',
  tiles: 'Tiles',
  colours: 'Colours',
  wallpaper: 'Wallpaper',
  choosePhoto: 'Choose a photo…',
  photoNote: 'Click the picture to pick your own photo. It becomes the TV’s background, cropped to 16:9, and the colours follow it (Auto).',
  sendingPhoto: 'Sending it to the TV…',
  photoSent: 'On the TV now. Pick another one any time.',
  photoUnreadable: 'That photo could not be opened. Pick a JPEG, PNG or WebP picture.',
  coloursNote: 'Auto takes its colour from the TV’s wallpaper. You can change it on the TV too, under Settings › Colours.',
  themeName: { neutral: 'Neutral', auto: 'Auto', mint: 'Mint', sand: 'Sand', lavender: 'Lavender' } as Record<string, string>,
  look: 'Look',
  glassSymbols: 'Glass symbols',
  originalBanners: 'Original banners',
  update: 'Update',
  install: 'Install',

  restoreSub: 'The Fire TV goes back to how Amazon ships it.',
  restoreHome: 'Amazon’s home screen comes back',
  restorePackages: 'Turned-off packages go back on',
  restoreSettings: 'Settings go back to Amazon’s values',
  restoreFilter: 'DNS filter and Glass Home go',
  restoreNote: 'Removed apps do not come back by themselves. Get them from the Appstore if you need them.',

  keepOn: 'Do not turn the Fire TV off now.',
  done: 'Done',
  allGood: 'Done and checked.',
  someFailed: 'Not everything worked. The details are next to the steps.',
};

type Dict = { [K in keyof typeof en]: (typeof en)[K] };

const de: Dict = {
  langName: 'Deutsch',
  devtools: 'Entwicklertools',
  reload: 'Aktualisieren',
  noDevice: 'Kein Fire TV verbunden',
  disconnect: 'Trennen',

  introTitle: 'Bevor du\nverbindest',
  introSub: 'Glass Home spricht mit dem Fire TV über dein WLAN. Das schaltest du einmal am TV ein.',
  introAbout: 'Info öffnen',
  introAboutPath: 'Einstellungen › Mein Fire TV › Info',
  introPress: 'Entwickler werden',
  introPressPath: 'Siebenmal OK auf den Gerätenamen drücken',
  introAdb: 'ADB-Debugging einschalten',
  introAdbPath: 'Mein Fire TV › Entwickleroptionen',
  introWifi: 'Selbes WLAN',
  introWifiPath: 'Fire TV und dieser Computer im selben Netz',
  introVega: 'Fire TVs mit Vega OS werden nicht unterstützt.',
  introGo: 'Weiter',

  connectTitle: 'Fire TV\nverbinden',
  connectSub: 'Glass Home sucht ihn in deinem Netzwerk.',
  manualTitle: 'Nicht in der Liste? Gib seine Adresse ein.',
  manualHint: 'Sie steht unter Einstellungen › Mein Fire TV › Info › Netzwerk.',
  connect: 'Verbinden',
  connecting: 'Verbinde …',
  allow: 'Falls der TV fragt, dort „Zulassen“ bestätigen.',
  badIp: 'Eine Adresse sieht so aus: 192.168.1.20',
  scan: 'Erneut suchen',
  scanning: 'Suche …',
  scanNone: 'Kein Fire TV gefunden. Ist ADB-Debugging an?',
  adbOpen: 'ADB offen',
  turnOnAdb: 'ADB einschalten',
  notFire: 'Google TV, nicht unterstützt',
  fireTv: 'Fire TV',
  howTo: 'So schaltest du ADB ein',
  unsupported: 'Dieses Gerät wird nicht unterstützt.',

  debloat: 'Debloat',
  debloatOn: (n: number) => `Aktiv · ${n} Pakete aus`,
  notApplied: 'Nicht angewendet',
  glassUi: 'Glass UI',
  installed: (v: string) => `Installiert · ${v}`,
  notInstalled: 'Nicht installiert',
  restore: 'Wiederherstellen',
  restoreValue: 'Wie von Amazon',
  nothingToDo: 'Nichts zu tun',
  foot: 'Am System selbst ändert sich nichts. Alles lässt sich wiederherstellen.',
  adbOff: 'ADB-Debugging ausschalten',
  adbOffTitle: 'ADB-Debugging ausschalten?',
  adbOffSub: 'Empfohlen, wenn du fertig bist: Niemand in deinem Netzwerk kann diesen Fire TV dann noch über ADB steuern.',
  adbOffKeeps: 'Glass Home, deine Farben, Fotos und der DNS-Filter laufen weiter.',
  adbOffAfter: 'Dieses Fenster erreicht den TV danach nicht mehr.',
  adbOffBack: 'Für Wiederherstellen, Updates oder Debloat später am TV selbst wieder einschalten: Einstellungen › Mein Fire TV › Entwickleroptionen › ADB-Debugging.',
  adbOffGo: 'Ausschalten',
  adbOffBusy: 'Wird ausgeschaltet…',
  adbOffDone: 'ADB-Debugging ist am Fire TV aus. Am TV wieder einschalten, wenn du dieses Fenster wieder brauchst.',

  debloatSub: 'Was bleiben soll. Alles andere an Werbung und Tracking von Amazon geht aus.',
  keep: 'Behalten',
  off: 'Aus',
  on: 'An',
  primeVideo: 'Prime Video',
  alexa: 'Alexa',
  updates: 'Fire-OS-Updates',
  dnsFilter: 'DNS-Filter',
  yourApps: 'Deine Apps',
  readingApps: 'Die Namen deiner Apps werden gelesen …',
  noApps: 'Keine eigenen Apps gefunden.',
  removeNote: 'Entfernen löscht eine App samt ihrer Daten.',
  stays: 'Bleibt',
  remove: 'Entfernen',
  cancel: 'Abbrechen',
  start: 'Starten',

  uiSub: 'Der Startbildschirm aus Glas. Alles Weitere stellst du am Fire TV ein.',
  layout: 'Layout',
  dock: 'Dock',
  list: 'Liste',
  icons: 'Icons',
  tiles: 'Kacheln',
  colours: 'Farben',
  wallpaper: 'Hintergrund',
  choosePhoto: 'Foto auswählen…',
  photoNote: 'Auf das Bild klicken und ein eigenes Foto wählen. Es wird zum Hintergrund am TV, zugeschnitten auf 16:9, und die Farben passen sich an (Auto).',
  sendingPhoto: 'Wird an den TV geschickt…',
  photoSent: 'Jetzt am TV. Du kannst jederzeit ein anderes wählen.',
  photoUnreadable: 'Das Foto ließ sich nicht öffnen. Bitte ein JPEG-, PNG- oder WebP-Bild wählen.',
  coloursNote: 'Auto nimmt die Farbe aus dem Hintergrundbild am TV. Auch am TV änderbar unter Einstellungen › Farben.',
  themeName: { neutral: 'Neutral', auto: 'Auto', mint: 'Mint', sand: 'Sand', lavender: 'Lavendel' } as Record<string, string>,
  look: 'Aussehen',
  glassSymbols: 'Glas-Symbole',
  originalBanners: 'Original-Banner',
  update: 'Aktualisieren',
  install: 'Installieren',

  restoreSub: 'Der Fire TV wird wieder so, wie Amazon ihn ausliefert.',
  restoreHome: 'Amazons Startbildschirm kommt zurück',
  restorePackages: 'Abgeschaltete Pakete gehen wieder an',
  restoreSettings: 'Einstellungen auf Amazons Werte',
  restoreFilter: 'DNS-Filter und Glass Home gehen',
  restoreNote: 'Entfernte Apps kommen nicht von selbst zurück. Die holst du bei Bedarf aus dem Appstore.',

  keepOn: 'Den Fire TV jetzt nicht ausschalten.',
  done: 'Fertig',
  allGood: 'Fertig und geprüft.',
  someFailed: 'Nicht alles hat geklappt. Details stehen bei den Schritten.',
};

// What the backend says, in German. Exact texts first, then texts with numbers or names.
const backendDe: Record<string, string> = {
  'The Fire TV still answers over ADB. Turn ADB debugging off on the TV: Settings › My Fire TV › Developer options.': 'Der Fire TV antwortet noch über ADB. ADB-Debugging am TV ausschalten: Einstellungen › Mein Fire TV › Entwickleroptionen.',
  'That photo could not be read. Pick a JPEG, PNG or WebP picture.': 'Das Foto ließ sich nicht lesen. Bitte ein JPEG-, PNG- oder WebP-Bild wählen.',
  'Glass Home is not on this Fire TV yet. Install Glass UI first.': 'Glass Home ist noch nicht auf diesem Fire TV. Zuerst Glass UI installieren.',
  'Back up settings': 'Einstellungen sichern',
  'Turn off Amazon packages': 'Amazon-Pakete abschalten',
  'Remove selected apps': 'Ausgewählte Apps entfernen',
  'Throttle background services': 'Hintergrunddienste drosseln',
  'Privacy settings': 'Datenschutz-Einstellungen',
  'Set up DNS filter': 'DNS-Filter einrichten',
  'Restart': 'Neustart',
  'Check': 'Prüfen',
  'Keep existing settings': 'Vorhandene Einstellungen übernehmen',
  'Install Glass Home': 'Glass Home installieren',
  'Apply layout': 'Layout einstellen',
  'Take over the home screen': 'Startbildschirm übernehmen',
  "Bring back Amazon's home screen": 'Amazons Startbildschirm zurück',
  'Turn packages back on': 'Pakete wieder einschalten',
  'Lift throttling': 'Drosselungen aufheben',
  'Reset settings': 'Einstellungen zurücksetzen',
  'Remove DNS filter': 'DNS-Filter entfernen',
  'saved': 'gesichert',
  'already saved': 'schon vorhanden',
  'none selected': 'keine ausgewählt',
  'not yet on Fire OS 8': 'auf Fire OS 8 noch nicht',
  'not selected': 'nicht gewählt',
  'removed': 'entfernt',
  'was not installed': 'war nicht installiert',
  'settings kept': 'Einstellungen behalten',
  'old version carried over': 'alte Version übernommen',
  'first install': 'erste Installation',
  'Glass Home is in front': 'Glass Home ist vorn',
  'Amazon defaults': 'Amazon-Standard',
  'as before': 'wie vorher',
  'Lost the connection to the Fire TV': 'Verbindung zum Fire TV verloren',
  'No Fire TV connected.': 'Kein Fire TV verbunden.',
  'No network found.': 'Kein Netzwerk gefunden.',
  'Only IPv4 networks can be searched.': 'Nur IPv4-Netze werden durchsucht.',
  'The Fire TV did not allow the connection. On the TV, confirm “Allow USB debugging” with OK.':
    'Der Fire TV hat die Verbindung nicht zugelassen. Am TV „USB-Debugging zulassen“ mit OK bestätigen.',
  'The DNS filter is not blocking yet. Restart the Fire TV once.': 'Der DNS-Filter blockiert noch nicht. Den Fire TV einmal neu starten.',
  'Names do not resolve: the filter cannot reach the router.': 'Namen lösen nicht auf: der Filter erreicht den Router nicht.',
  "Could not read the Fire TV's router address.": 'Die Router-Adresse des Fire TV ließ sich nicht lesen.',
  'The tracker list looks wrong; try again later.': 'Die Trackerliste sieht nicht richtig aus; später noch einmal versuchen.',
  "The DNS filter's checksum does not match; not installed.": 'Prüfsumme des DNS-Filters stimmt nicht; nicht installiert.',
  "The DNS filter's template has changed; not set up.": 'Die Vorlage des DNS-Filters hat sich geändert; nicht eingerichtet.',
  'The DNS filter did not create its folder.': 'Der DNS-Filter hat seinen Ordner nicht angelegt.',
  "Android's backup service is not running on this Fire TV; the filter cannot be set up here.":
    'Auf diesem Fire TV läuft Androids Backup-Dienst nicht; der Filter lässt sich hier nicht einrichten.',
  'The confirmation on the Fire TV did not come through. Run Debloat again and choose “Restore my data” on the TV.':
    'Die Bestätigung am Fire TV kam nicht durch. Debloat noch einmal starten und am TV „Daten wiederherstellen“ wählen.',
  'Fire OS 5 and older cannot turn packages off. This device is not supported.':
    'Fire OS 5 und älter lassen keine Pakete abschalten. Dieses Gerät wird nicht unterstützt.',
  'The Fire TV did not come back after the restart. Please check the TV.': 'Der Fire TV ist nach dem Neustart nicht wieder erreichbar. Bitte am TV nachsehen.',
  'Fire OS did not allow the home screen takeover. Are parental controls on?': 'Fire OS hat die HOME-Übernahme nicht zugelassen. Ist die Kindersicherung an?',
};

const backendDePatterns: [RegExp, (...m: string[]) => string][] = [
  [/^(\d+) off, (\d+) protected$/, (_, a, b) => `${a} aus, ${b} geschützt`],
  [/^(\d+) off$/, (_, a) => `${a} aus`],
  [/^(\d+) entries$/, (_, a) => `${a} Einträge`],
  [/^(\d+) removed$/, (_, a) => `${a} entfernt`],
  [/^(\d+) services$/, (_, a) => `${a} Dienste`],
  [/^(\d+) set$/, (_, a) => `${a} gesetzt`],
  [/^(\d+) packages off$/, (_, a) => `${a} Pakete aus`],
  [/^(\d+) back on$/, (_, a) => `${a} wieder an`],
  [/^(\d+) settings$/, (_, a) => `${a} Einstellungen`],
  [/^version (.+)$/, (_, v) => `Version ${v}`],
  [/^In front: unknown$/, () => 'Vorn ist unbekannt'],
  [/^In front: (.+)$/, (_, p) => `Vorn ist ${p}`],
  [/^adb could not start: (.+)$/, (_, e) => `adb konnte nicht starten: ${e}`],
  [/^Could not reach (.+)\. Is ADB debugging on, and are the Fire TV and this computer on the same Wi-Fi\?$/,
    (_, ip) => `Keine Verbindung zu ${ip}. Ist ADB-Debugging an und sind Fire TV und dieser Computer im selben WLAN?`],
  [/^Copying to the Fire TV failed: (.*)$/, (_, e) => `Kopieren auf den Fire TV fehlgeschlagen: ${e}`],
  [/^Reading from the Fire TV failed: (.*)$/, (_, e) => `Lesen vom Fire TV fehlgeschlagen: ${e}`],
  [/^Install failed: (.*)$/, (_, e) => `Installation fehlgeschlagen: ${e}`],
  [/^Download failed (.*)$/, (_, e) => `Download fehlgeschlagen ${e}`],
  [/^This is not an Amazon Fire TV \((.*)\)\. Glass Home is built for Fire OS\.$/,
    (_, m) => `Das ist kein Fire TV von Amazon (${m}). Glass Home ist für Fire OS gebaut.`],
  [/^Fire OS 5 or older$/, () => 'Fire OS 5 oder älter'],
];

const KEY = 'glasshome.lang';

function initial(): Lang {
  try {
    const saved = localStorage.getItem(KEY);
    if (saved === 'en' || saved === 'de') return saved;
  } catch { /* storage may be off */ }
  return 'en';
}

export let lang: Lang = initial();
export let t: Dict = lang === 'de' ? de : en;

export function setLang(l: Lang) {
  lang = l;
  t = l === 'de' ? de : en;
  document.documentElement.lang = l;
  try { localStorage.setItem(KEY, l); } catch { /* storage may be off */ }
}
document.documentElement.lang = lang;

/** A message from the backend in the window's language. Unknown ones stay as they came. */
export function tr(msg: string | null | undefined): string {
  if (!msg) return '';
  if (lang === 'en') return msg;
  if (backendDe[msg]) return backendDe[msg];
  for (const [re, f] of backendDePatterns) {
    const m = msg.match(re);
    if (m) return f(...m);
  }
  return msg;
}
