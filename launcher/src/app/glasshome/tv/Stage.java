package app.glasshome.tv;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * The home screen, drawn on one 1920 x 1080 stage (1:1 on a 1080p Fire TV): a photograph, the
 * time, glass controls at the top right and the apps as a glass dock or a list. Focus lifts a
 * tile with a soft shadow and a light sheen; nothing is outlined. The same stage draws the apps
 * grid, the settings page, the wallpaper picker, the options menu and the move modes.
 */
final class Stage extends View {
    interface Host {
        void open(Item it);
        void uninstall(Item it);
        void dockChanged(List<Item> dock);
        /** The apps list in the owner's order. */
        void orderChanged(List<Item> order);
        void wallChanged(int wall);
        /** Saves one appearance setting (see {@link Prefs}). */
        void prefChanged(String key, String value);
        /** Hands HOME back to Fire TV's own home screen. */
        void useFireHome();
        /** Opens Fire TV's own settings page {@code i} (network, display, remotes, apps, device). */
        void fireSettings(int i);
    }

    /** Appearance settings, after CleanFrame's choices. */
    static final class Prefs {
        static final String MATERIAL = "glass.material", CONTROLS = "glass.controls", FONT = "glass.font",
                NAMES = "glass.names", DATE = "glass.date", AMAZON = "glass.amazon", LAYOUT = "glass.layout",
                POSITION = "glass.position", STYLE = "glass.style", TILES = "glass.tiles", TEXT = "glass.text",
                HOME = "glass.home", ICONS = "glass.icons";
        /** soft, frost, clear: which pre-rendered glass the dock is made of. */
        int material;
        /** 0 dock, 1 list with names, 2 icons only. */
        int layout;
        /** Dock or list: 0 bottom, 1 middle, 2 top. */
        int position;
        /** Tiles show the apps' own banners, or white marks on clear glass. */
        boolean glassIcons;
        /** Dock: 0 glass shelf, 1 full width, 2 no shelf. */
        int style;
        /** 0 small, 1 medium, 2 large. */
        int tiles = 1, text = 1;
        /** 0 Geist, 1 Newsreader, 2 the system face. */
        int font;
        boolean controlText = true, namesAlways, showDate = true, amazonApps;
    }

    static final int HOME = 0, ARRANGE = 1, APPS = 2, WALLS = 3, SETTINGS = 4, MENU = 5, APPS_MOVE = 6;

    private static final int W = 1920, H = 1080;
    // Apps grid.
    private static final int GW = 292, GH = 164, GX = 150, GY = 230, GPITCH = GW + 40, ROW = GH + 92;
    private static final int COLS = 5, ROWS = 3;
    // List layout.
    private static final int LX = 150, LY = 236;
    // List with names: a row per app, CleanFrame's List layout.
    private static final int RW = 680, RROW = 104, RVIS = 7;
    // Controls, top right.
    private static final int CTL_H = 60, CTL_Y = 78, CTL_RIGHT = 1770;
    private static final float LIFT = 1.1f;
    // Cards are rendered once at the largest size they are shown at: a lifted large tile.
    private static final int CARD_W = 396, CARD_H = 223;
    private static final int INK = 0xFFF6F1E7, SOLID = 0xFFF3F3EF, SOLID_INK = 0xFF141618;
    private static final int MOVE_MS = 200, FADE_MS = 160, SLIDE_MS = 240;
    private static final long LONG_PRESS_MS = 550, STATUS_MS = 3200;
    private static final Interpolator EASE = new PathInterpolator(.2f, .7f, .2f, 1f);

    private static final class Tween {
        private float from, to;
        private long start;
        private int dur;

        Tween(float v) {
            from = to = v;
        }

        void go(float v, int ms) {
            if (ms <= 0) {
                from = to = v;
                dur = 0;
                return;
            }
            if (v == to) return;
            from = get();
            to = v;
            start = SystemClock.uptimeMillis();
            dur = ms;
        }

        float get() {
            if (dur <= 0) return to;
            float t = (SystemClock.uptimeMillis() - start) / (float) dur;
            if (t >= 1) {
                dur = 0;
                return to;
            }
            return from + (to - from) * EASE.getInterpolation(t);
        }

        boolean running() {
            return dur > 0 && SystemClock.uptimeMillis() - start < dur;
        }
    }

    /** An app's banner (or icon on clear glass), pre-rendered at {@link #CARD_W} x {@link #CARD_H}. */
    private static final class Card {
        final BitmapShader shader;
        final boolean opaque;

        Card(Bitmap b, boolean opaque) {
            shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            this.opaque = opaque;
        }
    }

    private interface Action {
        void run();
    }

    private final Host host;
    private final ExecutorService io;
    private final boolean de = "de".equals(Locale.getDefault().getLanguage());
    private Typeface regular, medium, semibold;
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint(), shadeLight = new Paint(), scrim = new Paint(), listShade = new Paint();
    private final Matrix matrix = new Matrix();
    private final Rect stageRect = new Rect(0, 0, W, H);
    private final Map<String, Path> edges = new HashMap<>();
    private final Map<String, LinearGradient> shaders = new HashMap<>();
    private final Map<String, Card> cards = new HashMap<>();
    private final Map<Integer, Drawable> icons = new HashMap<>();
    private final Map<String, Tween[]> slides = new HashMap<>();
    private boolean sliding;

    private Prefs prefs = new Prefs();
    // Dock geometry, from the tile size.
    private int tw, th, gap, pad, pitch, vis, tileR, dockR, dockH;
    // List geometry: icon size, row pitch and rows that fit between the clock and the bottom.
    private int lw, lh, lrow, lvis;
    private Bitmap photo, soft, frost;
    private Rect photoCrop;
    private boolean photoLight;
    private BitmapShader softShader, frostShader, photoShader;
    private final Card[] thumbCards = new Card[Walls.IDS.length];
    private int wall, wallBefore, wallToken;
    private boolean thumbsLoading;

    private final List<Item> dock = new ArrayList<>();
    private List<Item> all = new ArrayList<>();
    private int mode = HOME, wallsFrom = HOME, appsFrom = HOME, menuFrom = HOME;
    private boolean inCtl, onAction, fireHome;
    private int dockI, ctlI, appsI, wallI, setI, first, appsRow, menuI;
    private final Tween focus = new Tween(0), focusAlpha = new Tween(1), track = new Tween(0);
    private final Tween ctlPos = new Tween(0), ctlAlpha = new Tween(0);
    private final Tween gx = new Tween(0), gy = new Tween(0), gScroll = new Tween(0);
    private final Tween rowY = new Tween(0), listOff = new Tween(0), menuY = new Tween(0);
    // Icons layout: the focused app's name on the right, cross-faded when focus moves.
    private final Tween heroFade = new Tween(1);
    private String heroId, heroPrev;
    /** How visible the outgoing name was when it started to fade (a fast second press lands mid-fade). */
    private float heroPrevA = 1;
    /** Share of the change the outgoing name takes to fade; the new name only appears after it. */
    private static final float HERO_OUT = .38f;
    private Map<String, Long> recent = new HashMap<>();
    private final Paint heroDim = new Paint();
    private final float[] ctlX = new float[3], ctlW = new float[3];
    private final List<String> menuLabels = new ArrayList<>();
    private final List<Action> menuActions = new ArrayList<>();
    private Item menuItem;
    private final RectF menuAnchor = new RectF();
    private String clock = "", date = "";
    private String status;
    private long statusAt;
    private boolean longHandled;
    private long okDown;

    Stage(Context c, Host host, ExecutorService io) {
        super(c);
        this.host = host;
        this.io = io;
        setFocusable(true);
        setFocusableInTouchMode(true);
        loadFonts();
        geometry();
        shade.setShader(vertical(0, 340, new int[] {Color.argb(92, 4, 10, 12), Color.argb(40, 4, 10, 12),
                Color.argb(0, 4, 10, 12)}, new float[] {0, .5f, 1}));
        shadeLight.setShader(vertical(0, 360, new int[] {Color.argb(140, 4, 10, 12), Color.argb(66, 4, 10, 12),
                Color.argb(0, 4, 10, 12)}, new float[] {0, .5f, 1}));
        scrim.setShader(vertical(560, 1080, new int[] {Color.argb(0, 4, 10, 12), Color.argb(102, 4, 10, 12),
                Color.argb(128, 4, 10, 12)}, new float[] {0, .7f, 1}));
        listShade.setShader(new LinearGradient(0, 0, 1000, 0, Color.argb(120, 4, 10, 12), Color.argb(0, 4, 10, 12),
                Shader.TileMode.CLAMP));
        tick();
    }

    private static LinearGradient vertical(float y0, float y1, int[] colors, float[] stops) {
        return new LinearGradient(0, y0, 0, y1, colors, stops, Shader.TileMode.CLAMP);
    }

    private void loadFonts() {
        if (prefs.font == 2) {
            regular = Typeface.create("sans-serif", Typeface.NORMAL);
            medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            semibold = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            return;
        }
        String file = prefs.font == 1 ? "newsreader.ttf" : "geist.ttf";
        regular = font(getContext(), file, prefs.font == 1 ? 420 : 450);
        medium = font(getContext(), file, prefs.font == 1 ? 520 : 500);
        semibold = font(getContext(), file, prefs.font == 1 ? 600 : 560);
    }

