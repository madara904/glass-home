package app.glasshome.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/**
 * CleanFrame's fifteen photographs (Unsplash License, credits in assets/wall-CREDITS.md). Each
 * has the photo, a "soft" material for the dock and a "frost" material for full-screen sheets.
 * Both materials are blurred ahead of time on the same centred 1920 x 1080 crop as the photo, so
 * the dock only has to show its own part of them: no blur runs on the TV.
 *
 * After them comes the owner's own photo, when there is one: a picture in the wallpapers folder
 * next to home.conf, which the Glass Home tool puts there. Its materials are made on the TV,
 * once, when it loads.
 */
final class Walls {
    static final String[] IDS = {"misty", "cove", "forest", "valley", "night", "peaks", "bridge",
            "ridge", "golden", "summit", "goldcoast", "sydney", "amber", "mint", "alpine"};
    /** The owner's own photo comes after the fifteen. */
    static final int CUSTOM = IDS.length;
    static final String CUSTOM_ID = "custom";
    /** The name the Glass Home tool gives the photo it uploads. */
    static final String CUSTOM_FILE = "glass-home";
    private static final String[] EN = {"Misty coast", "Quiet cove", "Forest mist", "Mountain valley",
            "Desert stars", "Blue peaks", "Bridge at dusk", "Sunlit ridges", "Golden hour",
            "Sunlit summits", "Gold Coast dusk", "Sydney Harbour", "Amber hills", "Mint wave", "Alpine glow"};
    private static final String[] DE = {"Nebelküste", "Stille Bucht", "Waldnebel", "Bergtal",
            "Wüstensterne", "Blaue Gipfel", "Brücke am Abend", "Sonnige Grate", "Goldene Stunde",
            "Gipfel im Licht", "Gold Coast am Abend", "Sydney Harbour", "Bernsteinhügel", "Mintwelle",
            "Alpenglühen"};
    /** "light" photos get the stronger top shade, as in CleanFrame. */
    private static final boolean[] LIGHT = {true, true, false, false, true, false, false, false,
            false, false, true, true, true, true, true};
    /** The veil CleanFrame lays over the frost material, per photo. */
    private static final float[] FROST_VEIL = {.20f, .12f, .12f, .12f, .20f, .36f, .44f, .12f, .30f,
            .20f, .20f, .44f, .20f, .44f, .20f};

    private Walls() {}

    static int index(String id) {
        if (CUSTOM_ID.equals(id)) return CUSTOM;
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i;
        return 0;
    }

    static String id(int i) {
        return i == CUSTOM ? CUSTOM_ID : IDS[i];
    }

    /** How many wallpapers there are to choose from: the fifteen, plus the own photo if there is one. */
    static int count(Context c) {
        return IDS.length + (customFile(c) != null ? 1 : 0);
    }

    static String name(int i) {
        boolean de = "de".equals(Locale.getDefault().getLanguage());
        if (i == CUSTOM) return de ? "Eigenes Foto" : "Your photo";
        return de ? DE[i] : EN[i];
    }

    static boolean light(int i) {
        return i != CUSTOM && LIGHT[i];
    }

    static float frostVeil(int i) {
        return i == CUSTOM ? .24f : FROST_VEIL[i];
    }

    /** The photo at full size; draw it with {@link #crop} for CleanFrame's centred crop. */
    static Bitmap photo(Context c, int i) {
        if (i == CUSTOM) return customPhoto(c);
        return decode(c, "wall-" + IDS[i] + ".jpg", 1);
    }

    /** A material at half size: it is blurred, so scaling it back up loses nothing visible. */
    static Bitmap soft(Context c, int i) {
        return decode(c, "wall-" + IDS[i] + "-soft.jpg", 2);
    }

    static Bitmap frost(Context c, int i) {
        return decode(c, "wall-" + IDS[i] + "-frost.jpg", 2);
    }

