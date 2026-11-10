package app.glasshome.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * CleanFrame's fifteen photographs (Unsplash License, credits in assets/wall-CREDITS.md). Each
 * has the photo, a "soft" material for the dock and a "frost" material for full-screen sheets.
 * Both materials are blurred ahead of time on the same centred 1920 x 1080 crop as the photo, so
 * the dock only has to show its own part of them: no blur runs on the TV.
 */
final class Walls {
    static final String[] IDS = {"misty", "cove", "forest", "valley", "night", "peaks", "bridge",
            "ridge", "golden", "summit", "goldcoast", "sydney", "amber", "mint", "alpine"};
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
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i;
        return 0;
    }

    static String name(int i) {
        return "de".equals(Locale.getDefault().getLanguage()) ? DE[i] : EN[i];
    }

    static boolean light(int i) {
        return LIGHT[i];
    }

    static float frostVeil(int i) {
        return FROST_VEIL[i];
    }

    /** The photo at full size; draw it with {@link #crop} for CleanFrame's centred crop. */
    static Bitmap photo(Context c, int i) {
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
        return decode(c, "wall-" + IDS[i] + "-thumb.jpg", 2);
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
