package com.uliptracker;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Profile pictures for you ("Me") and each family member: a photo cut into a circle, an emoji on a coloured
 * circle, or the first letter of the name. Stored in settings (emoji) and app storage (photos).
 */
public final class Avatars {

    private Avatars() { }

    public static final String[] EMOJIS = {"😀", "😎", "🤓", "🥳", "😇", "🧑‍💼", "👩‍💼", "👨‍💼", "👵", "👴", "👧", "👦",
            "🦁", "🐯", "🐼", "🦊", "🐨", "🐘", "🦚", "🐬", "🚀", "💎", "🌟", "🪷"};
    private static final int[] BG = {0xFF4F7CFF, 0xFF8B5CF6, 0xFFE0517A, 0xFF0EA5A4, 0xFFF59E0B, 0xFF10B981, 0xFFEF6C3A, 0xFF64748B};
    private static final Map<String, Bitmap> cache = new HashMap<>();

    private static String key(String owner) {
        String o = owner == null || owner.trim().isEmpty() ? "me" : owner.trim().toLowerCase(Locale.ROOT);
        return "av_" + o.replaceAll("[^a-z0-9]+", "_");
    }

    private static SharedPreferences prefs(Context c) { return Alerts.prefs(c); }

    private static File photo(Context c, String owner) { return new File(c.getFilesDir(), key(owner) + ".png"); }

    public static String emoji(Context c, String owner) {
        String v = prefs(c).getString(key(owner), "");
        return v.startsWith("emoji:") ? v.substring(6) : null;
    }

    public static boolean hasPhoto(Context c, String owner) {
        return "photo".equals(prefs(c).getString(key(owner), "")) && photo(c, owner).exists();
    }

    /** True if the person chose a photo or an emoji. */
    public static boolean isSet(Context c, String owner) { return emoji(c, owner) != null || hasPhoto(c, owner); }

    public static void setEmoji(Context c, String owner, String e) {
        prefs(c).edit().putString(key(owner), "emoji:" + e).apply();
        photo(c, owner).delete();
        cache.clear();
    }

    public static void clear(Context c, String owner) {
        prefs(c).edit().remove(key(owner)).apply();
        photo(c, owner).delete();
        cache.clear();
    }

    /** Moves a picture to a person's new name. */
    public static void rename(Context c, String from, String to) {
        if (from.equalsIgnoreCase(to)) return;
        String v = prefs(c).getString(key(from), null);
        if (v == null) return;
        prefs(c).edit().putString(key(to), v).remove(key(from)).apply();
        File f = photo(c, from);
        if (f.exists()) f.renameTo(photo(c, to));
        cache.clear();
    }

    /** Reads an image, crops the centre square and saves it at 320 px. */
    public static void setPhoto(Context c, String owner, Uri uri) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
        int s = 1;
        while (Math.min(o.outWidth, o.outHeight) / (s * 2) >= 320) s *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = s;
        Bitmap b;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) { b = BitmapFactory.decodeStream(in, null, o); }
        if (b == null) throw new IllegalArgumentException("Not an image");
        b = rotateFromExif(c, uri, b);
        int side = Math.min(b.getWidth(), b.getHeight());
        Bitmap sq = Bitmap.createBitmap(b, (b.getWidth() - side) / 2, (b.getHeight() - side) / 2, side, side);
        Bitmap out = Bitmap.createScaledBitmap(sq, 320, 320, true);
        try (FileOutputStream f = new FileOutputStream(photo(c, owner))) { out.compress(Bitmap.CompressFormat.PNG, 100, f); }
        prefs(c).edit().putString(key(owner), "photo").apply();
        cache.clear();
    }

    private static Bitmap rotateFromExif(Context c, Uri uri, Bitmap b) {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            android.media.ExifInterface ex = new android.media.ExifInterface(in);
            int o = ex.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
            int deg = o == 6 ? 90 : o == 3 ? 180 : o == 8 ? 270 : 0;
            if (deg == 0) return b;
            Matrix m = new Matrix();
            m.postRotate(deg);
            return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        } catch (Throwable t) {
            return b;
        }
    }

    /** Round avatar bitmap, `px` wide. */
    public static Bitmap bitmap(Context c, String owner, int px) {
        String ck = key(owner) + "@" + px;
        Bitmap hit = cache.get(ck);
        if (hit != null) return hit;
        Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        float r = px / 2f;
        Bitmap ph = hasPhoto(c, owner) ? BitmapFactory.decodeFile(photo(c, owner).getPath()) : null;
        if (ph != null) {
            BitmapShader sh = new BitmapShader(ph, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale((float) px / ph.getWidth(), (float) px / ph.getHeight());
            sh.setLocalMatrix(m);
            p.setShader(sh);
            cv.drawCircle(r, r, r, p);
        } else {
            String name = owner == null || owner.trim().isEmpty() ? "Me" : owner.trim();
            p.setColor(BG[Math.abs(name.toLowerCase(Locale.ROOT).hashCode()) % BG.length]);
            cv.drawCircle(r, r, r, p);
            String e = emoji(c, owner);
            String label = e != null ? e : name.substring(0, 1).toUpperCase(Locale.ROOT);
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setColor(0xFFFFFFFF);
            t.setTextAlign(Paint.Align.CENTER);
            t.setTextSize(px * (e != null ? 0.56f : 0.46f));
            if (e == null) t.setFakeBoldText(true);
            Rect tb = new Rect();
            t.getTextBounds(label, 0, label.length(), tb);
            cv.drawText(label, r, r - tb.exactCenterY(), t);
        }
        cache.put(ck, out);
        return out;
    }

    public static Drawable drawable(Context c, String owner, int px) {
        BitmapDrawable d = new BitmapDrawable(c.getResources(), bitmap(c, owner, px));
        d.setBounds(0, 0, px, px);
        return d;
    }
}