    static Bitmap thumb(Context c, int i) {
        if (i == CUSTOM) {
            Bitmap p = customPhoto(c);
            return p == null ? null : Bitmap.createScaledBitmap(p, 480, Math.max(1, 480 * p.getHeight() / p.getWidth()), true);
        }
        return decode(c, "wall-" + IDS[i] + "-thumb.jpg", 2);
    }

    /**
     * The own photo: the tool's upload when it is there, otherwise the newest picture in the
     * wallpapers folders. Null when there is none.
     */
    static File customFile(Context c) {
        List<File> all = Config.get(c).wallpapers();
        File newest = null;
        for (File f : all) {
            if (f.getName().toLowerCase(Locale.ROOT).startsWith(CUSTOM_FILE + ".")) return f;
            if (newest == null || f.lastModified() > newest.lastModified()) newest = f;
        }
        return newest;
    }

    /** The own photo, decoded no larger than needed for a 1920 x 1080 screen. */
    static Bitmap customPhoto(Context c) {
        File f = customFile(c);
        if (f == null) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= 1920 && bounds.outHeight / (sample * 2) >= 1080) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        try {
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    /**
     * A blurred material from {@code photo}'s centred crop: drawn at 240 x 135, blurred there with
     * three box passes each way (close to a Gaussian, so no blocks), then scaled to 480 x 270, the
     * size the shipped materials have after their half-size decode. {@code radius} is in those
     * 240 px: about 6 for the dock's soft glass, 12 for the frosted sheets.
     */
    static Bitmap blurred(Bitmap photo, int radius) {
        if (photo == null) return null;
        int w = 240, h = 135;
        Bitmap small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        new Canvas(small).drawBitmap(photo, crop(photo), new Rect(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG));
        int[] px = new int[w * h], tmp = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(px, tmp, w, h, radius, true);
            boxBlur(tmp, px, w, h, radius, false);
        }
        small.setPixels(px, 0, w, 0, 0, w, h);
        Bitmap out = Bitmap.createScaledBitmap(small, 480, 270, true);
        small.recycle();
        return out;
    }

    /** One box-blur pass along rows ({@code across}) or columns, edges clamped. */
    private static void boxBlur(int[] in, int[] out, int w, int h, int r, boolean across) {
        int lines = across ? h : w, len = across ? w : h, n = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            int a = 0, red = 0, g = 0, b = 0;
            for (int k = -r; k <= r; k++) {
                int c = in[index(l, Math.max(0, Math.min(len - 1, k)), w, across)];
                a += c >>> 24; red += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255;
            }
            for (int i = 0; i < len; i++) {
                out[index(l, i, w, across)] = ((a / n) << 24) | ((red / n) << 16) | ((g / n) << 8) | (b / n);
                int add = in[index(l, Math.min(len - 1, i + r + 1), w, across)];
                int sub = in[index(l, Math.max(0, i - r), w, across)];
                a += (add >>> 24) - (sub >>> 24);
                red += ((add >> 16) & 255) - ((sub >> 16) & 255);
                g += ((add >> 8) & 255) - ((sub >> 8) & 255);
                b += (add & 255) - (sub & 255);
            }
        }
    }

    private static int index(int line, int i, int w, boolean across) {
        return across ? line * w + i : i * w + line;
    }

    /** The centred 16:9 part of {@code b}, like CSS object-fit: cover. */
    static android.graphics.Rect crop(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        if (w * 9 > h * 16) {
            int cw = h * 16 / 9;
            return new android.graphics.Rect((w - cw) / 2, 0, (w + cw) / 2, h);
        }
        int ch = w * 9 / 16;
        return new android.graphics.Rect(0, (h - ch) / 2, w, (h + ch) / 2);
    }

    private static Bitmap decode(Context c, String path, int sample) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        try (InputStream in = c.getAssets().open(path)) {
            return BitmapFactory.decodeStream(in, null, o);
        } catch (IOException | OutOfMemoryError e) {
            return null;
        }
    }
}
