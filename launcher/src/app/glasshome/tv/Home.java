package app.glasshome.tv;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen: CleanFrame's photo, clock and glass dock, drawn by {@link Stage}. HOME is
 * still taken over by dumbtv's {@link Guard}; this activity only replaces what is on screen.
 * The dock and the photo live in home.conf as glass.dock and glass.wall.
 */
public final class Home extends Activity implements Stage.Host {
    private static final String APPSTORE = "com.amazon.venezia";
    private static final String DOCK = "glass.dock", WALL = "glass.wall", ORDER = "glass.order";
    /** Amazon apps shown even with "Amazon apps: few": Prime Video, the Appstore, Silk. */
    private static final String[] AMAZON_FEW = {"com.amazon.firebat", APPSTORE, "com.amazon.cloud9"};
    /** The first dock on a new install: the apps that have a mark, in this order. */
    private static final String[] PREFERRED = {"com.netflix.ninja", "com.amazon.firebat",
            "com.disney.disneyplus", "com.amazon.firetv.youtube",
            "com.liskovsoft.smarttubetv.beta", "com.apple.atve.amazon.appletv", "com.spotify.tv.android"};
    /**
     * System screens, not apps: troubleshooting, Amazon's own home, the profile switcher, the
     * screensaver, the system status page, Alexa's "what to watch" panel, and Prime Video's
     * second entry ("FireTV Player"), which duplicates com.amazon.firebat.
     */
    private static final String[] HIDDEN = {"com.amazon.firetv.troubleshooting", "com.amazon.tv.launcher",
            "com.amazon.ftv.profilepicker", "com.amazon.ftv.screensaver", "com.amazon.ssm",
            "com.amazon.vizzini.ftvcds", "com.amazon.avod"};

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Config cfg;
    private Stage stage;
    private List<Item> apps = new ArrayList<>();
    private boolean resumed;

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            io.execute(() -> {
                List<Item> found = Catalog.apps(Home.this);
                runOnUiThread(() -> {
                    stage.forgetCards(); // an update can bring new art
                    showApps(found);
                });
            });
        }
    };

    private final BroadcastReceiver time = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            stage.tick();
            // Once a minute: a photo or settings sent while HOME stays in front, and the slideshow.
            pickUpChanges();
            slideshow();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        cfg = Config.get(this);
        stage = new Stage(this, this, io);
        setContentView(stage);
        stage.requestFocus();
        // Both are read before the first frame, so HOME never shows an empty dock or a bare colour.
        // The colour scheme first: Lavender replaces the photo, and Auto measures it as it loads.
        stage.setPrefs(prefs(), fireHome());
        stage.setWall(wallIndex());
        seen = cfg.version();
        applied = look();
        photoStamp = photoStamp();
        showApps(Catalog.apps(this));

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
        Keeper.start(this);
        Filter.watch(this);
    }

    /** The settings version and own photo the screen was last drawn with. */
    private int seen;
    private long photoStamp;

    /** The stored wallpaper; the own photo only while there is one to show. */
    private int wallIndex() {
        int i = Walls.index(cfg.get(WALL, Walls.IDS[0]));
        return i == Walls.CUSTOM && Walls.customFile(this) == null ? 0 : i;
    }

    private long photoStamp() {
        java.io.File f = Walls.customFile(this);
        return f == null ? 0 : f.lastModified() * 31 + f.length();
    }

    /**
     * Settings or an own photo sent from the Glass Home tool, picked up when HOME comes back to
     * the front: no restart, so the HOME guard stays bound.
     */
    private void pickUpChanges() {
        cfg.refresh();
        long stamp = photoStamp();
        boolean photo = stamp != photoStamp;
        photoStamp = stamp;
        seen = cfg.version();
        // Only what changes the look counts: the launcher writes its own bookkeeping (app order,
        // hidden apps) into the same file, and redrawing for that stalled moving on Home.
        String look = look();
        if (!look.equals(applied)) {
            String wallBefore = applied == null ? "" : applied.substring(applied.lastIndexOf('|') + 1);
            applied = look;
            stage.setPrefs(prefs(), fireHome());
            if (!wallBefore.equals(cfg.get(WALL, ""))) stage.changeWall(wallIndex());
        } else if (photo) {
            stage.customPhotoChanged();
        }
    }

    private static final String[] LOOK = {Stage.Prefs.MATERIAL, Stage.Prefs.CONTROLS, Stage.Prefs.FONT,
            Stage.Prefs.NAMES, Stage.Prefs.DATE, Stage.Prefs.AMAZON, Stage.Prefs.LAYOUT, Stage.Prefs.POSITION,
            Stage.Prefs.STYLE, Stage.Prefs.TILES, Stage.Prefs.TEXT, Stage.Prefs.HOME, Stage.Prefs.ICONS,
            Stage.Prefs.THEME, Stage.Prefs.SLIDE, Stage.Prefs.SLIDE_EVERY, Stage.Prefs.SLIDE_ORDER, Stage.Prefs.SLIDES};
    /** The settings the screen was last drawn with; the wallpaper comes last. */
    private String applied;

    private String look() {
        StringBuilder b = new StringBuilder();
        for (String k : LOOK) b.append(cfg.get(k, "")).append('|');
        return b.append(cfg.get(WALL, "")).toString();
    }