    private static Typeface font(Context c, String file, int weight) {
        try {
            Typeface t = new Typeface.Builder(c.getAssets(), file)
                    .setFontVariationSettings("'wght' " + weight).build();
            if (t != null) return t;
        } catch (RuntimeException ignored) {
            // fall back to the system face below
        }
        return Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    /** Small, medium, large tiles: 240, 300 or 360 wide, 16:9, everything else in proportion. */
    private void geometry() {
        int s = prefs.tiles;
        tw = s == 0 ? 240 : s == 2 ? 360 : 300;
        th = Math.round(tw * 9f / 16f);
        gap = s == 0 ? 30 : s == 2 ? 40 : 36;
        pad = Math.round(28f * tw / 300f);
        pitch = tw + gap;
        vis = s == 0 ? 6 : s == 2 ? 4 : 5;
        tileR = Math.round(18f * tw / 300f);
        dockR = tileR + pad;
        dockH = th + 2 * pad;
        lw = s == 0 ? 176 : s == 2 ? 248 : 208;
        lh = Math.round(lw * 9f / 16f);
        lrow = lh + 26;
        lvis = Math.max(3, (1000 - LY + 26) / lrow);
    }

    private String t(String german, String english) {
        return de ? german : english;
    }

    // ---------------------------------------------------------------- shared shapes

    private static int blend(int a, int b, float t) {
        float u = Math.max(0, Math.min(1, t));
        return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * u),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * u),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * u));
    }

    private static int white(float alpha) {
        return Color.argb(Math.round(Math.max(0, Math.min(1, alpha)) * 255), 255, 255, 255);
    }

    /** CleanFrame's glass: linear-gradient(170deg, a, b 58%, c) in white, for a w x h box. */
    private LinearGradient glassShader(float w, float h, float a, float b, float c) {
        String key = Math.round(w) + "x" + Math.round(h) + "/" + a + "/" + b + "/" + c;
        LinearGradient g = shaders.get(key);
        if (g == null) {
            double rad = Math.toRadians(170);
            float dx = (float) Math.sin(rad), dy = (float) -Math.cos(rad);
            float len = Math.abs(w * dx) + Math.abs(h * dy), cx = w / 2, cy = h / 2;
            g = new LinearGradient(cx - dx * len / 2, cy - dy * len / 2, cx + dx * len / 2, cy + dy * len / 2,
                    new int[] {white(a), white(b), white(c)}, new float[] {0, .58f, 1}, Shader.TileMode.CLAMP);
            shaders.put(key, g);
        }
        return g;
    }

    /** inset 0 1px 0: the light crescent along the top of a rounded shape. */
    private Path edge(float w, float h, float r) {
        String key = Math.round(w) + "x" + Math.round(h) + "/" + r;
        Path p = edges.get(key);
        if (p == null) {
            p = new Path();
            p.addRoundRect(0, 0, w, h, r, r, Path.Direction.CW);
            Path lower = new Path();
            lower.addRoundRect(0, 1.5f, w, h + 1.5f, r, r, Path.Direction.CW);
            p.op(lower, Path.Op.DIFFERENCE);
            edges.put(key, p);
        }
        return p;
    }

    /**
     * A pane of glass at the canvas origin; {@code strength} 0 = resting, 1 = focused. {@code dark}
     * lays a smoky base under it first, so glass stays visible over a bright photograph.
     */
    private void pane(Canvas c, float w, float h, float r, float strength, float alpha, float dark) {
        if (dark > 0) {
            glass.setShader(null);
            glass.setColor(Color.argb(Math.round(dark * alpha * 255), 8, 10, 14));
            c.drawRoundRect(0, 0, w, h, r, r, glass);
        }
        float s = strength;
        glass.setShader(glassShader(w, h, .20f + .30f * s, .08f + .24f * s, .12f + .26f * s));
        glass.setAlpha(Math.round(alpha * 255));
        c.drawRoundRect(0, 0, w, h, r, r, glass);
        glass.setShader(null);
        glass.setColor(white((.26f + .16f * s) * alpha));
        c.drawPath(edge(w, h, r), glass);
    }

    private void softShadow(Canvas c, float w, float h, float r, float strength) {
        shadow.setColor(Color.argb(1, 0, 0, 0));
        shadow.setShadowLayer(30 * strength, 0, 16 * strength, Color.argb(Math.round(120 * strength), 0, 0, 0));
        c.drawRoundRect(2, 3, w - 2, h - 2, r, r, shadow);
        shadow.clearShadowLayer();
    }

    private Drawable icon(int res) {
        Drawable d = icons.get(res);
        if (d == null) {
            d = getContext().getDrawable(res).mutate();
            d.setTint(INK);
            icons.put(res, d);
        }
        return d;
    }

    private float textScale() {
        return prefs.text == 0 ? .9f : prefs.text == 2 ? 1.12f : 1f;
    }

    private void textStyle(Typeface face, float size, float tracking, int color) {
        float s = size * textScale();
        text.setTypeface(face);
        text.setTextSize(s);
        text.setLetterSpacing(tracking / size);
        text.setColor(color);
        text.clearShadowLayer();
        text.setTextAlign(Paint.Align.LEFT);
    }

    /** Text over a photograph: a short, soft shadow so it reads on mist and on rock. */
    private void photoShadow(float alpha) {
        text.setShadowLayer(7, 0, 1.5f, Color.argb(Math.round(130 * alpha), 6, 8, 10));
    }

    private float baseline(float top, float lineHeight) {
        Paint.FontMetrics fm = text.getFontMetrics();
        return top + (lineHeight - (fm.descent - fm.ascent)) / 2 - fm.ascent;
    }

    /**
     * Where an item is drawn: its slot, reached by a short ease when the slot changes (moving a
     * tile, or a neighbour making room). New items start in place.
     */
    private float[] slid(String key, float x, float y) {
        Tween[] s = slides.get(key);
        if (s == null) {
            s = new Tween[] {new Tween(x), new Tween(y)};
            slides.put(key, s);
        } else {
            s[0].go(x, SLIDE_MS);
            s[1].go(y, SLIDE_MS);
        }
        if (s[0].running() || s[1].running()) sliding = true;
        return new float[] {s[0].get(), s[1].get()};
    }

    // ---------------------------------------------------------------- data

    void setApps(List<Item> dockItems, List<Item> allItems) {
        String focused = dockI < dock.size() ? dock.get(dockI).id : null;
        dock.clear();
        dock.addAll(dockItems);
        all = allItems;
        dockI = 0;
        for (int i = 0; i < dock.size(); i++) if (dock.get(i).id.equals(focused)) dockI = i;
        appsI = Math.min(appsI, Math.max(0, all.size() - 1));
        if (mode == MENU) mode = menuFrom; // the app the menu was for may be gone
        placeDock(false);
        placeGrid(false);
        invalidate();
    }

    /** When each app was last opened from here, for the line under its name. */
    void setRecent(Map<String, Long> times) {
        recent = times;
        invalidate();
    }

    void artLoaded() {
        invalidate();
    }

    void forgetCards() {
        cards.clear();
    }

    void setPrefs(Prefs p, boolean fire) {
        prefs = p;
        fireHome = fire;
        loadFonts();
        geometry();
        slides.clear();
        placeDock(false);
        invalidate();
    }

    void say(String s) {
        status = s;
        statusAt = SystemClock.uptimeMillis();
        invalidate();
    }

    void setWall(int i) {
        wall = i;
        loadWall(i, true);
    }

    private void loadWall(int i, boolean now) {
        final int token = ++wallToken;
        Runnable job = () -> {
            Context c = getContext();
            Bitmap p = Walls.photo(c, i), s = Walls.soft(c, i), f = Walls.frost(c, i);
            boolean light = p != null && topIsLight(p);
            Runnable apply = () -> {
                if (token != wallToken) return;
                photo = p;
                photoCrop = p == null ? null : Walls.crop(p);
                photoLight = light;
                soft = s;
                frost = f;
                softShader = s == null ? null : new BitmapShader(s, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                frostShader = f == null ? null : new BitmapShader(f, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                photoShader = p == null ? null : new BitmapShader(p, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                invalidate();
            };
            if (now) apply.run();
            else post(apply);
        };
        if (now) job.run();
        else io.execute(job);
    }

    /** Whether the band behind the clock and controls is bright, measured on the photo itself. */
    private static boolean topIsLight(Bitmap p) {
        Rect crop = Walls.crop(p);
        int band = crop.height() * 30 / 100;
        double sum = 0;
        int n = 0;
        for (int y = crop.top + 4; y < crop.top + band; y += Math.max(1, band / 12)) {
            for (int x = crop.left + crop.width() / 2; x < crop.right - 4; x += Math.max(1, crop.width() / 40)) {
                int c = p.getPixel(x, y);
                sum += (.2126 * Color.red(c) + .7152 * Color.green(c) + .0722 * Color.blue(c)) / 255;
                n++;
            }
        }
        return n > 0 && sum / n > .55;
    }

    private void loadThumbs() {
        if (thumbsLoading) return;
        thumbsLoading = true;
        io.execute(() -> {
            for (int i = 0; i < thumbCards.length; i++) {
                Bitmap b = Walls.thumb(getContext(), i);
                if (b == null) continue;
                Bitmap card = Bitmap.createBitmap(CARD_W, CARD_H, Bitmap.Config.ARGB_8888);
                new Canvas(card).drawBitmap(b, Walls.crop(b), new Rect(0, 0, CARD_W, CARD_H), image);
                final int idx = i;
                final Card made = new Card(card, true);
                post(() -> {
                    thumbCards[idx] = made;
                    invalidate();
                });
            }
        });
    }

    /**
     * The app's TV banner, or its icon centred on clear glass; null until loaded. A 16:9 banner
     * fills the tile; any other shape is fitted whole on its own edge colour, never cut off.
     */
    private Card card(Item it) {
        Card k = cards.get(it.id);
        if (k != null) return k;
        if (prefs.glassIcons) {
            k = glassCard(it);
            cards.put(it.id, k);
            return k;
        }
        Drawable banner = it.banner, appIcon = it.icon;
        if (banner == null && appIcon == null) return null;
        Bitmap b = Bitmap.createBitmap(CARD_W, CARD_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        boolean wide = banner != null && Catalog.isWide(banner);
        if (wide) {
            int iw = Math.max(1, banner.getIntrinsicWidth()), ih = Math.max(1, banner.getIntrinsicHeight());
            float aspect = iw / (float) ih, tile = CARD_W / (float) CARD_H;
            boolean fits = Math.abs(aspect - tile) / tile < .06f;
            float s = fits ? Math.max(CARD_W / (float) iw, CARD_H / (float) ih)
                    : Math.min(CARD_W / (float) iw, CARD_H / (float) ih);
            int w = Math.round(iw * s), h = Math.round(ih * s);
            banner.setBounds((CARD_W - w) / 2, (CARD_H - h) / 2, (CARD_W + w) / 2, (CARD_H + h) / 2);
            banner.draw(c);
            if (!fits) {
                int edge = b.getPixel(Math.max(0, (CARD_W - w) / 2 + 2), Math.max(0, (CARD_H - h) / 2 + 2));
                c.drawColor(edge | 0xFF000000);
                banner.draw(c);
            }
        } else {
            Drawable d = appIcon != null ? appIcon : banner;
            int s = Math.round(CARD_H * .52f);
            d.setBounds((CARD_W - s) / 2, (CARD_H - s) / 2, (CARD_W + s) / 2, (CARD_H + s) / 2);
            d.draw(c);
        }
        k = new Card(b, wide);
        cards.put(it.id, k);
        return k;
    }

    /**
     * CleanFrame's look: a white mark on clear glass. Known services use their monochrome logo,
     * placed by its measured ink like CleanFrame does; any other app gets a white silhouette of
     * its own icon's foreground, and where the icon is a filled square, its name.
     */
    private Card glassCard(Item it) {
        Bitmap b = Bitmap.createBitmap(CARD_W, CARD_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        String id = markId(it.id);
        float[] m = id == null ? null : Marks.of(id);
        if (m != null) {
            Drawable d = getContext().getDrawable(Marks.res(id)).mutate();
            d.setTint(INK);
            float w = CARD_W * .46f, h = CARD_H * .46f, size = CARD_H * .40f;
            float weight = Math.max(.84f, Math.min(1.25f, (float) Math.pow(.45 / m[4], .3)));
            float k = Math.min(Math.min(w / m[2], h / m[3]), size * weight / (float) Math.sqrt(m[2] * m[3]));
            float s = 240 * k, l = CARD_W / 2f - (m[0] + m[2] / 2) * k, t = CARD_H / 2f - (m[1] + m[3] / 2) * k;
            d.setBounds(Math.round(l), Math.round(t), Math.round(l + s), Math.round(t + s));
            d.draw(c);
            return new Card(b, false);
        }
        Drawable icon = it.icon;
        if (icon == null && it.info != null) {
            try {
                icon = it.info.loadIcon(getContext().getPackageManager());
            } catch (RuntimeException ignored) {
                icon = null;
            }
        }
        if (icon instanceof android.graphics.drawable.AdaptiveIconDrawable) {
            Drawable fg = ((android.graphics.drawable.AdaptiveIconDrawable) icon).getForeground();
            if (fg != null) {
                // The foreground spans 108 units with the logo in the middle 72: draw it 1.5x.
                int s = Math.round(CARD_H * .5f * 1.5f);
                fg.setBounds((CARD_W - s) / 2, (CARD_H - s) / 2, (CARD_W + s) / 2, (CARD_H + s) / 2);
                fg.draw(c);
                c.drawColor(INK, android.graphics.PorterDuff.Mode.SRC_IN);
                if (coverage(b) < .55f) return new Card(b, false);
                b.eraseColor(Color.TRANSPARENT);
            }
        }
        text.setTypeface(semibold);
        text.setTextSize(40);
        text.setLetterSpacing(-.015f);
        text.setColor(INK);
        text.clearShadowLayer();
        text.setTextAlign(Paint.Align.CENTER);
        String n = TextUtils.ellipsize(it.label, text, CARD_W - 60, TextUtils.TruncateAt.END).toString();
        c.drawText(n, CARD_W / 2f, baseline(CARD_H / 2f - 26, 52), text);
        return new Card(b, false);
    }

    /** Share of the logo's square that is opaque: a silhouette that fills it is no logo. */
    private static float coverage(Bitmap b) {
        int s = Math.round(CARD_H * .5f), x0 = (CARD_W - s) / 2, y0 = (CARD_H - s) / 2, n = 0, on = 0;
        for (int y = y0; y < y0 + s; y += 6) {
            for (int x = x0; x < x0 + s; x += 6) {
                n++;
                if (Color.alpha(b.getPixel(x, y)) > 128) on++;
            }
        }
        return n == 0 ? 1 : on / (float) n;
    }

    /** Which of CleanFrame's monochrome marks stands for an app; null for none. */
    static String markId(String pkg) {
        switch (pkg) {
            case "com.netflix.ninja":
                return "netflix";
            case "com.amazon.firebat":
            case "com.amazon.avod":
                return "primevideo";
            case "com.disney.disneyplus":
                return "disneyplus";
            case "com.amazon.firetv.youtube":
            case "com.google.android.youtube.tv":
            case "com.liskovsoft.smarttubetv":
            case "com.liskovsoft.smarttubetv.beta":
                return "youtube";
            case "com.apple.atve.amazon.appletv":
                return "appletv";
            case "com.spotify.tv.android":
                return "spotify";
            case "com.stremio.one":
                return "stremio";
            case "com.hbo.hbonow":
            case "com.wbd.stream":
                return "hbomax";
            case "com.tubitv":
                return "tubi";
            case "com.apple.android.music":
                return "applemusic";
            default:
                return null;
        }
    }


    void tick() {
        Date now = new Date();
        String time = new SimpleDateFormat(DateFormat.is24HourFormat(getContext()) ? "H:mm" : "h:mm",
                Locale.getDefault()).format(now);
        String day = new SimpleDateFormat(de ? "EEEE, d. MMMM" : "EEEE, MMMM d", Locale.getDefault()).format(now);
        if (!time.equals(clock) || !day.equals(date)) {
            clock = time;
            date = day;
            invalidate();
        }
    }

    /** HOME pressed while home is showing: the first app, no mode. */
    void reset() {
        if (mode == MENU) mode = menuFrom;
        if (mode == WALLS) cancelWalls();
        if (mode == ARRANGE) dropTile();
        if (mode == APPS_MOVE) finishMove();
        mode = HOME;
        inCtl = false;
        dockI = 0;
        placeDock(true);
        invalidate();
    }

    // ---------------------------------------------------------------- placement

    private boolean listLayout(int m) {
        return prefs.layout != 0 && (m == HOME || m == ARRANGE);
    }

    /** Row pitch, rows that fit and the drawn height of a row, for the chosen list. */
    private int rowPitch() {
        return prefs.layout == 1 ? RROW : lrow;
    }

    private int rowsVisible() {
        return prefs.layout == 1 ? RVIS : lvis;
    }

    private int rowHeight() {
        return prefs.layout == 1 ? RROW - 8 : lh;
    }

    private int dockCount(int m) {
        return m == WALLS ? Walls.IDS.length : dock.size();
    }

    private void placeDock(boolean animate) {
        int n = dockCount(mode), i = mode == WALLS ? wallI : dockI;
        int window = listLayout(mode) ? rowsVisible() : vis, v = Math.max(1, Math.min(window, n));
        first = Math.max(0, Math.min(first, n - v));
        if (i < first) first = i;
        if (i > first + v - 1) first = i - v + 1;
        int ms = animate ? MOVE_MS : 0;
        float step = listLayout(mode) ? rowPitch() : pitch;
        track.go(first * step, ms);
        focus.go(i * step, ms);
        focusAlpha.go(inCtl && mode == HOME ? 0 : 1, animate ? FADE_MS : 0);
        ctlAlpha.go(inCtl && mode == HOME ? 1 : 0, animate ? FADE_MS : 0);
        ctlPos.go(ctlI, ms);
        invalidate();
    }

    private void placeGrid(boolean animate) {
        int ms = animate ? MOVE_MS : 0, row = appsI / COLS;
        if (row < appsRow) appsRow = row;
        if (row > appsRow + ROWS - 1) appsRow = row - ROWS + 1;
        gScroll.go(appsRow * ROW, ms);
        gx.go(GX + (appsI % COLS) * GPITCH, ms);
        gy.go(GY + row * ROW, ms);
        invalidate();
    }

    private boolean animating() {
        return focus.running() || focusAlpha.running() || track.running() || ctlPos.running()
                || ctlAlpha.running() || gx.running() || gy.running() || gScroll.running() || rowY.running()
                || listOff.running() || menuY.running() || heroFade.running() || status != null || sliding;
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas c) {
        sliding = false;
        c.save();
        c.scale(getWidth() / (float) W, getHeight() / (float) H);
        drawMode(c, mode == MENU ? menuFrom : mode);
        if (mode == MENU) drawMenu(c);
        drawStatus(c);
        c.restore();
        if (animating()) postInvalidateOnAnimation();
    }

    private void drawMode(Canvas c, int m) {
        if (m == APPS || m == APPS_MOVE) drawApps(c, m);
        else if (m == SETTINGS) drawSettings(c);
        else drawHome(c, m);
    }

    private void drawPhoto(Canvas c) {
        if (photo != null) c.drawBitmap(photo, photoCrop, stageRect, image);
        else c.drawColor(0xFF1D2A30);
    }

    private void drawFrost(Canvas c) {
        if (frost != null) c.drawBitmap(frost, null, stageRect, image);
        else drawPhoto(c);
        c.drawColor(Color.argb(Math.round(Math.max(.32f, Walls.frostVeil(wall) + .16f) * 255), 12, 10, 8));
    }

    private void drawHome(Canvas c, int m) {
        drawPhoto(c);
        c.drawRect(0, 0, W, 360, photoLight ? shadeLight : shade);
        if (!listLayout(m)) c.drawRect(0, 560, W, H, scrim);
        else {
            // The list sits on the left: a soft shade behind it instead of the bottom scrim.
            // Its own paint: the shared fill carries the focused control's alpha.
            c.drawRect(0, 0, 1000, H, listShade);
        }
        if (m == HOME) {
            drawClock(c);
            drawControls(c);
        } else if (m == ARRANGE) {
            title(c, t("Anordnen", "Arrange"), t("Pfeiltasten verschieben · OK fertig",
                    "Arrows move · OK done"), true);
        } else {
            title(c, t("Hintergrund", "Background"), t("OK übernimmt · Zurück verwirft",
                    "OK keeps it · Back cancels"), true);
        }
        if (listLayout(m) && prefs.layout == 1) drawRows(c, m);
        else if (listLayout(m)) drawList(c, m);
        else drawDock(c, m);
    }

    private void drawClock(Canvas c) {
        textStyle(medium, 46, -1.2f, INK);
        photoShadow(1);
        c.drawText(clock, 150, baseline(78, 56), text);
        if (prefs.showDate) {
            textStyle(regular, 25, 0, INK);
            text.setAlpha(225);
            photoShadow(1);
            c.drawText(date, 150, baseline(136, 32), text);
        }
        text.clearShadowLayer();
        text.setAlpha(255);
    }

    private void title(Canvas c, String title, String sub, boolean onPhoto) {
        textStyle(medium, 46, -1.2f, INK);
        if (onPhoto) photoShadow(1);
        c.drawText(title, 150, baseline(78, 56), text);
        if (sub != null) {
            textStyle(regular, 24, 0, INK);
            text.setAlpha(onPhoto ? 215 : 185);
            if (onPhoto) photoShadow(1);
            c.drawText(sub, 150, baseline(140, 32), text);
        }
        text.clearShadowLayer();
        text.setAlpha(255);
    }

    private static final int[] CTL_ICONS = {R.drawable.mk_apps, R.drawable.mk_art, R.drawable.mk_settings};

    private String ctlLabel(int i) {
        return i == 0 ? "Apps" : i == 1 ? t("Hintergrund", "Background") : t("Einstellungen", "Settings");
    }

    private void layoutControls() {
        textStyle(medium, 24, -.2f, INK);
        float right = CTL_RIGHT;
        for (int i = CTL_ICONS.length - 1; i >= 0; i--) {
            float w = prefs.controlText ? 20 + 28 + 12 + text.measureText(ctlLabel(i)) + 24 : CTL_H;
            ctlW[i] = w;
            ctlX[i] = right - w;
            right -= w + (prefs.controlText ? 14 : 20);
        }
    }

    /**
     * The three controls: glass pills on a smoky base, so a bright photo cannot wash them out.
     * The focused one turns into CleanFrame's solid light pill with dark ink.
     */
    private void drawControls(Canvas c) {
        layoutControls();
        float pos = ctlPos.get(), a = ctlAlpha.get();
        for (int i = 0; i < CTL_ICONS.length; i++) {
            float e = a * Math.max(0, 1 - Math.abs(pos - i));
            float x = ctlX[i], w = ctlW[i], s = 1 + (prefs.controlText ? .06f : .12f) * e;
            c.save();
            c.translate(x + w / 2, CTL_Y + CTL_H / 2f);
            c.scale(s, s);
            c.translate(-w / 2, -CTL_H / 2f);
            if (e > .01f) softShadow(c, w, CTL_H, CTL_H / 2f, e * .8f);
            pane(c, w, CTL_H, CTL_H / 2f, 0, 1, .30f);
            if (e > .01f) {
                fill.setColor(SOLID);
                fill.setAlpha(Math.round(e * 255));
                c.drawRoundRect(0, 0, w, CTL_H, CTL_H / 2f, CTL_H / 2f, fill);
            }
            int ink = blend(INK, SOLID_INK, e);
            Drawable d = icon(CTL_ICONS[i]);
            float ix = prefs.controlText ? 20 : (w - 28) / 2;
            d.setBounds(Math.round(ix), 16, Math.round(ix + 28), 44);
            d.setTint(ink);
            d.setAlpha(255);
            d.draw(c);
            d.setTint(INK);
            if (prefs.controlText) {
                textStyle(medium, 24, -.2f, ink);
                c.drawText(ctlLabel(i), ix + 28 + 12, baseline(0, CTL_H), text);
            }
            c.restore();
            if (!prefs.controlText && e > .01f) {
                textStyle(medium, 22, 0, INK);
                text.setAlpha(Math.round(e * 255));
                photoShadow(e);
                text.setTextAlign(Paint.Align.CENTER);
                c.drawText(ctlLabel(i), x + w / 2, baseline(152, 28), text);
                text.clearShadowLayer();
                text.setAlpha(255);
            }
        }
    }

    /** The dock's glass, by the chosen material, showing through at its own position. */
    private void dockMaterial(Canvas c, float x, float y, float w, float h, float r) {
        BitmapShader s;
        float veil;
        if (prefs.material == 1 && frostShader != null) {
            s = frostShader;
            matrix.setScale(W / (float) frost.getWidth(), H / (float) frost.getHeight());
            veil = Walls.frostVeil(wall);
        } else if (prefs.material == 2 && photoShader != null) {
            s = photoShader;
            matrix.setRectToRect(new RectF(photoCrop), new RectF(0, 0, W, H), Matrix.ScaleToFit.FILL);
            veil = .30f;
        } else {
            s = softShader;
            if (soft != null) matrix.setScale(W / (float) soft.getWidth(), H / (float) soft.getHeight());
            veil = .10f;
        }
        if (s != null) {
            matrix.postTranslate(-x, -y);
            s.setLocalMatrix(matrix);
            glass.setShader(s);
            glass.setAlpha(255);
        } else {
            glass.setShader(null);
            glass.setColor(white(.12f));
        }
        c.drawRoundRect(0, 0, w, h, r, r, glass);
        glass.setShader(null);
        glass.setColor(Color.argb(Math.round(veil * 255), 10, 12, 16));
        c.drawRoundRect(0, 0, w, h, r, r, glass);
        glass.setColor(white(.24f));
        c.drawPath(edge(w, h, r), glass);
    }

    private float dockTop() {
        if (prefs.position == 2) return 236;
        return prefs.position == 1 ? (H - dockH) / 2f + 40 : 875 - dockH;
    }

    /** Top of the list column: under the clock, centred, or ending near the bottom edge. */
    private float listTop() {
        float h = Math.max(1, Math.min(rowsVisible(), dock.size())) * rowPitch() - (rowPitch() - rowHeight());
        if (prefs.position == 1) return Math.max(LY, (H - h) / 2f + 40);
        if (prefs.position == 0) return Math.max(LY, 1000 - h);
        return LY;
    }

    private void drawDock(Canvas c, int m) {
        int n = dockCount(m), v = Math.max(1, Math.min(vis, n));
        float content = n == 0 ? 856 : v * tw + (v - 1) * gap;
        float y = dockTop(), cx0 = (W - content) / 2f;
        // The shelf: inset glass around the tiles, a full-width band, or none at all.
        if (prefs.style == 0 || n == 0) {
            float w = content + 2 * pad, x = cx0 - pad;
            c.save();
            c.translate(x, y);
            dockMaterial(c, x, y, w, dockH, dockR);
            c.restore();
        } else if (prefs.style == 1) {
            c.save();
            c.translate(0, y);
            dockMaterial(c, 0, y, W, dockH, 0);
            c.restore();
        }
        if (n == 0) {
            textStyle(medium, 30, -.3f, INK);
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText(t("Apps hinzufügen", "Add apps"), W / 2f, baseline(y + dockH / 2f - 17, 34), text);
            return;
        }

        float off = track.get(), f = focus.get(), fa = focusAlpha.get(), left0 = cx0 - off;
        int from = Math.max(0, (int) (off / pitch) - 1), to = Math.min(n, from + vis + 2);
        c.save();
        // Room for a lifted tile at the ends; the next tile (one gap further) stays hidden.
        c.clipRect(cx0 - Math.min(18, gap / 2f), 0, cx0 + content + Math.min(18, gap / 2f), H);
        int lifted = -1;
        float best = 0;
        for (int i = from; i < to; i++) {
            float e = fa * Math.max(0, 1 - Math.abs(f - i * pitch) / pitch);
            if (e > best) {
                best = e;
                lifted = i;
            }
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int i = from; i < to; i++) {
                if ((i == lifted) != (pass == 1)) continue;
                float e = fa * Math.max(0, 1 - Math.abs(f - i * pitch) / pitch);
                float ty = y + pad, dim = m == ARRANGE && i != dockI ? .55f : 1;
                float extra = m == ARRANGE && i == dockI ? 1 : 0;
                if (m == WALLS) {
                    drawCard(c, thumbCards[i], Walls.name(i), left0 + i * pitch, ty, tw, th, e, extra, dim);
                } else {
                    Item it = dock.get(i);
                    float tx = left0 + slid("d" + it.id, i * pitch, 0)[0];
                    drawCard(c, card(it), it.label.toString(), tx, ty, tw, th, e, extra, dim);
                }
            }
        }
        c.restore();

        if (m != ARRANGE) {
            for (int i = from; i < to; i++) {
                float e = fa * Math.max(0, 1 - Math.abs(f - i * pitch) / pitch);
                float a = prefs.namesAlways && m == HOME ? .75f + .25f * e : e;
                if (a < .02f) continue;
                float cx = left0 + i * pitch + tw / 2f;
                if (cx < cx0 || cx > cx0 + content) continue;
                String name = m == WALLS ? Walls.name(i) : dock.get(i).label.toString();
                textStyle(medium, 26, -.3f, INK);
                text.setTextAlign(Paint.Align.CENTER);
                text.setAlpha(Math.round(a * 255));
                photoShadow(a);
                name = TextUtils.ellipsize(name, text, pitch - 8, TextUtils.TruncateAt.END).toString();
                c.drawText(name, cx, baseline(y + dockH + 22, 32), text);
                text.clearShadowLayer();
                text.setAlpha(255);
            }
        }
        if (m == ARRANGE) drawRemove(c, left0 + dockI * pitch + tw / 2f, y + dockH + 20);
    }

    /** CleanFrame's List layout: a column of apps on the left, each a small banner and its name. */
    private void drawRows(Canvas c, int m) {
        int n = dock.size();
        float top = listTop();
        if (n == 0) {
            textStyle(medium, 30, -.3f, INK);
            photoShadow(1);
            c.drawText(t("Apps hinzufügen: Apps öffnen, Menütaste", "Add apps: open Apps, Menu button"),
                    LX, baseline(top, 40), text);
            text.clearShadowLayer();
            return;
        }
        float off = track.get(), f = focus.get(), fa = focusAlpha.get();
        c.save();
        c.clipRect(0, top - 20, W, top + RVIS * RROW + 20);
        float py = top + f - off;
        if (fa > .01f) {
            c.save();
            c.translate(LX - 20, py);
            c.scale(1.02f, 1.02f, RW / 2f, RROW / 2f - 4);
            softShadow(c, RW, RROW - 8, 28, fa);
            pane(c, RW, RROW - 8, 28, m == ARRANGE ? 1.25f : 1, fa, .18f);
            c.restore();
        }
        int from = Math.max(0, (int) (off / RROW) - 1), to = Math.min(n, from + RVIS + 2);
        for (int i = from; i < to; i++) {
            Item it = dock.get(i);
            float y = top + slid("r" + it.id, 0, i * RROW)[1] - off;
            float e = fa * Math.max(0, 1 - Math.abs(f - i * RROW) / RROW);
            float dim = m == ARRANGE && i != dockI ? .55f : 1;
            drawCard(c, card(it), it.label.toString(), LX, y + 8, 144, 81, 0, 0, dim);
            textStyle(medium, 34, -.6f, INK);
            text.setAlpha(Math.round((.82f + .18f * e) * dim * 255));
            if (e < .5f) photoShadow(1 - e);
            String name = TextUtils.ellipsize(it.label, text, RW - 220, TextUtils.TruncateAt.END).toString();
            c.drawText(name, LX + 176, baseline(y, RROW - 8), text);
            text.clearShadowLayer();
            text.setAlpha(255);
        }
        c.restore();
        if (m == ARRANGE) drawRemove(c, LX + RW / 2f - 20, top + Math.min(n, RVIS) * RROW + 16);
    }

    /**
     * The Icons layout: a column of app icons on the left. The focused one lifts like a dock
     * tile, and its name eases in beside it; with App names "always" every name shows, quieter.
     */
    private void drawList(Canvas c, int m) {
        int n = dock.size();
        float top = listTop();
        if (n == 0) {
            textStyle(medium, 30, -.3f, INK);
            photoShadow(1);
            c.drawText(t("Apps hinzufügen: Apps öffnen, Menütaste", "Add apps: open Apps, Menu button"),
                    LX, baseline(top, 40), text);
            text.clearShadowLayer();
            return;
        }
        float off = track.get(), f = focus.get(), fa = focusAlpha.get();
        c.save();
        // Room above and below for a lifted icon; rows scrolled out of the column stay hidden.
        c.clipRect(0, top - 26, W, top + lvis * lrow - (lrow - lh) + 26);
        int from = Math.max(0, (int) (off / lrow) - 1), to = Math.min(n, from + lvis + 2);
        int lifted = -1;
        float best = 0;
        for (int i = from; i < to; i++) {
            float e = fa * Math.max(0, 1 - Math.abs(f - i * lrow) / lrow);
            if (e > best) {
                best = e;
                lifted = i;
            }
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int i = from; i < to; i++) {
                if ((i == lifted) != (pass == 1)) continue;
                Item it = dock.get(i);
                float y = top + slid("l" + it.id, 0, i * lrow)[1] - off;
                float e = fa * Math.max(0, 1 - Math.abs(f - i * lrow) / lrow);
                float dim = m == ARRANGE && i != dockI ? .55f : 1;
                float extra = m == ARRANGE && i == dockI ? 1 : 0;
                drawCard(c, card(it), it.label.toString(), LX, y, lw, lh, e, extra, dim);
                float a = m == ARRANGE || !prefs.namesAlways ? 0 : .7f * (1 - e);
                if (a < .02f) continue;
                // The name sits beside the icon and eases in from a few pixels to the left.
                textStyle(medium, 30, -.4f, INK);
                text.setAlpha(Math.round(a * dim * 255));
                photoShadow(a);
                float x = LX + lw * (1 + (LIFT - 1) * e / 2) + 30 - 10 * (1 - e);
                String name = TextUtils.ellipsize(it.label, text, 620, TextUtils.TruncateAt.END).toString();
                c.drawText(name, x, baseline(y - 6 * e, lh), text);
                text.clearShadowLayer();
                text.setAlpha(255);
            }
        }
        c.restore();
        if (m == ARRANGE) {
            drawRemove(c, LX + lw / 2f, top + Math.min(n, lvis) * lrow);
        } else {
            drawShowcase(c, top, Math.min(n, lvis) * lrow - (lrow - lh));
        }
    }

    /**
     * The right side of the Icons layout: the focused app's name, large, with when it was last
     * opened and what the buttons do. A soft dark veil sits behind the block so it reads on any
     * photograph; moving focus cross-fades it. The photo's name sits small at the bottom right.
     */
    private void drawShowcase(Canvas c, float top, float height) {
        if (dock.isEmpty()) return;
        String id = dock.get(Math.min(dockI, dock.size() - 1)).id;
        if (!id.equals(heroId)) {
            // Fade out whatever name is showing right now, from where it is: the new name never
            // lies on top of the old one, and a quick second press does not bring a faded name back.
            float g = heroFade.get();
            if (heroId == null || g >= HERO_OUT) {
                heroPrevA = heroId == null ? 0 : heroInA(g);
                heroPrev = heroId;
            } else {
                heroPrevA = heroOutA(g);
            }
            heroId = id;
            heroFade.go(0, 0);
            heroFade.go(1, 300);
        }
        float f = heroFade.get(), show = .55f + .45f * focusAlpha.get();
        float x = 880, cy = Math.max(330, Math.min(H - 200, top + height / 2f));
        if (heroDim.getShader() == null || heroDim.getAlpha() != (photoLight ? 255 : 254)) {
            android.graphics.RadialGradient g = new android.graphics.RadialGradient(0, 0, 1,
                    Color.argb(photoLight ? 150 : 120, 4, 8, 10), Color.argb(0, 4, 8, 10), Shader.TileMode.CLAMP);
            heroDim.setShader(g);
            heroDim.setAlpha(photoLight ? 255 : 254);
        }
        Matrix mx = new Matrix();
        mx.setScale(820, 300);
        mx.postTranslate(x + 360, cy);
        heroDim.getShader().setLocalMatrix(mx);
        c.drawRect(x - 500, cy - 320, W, cy + 320, heroDim);
        // Out, then in, in place: moving text and two names on top of each other read as jitter.
        if (heroPrev != null && f < HERO_OUT) drawHeroText(c, heroPrev, x, cy, heroOutA(f) * show, 0);
        drawHeroText(c, heroId, x, cy, heroInA(f) * show, 0);

        textStyle(medium, 20, .2f, INK);
        text.setAlpha(205);
        text.setShadowLayer(8, 0, 1.5f, Color.argb(160, 4, 6, 8));
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText(Walls.name(wall), W - 150, baseline(H - 72, 28), text);
        text.clearShadowLayer();
        text.setAlpha(255);
    }

    private float heroOutA(float f) {
        return heroPrevA * Math.max(0, 1 - f / HERO_OUT);
    }

    private float heroInA(float f) {
        float t = Math.max(0, (f - HERO_OUT) / (1 - HERO_OUT));
        return t * t * (3 - 2 * t);
    }

    private void drawHeroText(Canvas c, String id, float x, float cy, float a, float dy) {
        if (a < .02f) return;
        Item it = null;
        for (Item d : dock) if (d.id.equals(id)) it = d;
        if (it == null) return;
        int shadowA = Math.round(170 * a);
        textStyle(medium, 100, -3f, INK);
        text.setAlpha(Math.round(a * 255));
        text.setShadowLayer(18, 0, 2, Color.argb(shadowA, 4, 6, 8));
        String name = TextUtils.ellipsize(it.label, text, 860, TextUtils.TruncateAt.END).toString();
        c.drawText(name, x, baseline(cy - 120 + dy, 120), text);
        float y = cy + 8 + dy;
        String when = lastOpened(it.id);
        if (when != null) {
            textStyle(regular, 28, 0, INK);
            text.setAlpha(Math.round(a * 240));
            text.setShadowLayer(10, 0, 1.5f, Color.argb(shadowA, 4, 6, 8));
            c.drawText(when, x + 4, baseline(y, 38), text);
            y += 46;
        }
        textStyle(regular, 23, 0, INK);
        text.setAlpha(Math.round(a * 200));
        text.setShadowLayer(10, 0, 1.5f, Color.argb(shadowA, 4, 6, 8));
        c.drawText(t("OK öffnet · Menütaste: Optionen", "OK opens · Menu button: options"), x + 4, baseline(y, 32), text);
        text.clearShadowLayer();
        text.setAlpha(255);
    }

    /** "Zuletzt geöffnet heute, 18:42", from opens through this home screen; null if never. */
    private String lastOpened(String id) {
        Long at = recent.get(id);
        if (at == null || at <= 0) return null;
        java.util.Calendar now = java.util.Calendar.getInstance(), then = java.util.Calendar.getInstance();
        then.setTimeInMillis(at);
        String time = new SimpleDateFormat(DateFormat.is24HourFormat(getContext()) ? "H:mm" : "h:mm a",
                Locale.getDefault()).format(new Date(at));
        int days = now.get(java.util.Calendar.DAY_OF_YEAR) - then.get(java.util.Calendar.DAY_OF_YEAR)
                + 365 * (now.get(java.util.Calendar.YEAR) - then.get(java.util.Calendar.YEAR));
        String day = days == 0 ? t("heute", "today") : days == 1 ? t("gestern", "yesterday")
                : new SimpleDateFormat(de ? "d. MMMM" : "MMMM d", Locale.getDefault()).format(new Date(at));
        return t("Zuletzt geöffnet ", "Last opened ") + day + ", " + time;
    }

    /**
     * One tile: the card, lifted by {@code e} (0 resting, 1 focused) with a soft shadow and a
     * diagonal sheen. {@code extra} lifts it further (a tile picked up to move).
     */
    private void drawCard(Canvas c, Card card, String name, float x, float y, float w, float h,
            float e, float extra, float dim) {
        float s = 1 + (LIFT - 1) * e + .03f * extra, lift = 6 * e + 8 * extra;
        c.save();
        c.translate(x + w / 2, y + h / 2 - lift);
        c.scale(s, s);
        c.translate(-w / 2, -h / 2);
        float r = 18 * (w / 300f);
        if (e > .01f) {
            shadow.setColor(Color.argb(1, 0, 0, 0));
            shadow.setShadowLayer(34 * e, 0, 20 * e, Color.argb(Math.round(150 * e * dim), 0, 0, 0));
            c.drawRoundRect(2, 4, w - 2, h - 2, r, r, shadow);
            shadow.clearShadowLayer();
        }
        if (card == null || !card.opaque) pane(c, w, h, r, .2f + .5f * e, dim, .12f);
        if (card != null) {
            matrix.setScale(w / CARD_W, h / CARD_H);
            card.shader.setLocalMatrix(matrix);
            image.setShader(card.shader);
            image.setAlpha(Math.round(255 * dim));
            c.drawRoundRect(0, 0, w, h, r, r, image);
            image.setShader(null);
            image.setAlpha(255);
        } else if (w > 160) {
            textStyle(semibold, 28, -.3f, INK);
            text.setTextAlign(Paint.Align.CENTER);
            text.setAlpha(Math.round(255 * dim));
            String n = TextUtils.ellipsize(name, text, w - 32, TextUtils.TruncateAt.END).toString();
            c.drawText(n, w / 2, baseline(h / 2 - 18, 36), text);
            text.setAlpha(255);
        }
        if (e > .01f) {
            sheen.setShader(sheenShader(w, h));
            sheen.setAlpha(Math.round(255 * e));
            c.drawRoundRect(0, 0, w, h, r, r, sheen);
        }
        c.restore();
    }

    /** linear-gradient(135deg, white .30, transparent 44%) over the tile. */
    private LinearGradient sheenShader(float w, float h) {
        String key = "sheen" + Math.round(w) + "x" + Math.round(h);
        LinearGradient g = shaders.get(key);
        if (g == null) {
            float d = .7071f, len = (w + h) * d, cx = w / 2, cy = h / 2;
            g = new LinearGradient(cx - d * len / 2, cy - d * len / 2, cx + d * len / 2, cy + d * len / 2,
                    new int[] {white(.30f), white(0)}, new float[] {0, .44f}, Shader.TileMode.CLAMP);
            shaders.put(key, g);
        }
        return g;
    }

    /** "Take out of the dock" under the picked-up tile, centred on {@code cx}. */
    private void drawRemove(Canvas c, float cx, float y) {
        String label = t("Aus dem Dock nehmen", "Take out of the dock");
        textStyle(medium, 25, -.3f, onAction ? SOLID_INK : INK);
        float w = text.measureText(label) + 56;
        if (onAction) {
            fill.setColor(SOLID);
            c.drawRoundRect(cx - w / 2, y, cx + w / 2, y + 60, 30, 30, fill);
        }
        text.setTextAlign(Paint.Align.CENTER);
        if (!onAction) {
            text.setAlpha(215);
            photoShadow(1);
        }
        c.drawText(label, cx, baseline(y, 60), text);
        text.clearShadowLayer();
        text.setAlpha(255);
    }

    private void drawApps(Canvas c, int m) {
        drawFrost(c);
        if (m == APPS_MOVE) title(c, t("Verschieben", "Move"), t("Pfeiltasten verschieben · OK fertig",
                "Arrows move · OK done"), false);
        else title(c, "Apps", t("Menütaste oder OK gedrückt halten: Optionen",
                "Menu button or hold OK: options"), false);
        float scroll = gScroll.get(), px = gx.get(), py = gy.get();
        c.save();
        c.clipRect(0, 196, W, H);
        c.translate(0, -scroll);
        int firstRow = Math.max(0, (int) (scroll / ROW) - 1), lastRow = firstRow + ROWS + 2;
        int focused = -1;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = firstRow * COLS; i < all.size() && i / COLS <= lastRow; i++) {
                Item it = all.get(i);
                float[] at = slid("g" + it.id, GX + (i % COLS) * GPITCH, GY + (i / COLS) * ROW);
                float x = at[0], y = at[1];
                float e = Math.max(0, 1 - Math.max(Math.abs(px - x) / GPITCH, Math.abs(py - y) / ROW));
                if (m == APPS_MOVE) e = i == appsI ? 1 : 0;
                if (pass == 0 && (m == APPS_MOVE ? i == appsI : e > .5f)) {
                    focused = i;
                    continue;
                }
                if (pass == 1 && i != focused) continue;
                float onScreen = y - scroll;
                float dim = onScreen < 200 || onScreen + GH > H - 20 ? .3f : 1;
                if (m == APPS_MOVE && i != appsI) dim *= .7f;
                drawCard(c, card(it), it.label.toString(), x, y, GW, GH, e, m == APPS_MOVE && i == appsI ? 1 : 0, dim);
                if (pinned(it)) {
                    float lift = 6 * e, s = 1 + (LIFT - 1) * e;
                    float cx = x + GW / 2f + (GW / 2f - 20) * s, cy = y + GH / 2f - lift - (GH / 2f - 18) * s;
                    // A soft dark halo keeps the dot visible on a white banner (Netflix).
                    fill.setColor(Color.argb(Math.round(70 * dim), 0, 0, 0));
                    c.drawCircle(cx, cy, 9, fill);
                    fill.setColor(INK);
                    fill.setAlpha(Math.round(245 * dim));
                    c.drawCircle(cx, cy, 5.5f, fill);
                }
                if (e > .02f) {
                    textStyle(medium, 26, -.3f, INK);
                    text.setTextAlign(Paint.Align.CENTER);
                    text.setAlpha(Math.round(e * dim * 255));
                    String name = TextUtils.ellipsize(it.label, text, GPITCH - 8, TextUtils.TruncateAt.END).toString();
                    c.drawText(name, x + GW / 2f, baseline(y + GH + 22, 32), text);
                    text.setAlpha(255);
                }
            }
        }
        c.restore();
    }

    // ---------------------------------------------------------------- settings

    private static final int R_WALL = 0, R_LAYOUT = 1, R_POSITION = 2, R_STYLE = 3, R_TILES = 4, R_ICONS = 5,
            R_GLASS = 6, R_CONTROLS = 7, R_NAMES = 8, R_CLOCK = 9, R_FONT = 10, R_TEXT = 11, R_DOCK = 12,
            R_AMAZON = 13, R_FIREHOME = 14, R_FIRE = 15, SET_ROWS = 20;
    /** First row of each section: home screen, type, apps, Fire TV. */
    private static final int[] SECTIONS = {R_WALL, R_FONT, R_DOCK, R_FIREHOME};
    private static final int[] SET_ICONS = {R.drawable.mk_art, R.drawable.ic_s_layout, R.drawable.ic_s_position,
            R.drawable.ic_s_style, R.drawable.ic_s_tiles, R.drawable.ic_s_mono, R.drawable.ic_s_glass, R.drawable.ic_s_buttons,
            R.drawable.ic_s_names, R.drawable.ic_s_clock, R.drawable.ic_s_font, R.drawable.ic_s_size,
            R.drawable.ic_s_dock, R.drawable.mk_apps, R.drawable.ic_s_firehome, R.drawable.ic_s_wifi,
            R.drawable.ic_s_display, R.drawable.ic_s_remote, R.drawable.mk_apps, R.drawable.ic_s_device};

    private String sectionName(int k) {
        switch (k) {
            case 0: return t("HOME-BILDSCHIRM", "HOME SCREEN");
            case 1: return t("SCHRIFT", "TYPE");
            case 2: return "APPS";
            default: return "FIRE TV";
        }
    }

    private String setLabel(int i) {
        switch (i) {
            case R_WALL: return t("Hintergrund", "Background");
            case R_LAYOUT: return "Layout";
            case R_POSITION: return "Position";
            case R_STYLE: return t("Dock-Stil", "Dock style");
            case R_TILES: return t("Kachelgröße", "Tile size");
            case R_ICONS: return t("Kacheln", "Tiles");
            case R_GLASS: return t("Glas", "Glass");
            case R_CONTROLS: return t("Knöpfe oben", "Top buttons");
            case R_NAMES: return t("App-Namen", "App names");
            case R_CLOCK: return t("Uhr", "Clock");
            case R_FONT: return t("Schriftart", "Typeface");
            case R_TEXT: return t("Schriftgröße", "Text size");
            case R_DOCK: return t("Apps im Dock", "Apps in the dock");
            case R_AMAZON: return t("Amazon-Apps", "Amazon apps");
            case R_FIREHOME: return t("Startbildschirm", "Home screen");
            case R_FIRE: return t("Netzwerk", "Network");
            case R_FIRE + 1: return t("Bildschirm und Ton", "Display and sound");
            case R_FIRE + 2: return t("Fernbedienungen und Bluetooth", "Remotes and Bluetooth");
            case R_FIRE + 3: return t("Apps verwalten", "Manage apps");
            default: return t("Mein Fire TV", "My Fire TV");
        }
    }

    private String size(int v) {
        return v == 0 ? t("Klein", "Small") : v == 2 ? t("Groß", "Large") : t("Mittel", "Medium");
    }

    private String setValue(int i) {
        switch (i) {
            case R_WALL: return Walls.name(wall);
            case R_LAYOUT: return prefs.layout == 2 ? "Icons" : prefs.layout == 1 ? t("Liste", "List") : "Dock";
            case R_POSITION: return prefs.position == 2 ? t("Oben", "Top")
                    : prefs.position == 1 ? t("Mitte", "Middle") : t("Unten", "Bottom");
            case R_STYLE: return prefs.style == 1 ? t("Ganze Breite", "Full width")
                    : prefs.style == 2 ? t("Ohne Leiste", "No shelf") : t("Glas-Leiste", "Glass shelf");
            case R_TILES: return size(prefs.tiles);
            case R_ICONS: return prefs.glassIcons ? t("Glas-Symbole", "Glass marks") : "Original";
            case R_GLASS: return prefs.material == 1 ? "Frost" : prefs.material == 2 ? t("Klar", "Clear") : t("Weich", "Soft");
            case R_CONTROLS: return prefs.controlText ? t("Symbol und Name", "Icon and name") : t("Nur Symbol", "Icon only");
            case R_NAMES: return prefs.namesAlways ? t("Immer", "Always") : t("Nur bei Fokus", "Focused only");
            case R_CLOCK: return prefs.showDate ? t("Mit Datum", "With date") : t("Nur Uhrzeit", "Time only");
            case R_FONT: return prefs.font == 1 ? "Newsreader" : prefs.font == 2 ? "System" : "Geist";
            case R_TEXT: return prefs.text == 0 ? t("Klein", "Small") : prefs.text == 2 ? t("Groß", "Large") : "Normal";
            case R_DOCK: return dock.size() + " Apps";
            case R_AMAZON: return prefs.amazonApps ? t("Alle", "All") : "Prime, Appstore, Silk";
            case R_FIREHOME: return fireHome ? "Fire TV" : t("Dieser", "This one");
            default: return null;
        }
    }

    private String setDesc(int i) {
        switch (i) {
            case R_WALL: return t("Das Foto hinter dem Home-Bildschirm. Das Glas nimmt seine Farben auf.",
                    "The photo behind the home screen. The glass takes on its colours.");
            case R_LAYOUT: return t("Dock: eine Reihe Kacheln. Liste: eine Spalte mit Bild und Namen. Icons: nur die Icons, der Name erscheint beim Fokus daneben.",
                    "Dock: a row of tiles. List: a column with picture and name. Icons: icons only, the name appears beside the focused one.");
            case R_POSITION: return t("Wo Dock oder Liste sitzen: unten, in der Mitte oder oben.",
                    "Where the dock or list sits: bottom, middle or top.");
            case R_STYLE: return t("Glas-Leiste um die Kacheln, eine Leiste über die ganze Breite, oder freie Kacheln.",
                    "A glass shelf around the tiles, a band across the screen, or tiles on their own.");
            case R_TILES: return t("Klein zeigt sechs Apps nebeneinander, Mittel fünf, Groß vier.",
                    "Small shows six apps side by side, medium five, large four.");
            case R_ICONS: return t("Original zeigt die Banner der Apps. Glas-Symbole zeigt weiße Logos auf klarem Glas, wie CleanFrame.",
                    "Original shows the apps' banners. Glass marks shows white logos on clear glass, as CleanFrame does.");
            case R_GLASS: return t("Weich ist ruhig, Frost stärker gestreut, Klar zeigt das Foto fast ungefiltert.",
                    "Soft is calm, Frost more diffused, Clear shows the photo almost as it is.");
            case R_CONTROLS: return t("Knöpfe oben rechts mit Namen, oder nur als Symbol.",
                    "The top right buttons with their names, or icons only.");
            case R_NAMES: return t("Der Name unter einer Dock-Kachel: nur bei der fokussierten oder bei allen.",
                    "The name under a dock tile: for the focused one only, or for all.");
            case R_CLOCK: return t("Die Uhrzeit oben links, mit oder ohne Datum.",
                    "The time at the top left, with or without the date.");
            case R_FONT: return t("Geist ist eine klare Grotesk, Newsreader eine ruhige Serifenschrift, System die Schrift von Fire TV.",
                    "Geist is a clean sans, Newsreader a calm serif, System Fire TV's own face.");
            case R_TEXT: return t("Die Größe aller Texte auf dem Home-Bildschirm.",
                    "The size of all text on the home screen.");
            case R_DOCK: return t("Welche Apps im Dock liegen. Öffnet die App-Übersicht.",
                    "Which apps sit in the dock. Opens the apps overview.");
            case R_AMAZON: return t("Amazons vorinstallierte Apps in der Übersicht: nur Prime Video, Appstore und Silk, oder alle.",
                    "Amazon's preinstalled apps in the overview: Prime Video, Appstore and Silk only, or all.");
            case R_FIREHOME: return t("OK wechselt zur normalen Fire-TV-Startseite. Zurück: diese App im Fire-TV-App-Menü öffnen und hier wieder umschalten.",
                    "OK switches to Fire TV's normal home screen. To return, open this app from Fire TV's apps and switch here.");
            case R_FIRE: return t("WLAN und Ethernet. Öffnet die Fire-TV-Einstellungen.",
                    "Wi-Fi and Ethernet. Opens Fire TV's settings.");
            case R_FIRE + 1: return t("Auflösung, Bildschirmschoner, Audio. Öffnet die Fire-TV-Einstellungen.",
                    "Resolution, screensaver, audio. Opens Fire TV's settings.");
            case R_FIRE + 2: return t("Fernbedienungen, Controller und Bluetooth-Geräte koppeln.",
                    "Pair remotes, game controllers and Bluetooth devices.");
            case R_FIRE + 3: return t("Apps deinstallieren, Speicher und Berechtigungen.",
                    "Uninstall apps, storage and permissions.");
            default: return t("Info, Speicher, Neustart und Entwickleroptionen.",
                    "About, storage, restart and developer options.");
        }
    }

    private static final float LIST_TOP = 196, LIST_BOTTOM = 1040, HEADER = 56;
    private static final int ROW_H = 76, ROW_GAP = 6;

    private static int sectionOf(int i) {
        int k = 0;
        for (int s = 0; s < SECTIONS.length; s++) if (i >= SECTIONS[s]) k = s;
        return k;
    }

    /** Top of row {@code i} in list coordinates, below the headers of its section and those before. */
    private static float rowTop(int i) {
        return HEADER + sectionOf(i) * (HEADER + 8) + i * (ROW_H + ROW_GAP);
    }

    private void placeRow(boolean animate) {
        int ms = animate ? MOVE_MS : 0;
        float row = rowTop(setI), off = listOff.to, view = LIST_BOTTOM - LIST_TOP;
        boolean opens = false;
        for (int s : SECTIONS) if (s == setI) opens = true;
        float top = row - (opens ? HEADER : ROW_GAP), bottom = row + ROW_H + 8;
        if (top < off) off = top;
        if (bottom - off > view) off = bottom - view;
        listOff.go(Math.max(0, off), ms);
        rowY.go(row, ms);
        invalidate();
    }

    private void drawSettings(Canvas c) {
        drawFrost(c);
        title(c, t("Einstellungen", "Settings"), null, false);

        c.save();
        c.translate(310, 330);
        pane(c, 340, 340, 84, 0, 1, 0);
        Drawable d = icon(SET_ICONS[setI]);
        d.setBounds(95, 95, 245, 245);
        d.setAlpha(235);
        d.draw(c);
        c.restore();
        textStyle(regular, 24, 0, INK);
        text.setAlpha(190);
        String d0 = setDesc(setI);
        StaticLayout desc = StaticLayout.Builder.obtain(d0, 0, d0.length(), text, 600)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0, 1.18f).build();
        c.save();
        c.translate(180, 720);
        desc.draw(c);
        c.restore();
        text.setAlpha(255);

        float lx = 960, lw = 810, off = listOff.get();
        c.save();
        c.clipRect(lx - 30, LIST_TOP - 10, lx + lw + 30, H);
        c.translate(0, LIST_TOP - off);
        for (int k = 0; k < SECTIONS.length; k++) {
            textStyle(semibold, 20, .6f, INK);
            text.setAlpha(140);
            c.drawText(sectionName(k), lx + 28, baseline(rowTop(SECTIONS[k]) - HEADER + 4, 40), text);
        }
        text.setAlpha(255);

        float fy = rowY.get();
        c.save();
        c.translate(lx, fy);
        c.scale(1.02f, 1.02f, lw / 2, ROW_H / 2f);
        softShadow(c, lw, ROW_H, 22, .85f);
        pane(c, lw, ROW_H, 22, 1, 1, 0);
        c.restore();

        for (int i = 0; i < SET_ROWS; i++) {
            float y = rowTop(i), e = Math.max(0, 1 - Math.abs(fy - y) / (ROW_H + ROW_GAP));
            textStyle(medium, 28, -.3f, INK);
            c.drawText(setLabel(i), lx + 28, baseline(y, ROW_H), text);
            float right = lx + lw - 28, cy = y + ROW_H / 2f;
            fill.setColor(INK);
            fill.setAlpha(Math.round((.5f + .3f * e) * 255));
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(2.5f);
            fill.setStrokeCap(Paint.Cap.ROUND);
            c.drawLine(right - 8, cy - 7, right - 1, cy, fill);
            c.drawLine(right - 1, cy, right - 8, cy + 7, fill);
            fill.setStyle(Paint.Style.FILL);
            String v = setValue(i);
            if (v != null) {
                textStyle(regular, 25, 0, INK);
                text.setAlpha(Math.round((.62f + .25f * e) * 255));
                text.setTextAlign(Paint.Align.RIGHT);
                c.drawText(v, right - 26, baseline(y, ROW_H), text);
                text.setAlpha(255);
            }
        }
        c.restore();
    }

    // ---------------------------------------------------------------- options menu

    private static final float MENU_W = 460, MENU_ROW = 70, MENU_PAD = 14, MENU_HEAD = 64;

    /** The options for one app: move, dock in or out, uninstall (installed apps only). */
    private void openMenu(Item it, RectF anchor) {
        menuFrom = mode;
        menuItem = it;
        menuAnchor.set(anchor);
        menuLabels.clear();
        menuActions.clear();
        boolean inDock = pinned(it);
        menuLabels.add(t("Verschieben", "Move"));
        menuActions.add(() -> {
            mode = menuFrom == HOME ? ARRANGE : APPS_MOVE;
            onAction = false;
            placeDock(true);
            invalidate();
        });
        menuLabels.add(inDock ? t("Aus dem Dock nehmen", "Take out of the dock") : t("Ins Dock legen", "Add to the dock"));
        menuActions.add(() -> {
            mode = menuFrom;
            togglePin(it);
            placeDock(false);
        });
        if (removable(it)) {
            menuLabels.add(t("Deinstallieren", "Uninstall"));
            menuActions.add(() -> {
                mode = menuFrom;
                invalidate();
                host.uninstall(it);
            });
        }
        mode = MENU;
        menuI = 0;
        menuY.go(0, 0);
        invalidate();
    }

    private static boolean removable(Item it) {
        return it.info != null && (it.info.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
    }

    private void drawMenu(Canvas c) {
        c.drawColor(Color.argb(90, 0, 0, 0));
        float h = MENU_HEAD + menuLabels.size() * MENU_ROW + MENU_PAD * 2;
        float x = Math.max(60, Math.min(W - MENU_W - 60, menuAnchor.centerX() - MENU_W / 2));
        float y = menuAnchor.bottom + 28;
        if (y + h > H - 40) y = menuAnchor.top - h - 28;
        y = Math.max(60, y);
        c.save();
        c.translate(x, y);
        softShadow(c, MENU_W, h, 32, 1);
        if (frostShader != null) {
            matrix.setScale(W / (float) frost.getWidth(), H / (float) frost.getHeight());
            matrix.postTranslate(-x, -y);
            frostShader.setLocalMatrix(matrix);
            glass.setShader(frostShader);
            glass.setAlpha(255);
            c.drawRoundRect(0, 0, MENU_W, h, 32, 32, glass);
            glass.setShader(null);
        }
        glass.setColor(Color.argb(150, 16, 18, 22));
        c.drawRoundRect(0, 0, MENU_W, h, 32, 32, glass);
        glass.setColor(white(.24f));
        c.drawPath(edge(MENU_W, h, 32), glass);

        textStyle(medium, 22, 0, INK);
        text.setAlpha(165);
        String name = TextUtils.ellipsize(menuItem == null ? "" : menuItem.label, text, MENU_W - 56,
                TextUtils.TruncateAt.END).toString();
        c.drawText(name, 28, baseline(MENU_PAD + 6, MENU_HEAD - 12), text);
        text.setAlpha(255);

        float py = MENU_PAD + MENU_HEAD + menuY.get();
        c.save();
        c.translate(MENU_PAD, py);
        pane(c, MENU_W - 2 * MENU_PAD, MENU_ROW, 22, 1, 1, 0);
        c.restore();
        for (int i = 0; i < menuLabels.size(); i++) {
            textStyle(medium, 27, -.3f, INK);
            c.drawText(menuLabels.get(i), MENU_PAD + 24, baseline(MENU_PAD + MENU_HEAD + i * MENU_ROW, MENU_ROW), text);
        }
        c.restore();
    }

    private void drawStatus(Canvas c) {
        if (status == null) return;
        long age = SystemClock.uptimeMillis() - statusAt;
        if (age > STATUS_MS) {
            status = null;
            return;
        }
        float a = age > STATUS_MS - 300 ? (STATUS_MS - age) / 300f : 1;
        textStyle(medium, 26, -.3f, INK);
        text.setAlpha(Math.round(a * 255));
        photoShadow(a);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(status, W / 2f, baseline(1012, 36), text);
        text.clearShadowLayer();
        text.setAlpha(255);
    }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getRepeatCount() == 0) {
                    longHandled = false;
                    okDown = e.getDownTime();
                } else if (!longHandled && e.getEventTime() - okDown >= LONG_PRESS_MS) {
                    longHandled = true;
                    menu();
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (e.getRepeatCount() == 0) menu();
                return true;
            case KeyEvent.KEYCODE_BACK:
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                move(0, 1);
                return true;
            default:
                return super.onKeyDown(code, e);
        }
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (!longHandled && !e.isCanceled()) ok();
                longHandled = false;
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!e.isCanceled()) back();
                return true;
            default:
                return super.onKeyUp(code, e);
        }
    }

    private void move(int dx, int dy) {
        switch (mode) {
            case HOME: {
                boolean list = prefs.layout != 0;
                // Up and down are a round trip between apps and controls: you come back to the
                // tile, or the control, you left.
                if (inCtl) {
                    if (dy > 0) inCtl = false;
                    else if (dx != 0) ctlI = Math.max(0, Math.min(CTL_ICONS.length - 1, ctlI + dx));
                } else if (list ? dy < 0 && dockI == 0 : dy < 0) {
                    inCtl = true;
                } else if (!dock.isEmpty()) {
                    int step = list ? dy : dx;
                    if (step != 0) dockI = Math.max(0, Math.min(dock.size() - 1, dockI + step));
                }
                placeDock(true);
                break;
            }
            case ARRANGE: {
                boolean list = prefs.layout != 0;
                int step = list ? dy : dx;
                if (!list && dy > 0 || list && dx > 0) onAction = true;
                else if (!list && dy < 0 || list && dx < 0) onAction = false;
                else if (!onAction && step != 0) {
                    int to = dockI + step;
                    if (to >= 0 && to < dock.size()) {
                        dock.add(to, dock.remove(dockI));
                        dockI = to;
                    }
                }
                placeDock(true);
                break;
            }
            case WALLS:
                if (dx != 0) {
                    int to = Math.max(0, Math.min(Walls.IDS.length - 1, wallI + dx));
                    if (to != wallI) {
                        wallI = to;
                        wall = to;
                        loadWall(to, false);
                    }
                    placeDock(true);
                }
                break;
            case APPS:
            case APPS_MOVE: {
                if (all.isEmpty()) break;
                int col = appsI % COLS, row = appsI / COLS, to = appsI;
                if (dx != 0) {
                    to = appsI + dx;
                    if (to < 0 || to >= all.size() || (mode == APPS && to / COLS != row)) to = appsI;
                } else if (dy != 0) {
                    to = (row + dy) * COLS + col;
                    if (dy > 0 && to >= all.size() && (row + 1) * COLS < all.size()) to = all.size() - 1;
                    if (to < 0 || to >= all.size()) to = appsI;
                }
                if (mode == APPS_MOVE && to != appsI) all.add(to, all.remove(appsI));
                appsI = to;
                placeGrid(true);
                break;
            }
            case SETTINGS:
                if (dy != 0) {
                    setI = Math.max(0, Math.min(SET_ROWS - 1, setI + dy));
                    placeRow(true);
                }
                break;
            case MENU:
                if (dy != 0) {
                    menuI = Math.max(0, Math.min(menuLabels.size() - 1, menuI + dy));
                    menuY.go(menuI * MENU_ROW, MOVE_MS);
                    invalidate();
                }
                break;
            default:
                break;
        }
    }

    private void ok() {
        switch (mode) {
            case HOME:
                if (inCtl) {
                    if (ctlI == 0) openApps(HOME);
                    else if (ctlI == 1) openWalls(HOME);
                    else openSettings();
                } else if (dock.isEmpty()) {
                    openApps(HOME);
                } else {
                    host.open(dock.get(dockI));
                }
                break;
            case ARRANGE:
                if (onAction) removeFocused();
                else dropTile();
                break;
            case WALLS:
                host.wallChanged(wall);
                leaveWalls();
                break;
            case APPS:
                if (!all.isEmpty()) host.open(all.get(appsI));
                break;
            case APPS_MOVE:
                finishMove();
                break;
            case SETTINGS:
                settingsOk();
                break;
            case MENU:
                if (menuI < menuActions.size()) menuActions.get(menuI).run();
                break;
            default:
                break;
        }
    }

    private int cycle(int v, int n) {
        return (v + 1) % n;
    }

    private void settingsOk() {
        switch (setI) {
            case R_WALL:
                openWalls(SETTINGS);
                break;
            case R_LAYOUT:
                prefs.layout = cycle(prefs.layout, 3);
                host.prefChanged(Prefs.LAYOUT, prefs.layout == 2 ? "icons" : prefs.layout == 1 ? "list" : "dock");
                slides.clear();
                break;
            case R_POSITION:
                prefs.position = cycle(prefs.position, 3);
                host.prefChanged(Prefs.POSITION, prefs.position == 2 ? "top" : prefs.position == 1 ? "middle" : "bottom");
                break;
            case R_STYLE:
                prefs.style = cycle(prefs.style, 3);
                host.prefChanged(Prefs.STYLE, prefs.style == 1 ? "full" : prefs.style == 2 ? "none" : "shelf");
                break;
            case R_TILES:
                prefs.tiles = cycle(prefs.tiles, 3);
                geometry();
                slides.clear();
                host.prefChanged(Prefs.TILES, prefs.tiles == 0 ? "small" : prefs.tiles == 2 ? "large" : "medium");
                break;
            case R_ICONS:
                prefs.glassIcons = !prefs.glassIcons;
                cards.clear();
                host.prefChanged(Prefs.ICONS, prefs.glassIcons ? "glass" : "banner");
                break;
            case R_GLASS:
                prefs.material = cycle(prefs.material, 3);
                host.prefChanged(Prefs.MATERIAL, prefs.material == 1 ? "frost" : prefs.material == 2 ? "clear" : "soft");
                break;
            case R_CONTROLS:
                prefs.controlText = !prefs.controlText;
                host.prefChanged(Prefs.CONTROLS, prefs.controlText ? "text" : "icons");
                break;
            case R_NAMES:
                prefs.namesAlways = !prefs.namesAlways;
                host.prefChanged(Prefs.NAMES, prefs.namesAlways ? "always" : "focus");
                break;
            case R_CLOCK:
                prefs.showDate = !prefs.showDate;
                host.prefChanged(Prefs.DATE, prefs.showDate ? "on" : "off");
                break;
            case R_FONT:
                prefs.font = cycle(prefs.font, 3);
                loadFonts();
                host.prefChanged(Prefs.FONT, prefs.font == 1 ? "newsreader" : prefs.font == 2 ? "system" : "geist");
                break;
            case R_TEXT:
                prefs.text = cycle(prefs.text, 3);
                host.prefChanged(Prefs.TEXT, prefs.text == 0 ? "small" : prefs.text == 2 ? "large" : "normal");
                break;
            case R_DOCK:
                openApps(SETTINGS);
                break;
            case R_AMAZON:
                prefs.amazonApps = !prefs.amazonApps;
                host.prefChanged(Prefs.AMAZON, prefs.amazonApps ? "all" : "few");
                break;
            case R_FIREHOME:
                fireHome = !fireHome;
                host.prefChanged(Prefs.HOME, fireHome ? "fire" : "glass");
                if (fireHome) host.useFireHome();
                else say(t("Dieser Startbildschirm ist wieder aktiv", "This home screen is active again"));
                break;
            default:
                host.fireSettings(setI - R_FIRE);
                break;
        }
        invalidate();
    }

    private void menu() {
        if (mode == HOME && !inCtl && !dock.isEmpty()) {
            RectF anchor;
            if (prefs.layout != 0) {
                float y = listTop() + (dockI - first) * rowPitch();
                anchor = new RectF(LX, y, LX + (prefs.layout == 1 ? RW : lw), y + rowHeight());
            } else {
                int v = Math.min(vis, dock.size());
                float x = (W - (v * tw + (v - 1) * gap)) / 2f + (dockI - first) * pitch, y = dockTop() + pad;
                anchor = new RectF(x, y, x + tw, y + th);
            }
            openMenu(dock.get(dockI), anchor);
        } else if (mode == ARRANGE) {
            dropTile();
        } else if (mode == APPS_MOVE) {
            finishMove();
        } else if (mode == APPS && !all.isEmpty()) {
            float x = GX + (appsI % COLS) * GPITCH, y = GY + (appsI / COLS) * ROW - gScroll.to;
            openMenu(all.get(appsI), new RectF(x, y, x + GW, y + GH));
        } else if (mode == MENU) {
            mode = menuFrom;
            invalidate();
        }
    }

    private void back() {
        switch (mode) {
            case MENU:
                mode = menuFrom;
                break;
            case ARRANGE:
                dropTile();
                break;
            case APPS_MOVE:
                finishMove();
                break;
            case WALLS:
                cancelWalls();
                break;
            case APPS:
                mode = appsFrom;
                placeDock(false);
                break;
            case SETTINGS:
                mode = HOME;
                placeDock(false);
                break;
            default:
                reset();
                break;
        }
        invalidate();
    }

    private void finishMove() {
        mode = APPS;
        host.orderChanged(new ArrayList<>(all));
        invalidate();
    }

    private void openApps(int from) {
        appsFrom = from;
        mode = APPS;
        appsI = 0;
        appsRow = 0;
        placeGrid(false);
        invalidate();
    }

    private void openSettings() {
        mode = SETTINGS;
        setI = 0;
        listOff.go(0, 0);
        placeRow(false);
    }

    private void openWalls(int from) {
        wallsFrom = from;
        mode = WALLS;
        wallBefore = wall;
        wallI = wall;
        first = Math.max(0, wallI - 2);
        loadThumbs();
        placeDock(false);
    }

    private void leaveWalls() {
        mode = wallsFrom;
        first = 0;
        placeDock(false);
    }

    private void cancelWalls() {
        if (wall != wallBefore) {
            wall = wallBefore;
            loadWall(wall, false);
        }
        leaveWalls();
    }

    private void dropTile() {
        mode = HOME;
        onAction = false;
        host.dockChanged(new ArrayList<>(dock));
        placeDock(true);
    }

    private void removeFocused() {
        Item gone = dock.remove(dockI);
        say(t(gone.label + " ist nicht mehr im Dock", gone.label + " left the dock"));
        dockI = Math.max(0, Math.min(dockI, dock.size() - 1));
        onAction = false;
        mode = HOME;
        host.dockChanged(new ArrayList<>(dock));
        placeDock(true);
    }

    private boolean pinned(Item it) {
        for (Item d : dock) if (d.id.equals(it.id)) return true;
        return false;
    }

    private void togglePin(Item it) {
        if (pinned(it)) {
            for (int i = dock.size() - 1; i >= 0; i--) {
                if (dock.get(i).id.equals(it.id)) dock.remove(i);
            }
            say(t(it.label + " ist nicht mehr im Dock", it.label + " left the dock"));
        } else {
            dock.add(it);
            say(t(it.label + " ist jetzt im Dock", it.label + " is in the dock"));
        }
        dockI = Math.max(0, Math.min(dockI, dock.size() - 1));
        host.dockChanged(new ArrayList<>(dock));
        invalidate();
    }
}