/** When the slideshow last changed the photo; it waits a full interval after HOME starts. */
    private long slideAt = android.os.SystemClock.elapsedRealtime();
    private final java.util.Random random = new java.util.Random();

    /**
     * The slideshow: once the interval is up, the next photo of the list (or a random other one),
     * saved as the wallpaper like a choice made on the TV. The own photo counts only while it exists.
     */
    private void slideshow() {
        Stage.Prefs p = prefs();
        long now = android.os.SystemClock.elapsedRealtime();
        if (!p.slideOn || now - slideAt < Stage.Prefs.EVERY[p.slideEvery] * 60_000L) return;
        List<String> list = new ArrayList<>();
        for (String id : p.slides) {
            boolean known = Walls.CUSTOM_ID.equals(id) ? Walls.customFile(this) != null : java.util.Arrays.asList(Walls.IDS).contains(id);
            if (known) list.add(id);
        }
        if (list.size() < 2) return;
        slideAt = now;
        String current = cfg.get(WALL, Walls.IDS[0]);
        String next;
        if (p.slideShuffle) {
            do next = list.get(random.nextInt(list.size())); while (next.equals(current));
        } else {
            next = list.get((list.indexOf(current) + 1) % list.size());
        }
        cfg.set(WALL, next);
        seen = cfg.version();
        applied = look();
        stage.changeWall(Walls.index(next));
    }

    private Stage.Prefs prefs() {
        Stage.Prefs p = new Stage.Prefs();
        String m = cfg.get(Stage.Prefs.MATERIAL, "soft");
        p.material = "frost".equals(m) ? 1 : "clear".equals(m) ? 2 : 0;
        p.controlText = !"icons".equals(cfg.get(Stage.Prefs.CONTROLS, "text"));
        p.namesAlways = "always".equals(cfg.get(Stage.Prefs.NAMES, "focus"));
        p.showDate = !"off".equals(cfg.get(Stage.Prefs.DATE, "on"));
        p.amazonApps = "all".equals(cfg.get(Stage.Prefs.AMAZON, "few"));
        String lay = cfg.get(Stage.Prefs.LAYOUT, "dock");
        p.layout = "icons".equals(lay) ? 2 : "list".equals(lay) ? 1 : 0;
        String pos = cfg.get(Stage.Prefs.POSITION, "bottom");
        p.position = "top".equals(pos) ? 2 : "middle".equals(pos) ? 1 : 0;
        p.glassIcons = "glass".equals(cfg.get(Stage.Prefs.ICONS, "banner"));
        String st = cfg.get(Stage.Prefs.STYLE, "shelf");
        p.style = "full".equals(st) ? 1 : "none".equals(st) ? 2 : 0;
        p.tiles = level(cfg.get(Stage.Prefs.TILES, "medium"));
        p.text = level(cfg.get(Stage.Prefs.TEXT, "normal"));
        String f = cfg.get(Stage.Prefs.FONT, "geist");
        p.font = "newsreader".equals(f) ? 1 : "system".equals(f) ? 2 : 0;
        p.theme = Math.max(0, java.util.Arrays.asList(Stage.Prefs.THEMES).indexOf(cfg.get(Stage.Prefs.THEME, "neutral")));
        p.slideOn = "on".equals(cfg.get(Stage.Prefs.SLIDE, "off"));
        p.slideShuffle = "shuffle".equals(cfg.get(Stage.Prefs.SLIDE_ORDER, "order"));
        String every = cfg.get(Stage.Prefs.SLIDE_EVERY, "15");
        for (int i = 0; i < Stage.Prefs.EVERY.length; i++) if (String.valueOf(Stage.Prefs.EVERY[i]).equals(every)) p.slideEvery = i;
        p.slides.addAll(cfg.list(Stage.Prefs.SLIDES));
        return p;
    }

    private static int level(String v) {
        return "small".equals(v) ? 0 : "large".equals(v) ? 2 : 1;
    }

    /** Whether the owner handed HOME back to Fire TV's own home screen (see Guard). */
    private boolean fireHome() {
        return "fire".equals(cfg.get(Stage.Prefs.HOME, "glass"));
    }

    private void showApps(List<Item> found) {
        apps = found;
        Map<String, Item> byId = new LinkedHashMap<>();
        for (Item it : found) byId.put(it.id, it);
        List<Item> dock = new ArrayList<>();
        if (cfg.get(DOCK, null) == null) {
            for (String id : PREFERRED) if (byId.containsKey(id)) dock.add(byId.get(id));
        } else {
            for (String id : cfg.list(DOCK)) if (byId.containsKey(id)) dock.add(byId.get(id));
        }
        List<Item> listed = new ArrayList<>();
        boolean amazon = "all".equals(cfg.get(Stage.Prefs.AMAZON, "few"));
        for (Item it : found) if (listed(it, amazon)) listed.add(it);
        // The owner's order first; apps never moved (a new install) follow in A to Z order.
        List<String> order = cfg.list(ORDER);
        Collections.sort(listed, (a, b) -> Integer.compare(rank(order, a.id), rank(order, b.id)));
        // Banners and icons come from each app itself, so a new install shows its own art with
        // nothing to configure. The dock's are read now, so the first frame is complete; the
        // rest follow off the UI thread.
        for (Item it : dock) art(it);
        stage.setApps(dock, listed);
        io.execute(() -> {
            for (Item it : listed) {
                if (it.artRequested) continue;
                art(it);
                runOnUiThread(stage::artLoaded);
            }
        });
    }

    private static int rank(List<String> order, String id) {
        int i = order.indexOf(id);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    private void art(Item it) {
        if (it.artRequested) return;
        it.artRequested = true;
        try {
            Catalog.art(getPackageManager(), it, false, null);
        } catch (RuntimeException e) {
            // An app whose resources fail to load keeps its name on a glass tile.
        }
    }

    /**
     * The owner's TV apps, found automatically: every installed app with a TV launcher entry,
     * so a new install appears with its own banner. Amazon's preinstalled apps show as Prime
     * Video, Appstore and Silk only, or all of them with "Amazon apps: all" (they are built for
     * Fire TV but most only declare the plain launcher entry). Any other plain-launcher app is a
     * phone app (personalDNSfilter, Downloads, a phone build) that opens phone-sized: left out.
     */
    private static boolean listed(Item it, boolean allAmazon) {
        if (Arrays.asList(HIDDEN).contains(it.id)) return false;
        if (it.id.startsWith("com.amazon.")) return allAmazon || Arrays.asList(AMAZON_FEW).contains(it.id);
        return it.leanback;
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        Guard.arrived(this);
        Filter.wake(this);
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(time, f);
        stage.tick();
        pickUpChanges();
        io.execute(() -> {
            Map<String, Long> times = Recent.times(Home.this);
            runOnUiThread(() -> stage.setRecent(times));
        });
        if (fireHome()) {
            stage.say(getString(R.string.g_fire_home_active));
        }
    }

    @Override
    public void onEnterAnimationComplete() {
        Guard.shown();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        unregisterReceiver(time);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        io.shutdownNow();
        super.onDestroy();
    }

    /**
     * Android 9 keeps a hidden window's GPU context (about 35 MB on a TV) unless memory is
     * critical. Dropping it while an app plays costs one context rebuild on the way back.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level < TRIM_MEMORY_UI_HIDDEN || level >= TRIM_MEMORY_COMPLETE) return;
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object global = wmg.getMethod("getInstance").invoke(null);
            wmg.getMethod("trimMemory", int.class).invoke(global, TRIM_MEMORY_COMPLETE);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // not reachable on this build: the context simply stays
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (resumed) stage.reset();
    }

    @Override
    public void onBackPressed() {
        stage.reset();
    }

    // ---------------------------------------------------------------- Stage.Host

    @Override
    public void open(Item it) {
        launch(appIntent(it), it.id);
    }

    @Override
    public void dockChanged(List<Item> dock) {
        List<String> ids = new ArrayList<>();
        for (Item it : dock) ids.add(it.id);
        // An empty dock is saved as "-", so it is not mistaken for a first start.
        if (ids.isEmpty()) cfg.set(DOCK, "-");
        else cfg.setList(DOCK, ids);
    }

    @Override
    public void wallChanged(int wall) {
        cfg.set(WALL, Walls.id(wall));
        seen = cfg.version();
        applied = look();
        slideAt = android.os.SystemClock.elapsedRealtime();
    }

    @Override
    public void orderChanged(List<Item> order) {
        List<String> ids = new ArrayList<>();
        for (Item it : order) ids.add(it.id);
        // Keep the places of apps hidden right now (Amazon apps: few), so showing them again
        // does not lose their spot.
        for (String id : cfg.list(ORDER)) if (!ids.contains(id)) ids.add(id);
        cfg.setList(ORDER, ids);
    }

    /** Fire TV's own home screen from now on; Guard leaves it alone until switched back. */
    @Override
    public void useFireHome() {
        Guard.amazon(this);
    }

    @Override
    public void prefChanged(String key, String value) {
        cfg.set(key, value);
        seen = cfg.version();
        applied = look();
        if (Stage.Prefs.AMAZON.equals(key)) showApps(apps);
    }

    /** Fire OS's own uninstall dialog, which asks before anything is removed. */
    @Override
    public void uninstall(Item it) {
        try {
            startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + it.id))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.could_not_open, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Fire TV's own pages, in the order of the settings list: network, display and sound,
     * remotes and Bluetooth, applications, device. Each opens directly where Amazon lets another
     * app start it; otherwise Fire TV's settings page opens and the section is one click away.
     */
    private static final String[][] FIRE = {
        {"com.amazon.device.settings.action.ADD_NETWORK", "com.amazon.tv.settings.v2.tv.network.NetworkActivity"},
        {Intent.ACTION_MAIN, "com.amazon.tv.settings.v2.tv.display_sounds.DisplayAndSoundsActivity"},
        {"com.amazon.device.settings.action.CONTROLLERS",
                "com.amazon.tv.settings.v2.tv.controllers_bluetooth_devices.ControllersAndBluetoothActivity"},
        {"com.amazon.device.settings.action.APPLICATIONS",
                "com.amazon.tv.settings.v2.tv.applications.ApplicationsActivity"},
        {"com.amazon.device.settings.action.DEVICE", "com.amazon.tv.settings.v2.tv.device.DeviceActivity"},
    };

    @Override
    public void fireSettings(int i) {
        if (i >= 0 && i < FIRE.length) {
            // Clearing the settings task means BACK leaves to this home screen, not to a page
            // opened earlier and left with HOME.
            Intent page = new Intent(FIRE[i][0]).setClassName("com.amazon.tv.settings.v2", FIRE[i][1])
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
            if (FireSettings.startable(this, getPackageManager(), page)) {
                launch(page, null);
                return;
            }
        }
        settings();
    }

    /**
     * Fire OS answers the generic settings action with a stub that reopens Amazon's home, which
     * Guard then replaces with ours. The TV settings entry point opens the real settings screen,
     * except on Fire OS 6, where that page draws off the top of the screen: see FireSettings.
     */
    private void settings() {
        Intent tvSettings = new Intent(Intent.ACTION_MAIN)
                .addCategory("android.intent.category.LEANBACK_SETTINGS");
        if (FireSettings.applies(getPackageManager(), tvSettings)) {
            List<Item> sections = FireSettings.sections(this);
            CharSequence[] labels = new CharSequence[sections.size()];
            for (int i = 0; i < labels.length; i++) labels[i] = sections.get(i).label;
            new AlertDialog.Builder(this, Ui.DIALOG)
                    .setTitle(R.string.settings)
                    .setItems(labels, (d, which) -> {
                        Item s = sections.get(which);
                        if (Guard.AMAZON.equals(s.id)) Guard.pause();
                        launch(new Intent(s.intent), null);
                    })
                    .show();
            return;
        }
        boolean found = tvSettings.resolveActivity(getPackageManager()) != null;
        launch(found ? tvSettings : new Intent(Settings.ACTION_SETTINGS), null);
    }

    /**
     * On Fire OS 8 the Appstore's launcher entry opens Amazon's home with its apps grid, which
     * Guard then replaces with ours. Its store screen is opened instead.
     */
    private Intent appIntent(Item it) {
        if (Build.VERSION.SDK_INT >= 30 && APPSTORE.equals(it.id)) {
            Intent store = new Intent(Intent.ACTION_MAIN)
                    .setClassName(APPSTORE, APPSTORE + ".ade.ADEHomeActivity");
            if (store.resolveActivity(getPackageManager()) != null) return store;
        }
        return new Intent(it.intent);
    }

    private void launch(Intent i, String pkg) {
        if (Guard.AMAZON.equals(pkg)) Guard.pause();
        try {
            // No transition: on a warm start of an app with a trampoline activity, such as
            // Prime Video, the animation showed home again between the two.
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                    ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle());
            if (pkg != null) Recent.opened(this, pkg);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.could_not_open, Toast.LENGTH_SHORT).show();
        }
    }
}
