package io.sandramine;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The NerdMiner v2 T-Display screens (320x170), drawn like src/screens.rs in the desktop SandraMine:
 * the firmware's backgrounds, fonts and text coordinates (BitMaker-hub/NerdMiner_v2, MIT).
 */
final class MinerView extends View {
    static final int MINER = 0, CLOCK = 1, GLOBAL = 2, PRICE = 3, COUNT = 4;

    private static final int BLACK = Color.BLACK, WHITE = Color.WHITE;
    private static final int C_DEDB = Color.rgb(222, 219, 222), C_9C92 = Color.rgb(156, 146, 148);
    /** Pixel boxes (inclusive) of the "KH/s" baked into the backgrounds: x0, y0, x1, y1. */
    private static final int[] MINER_KHS = {135, 146, 160, 153}, SMALL_KHS = {102, 150, 128, 157};
    private static final int ALIGN_L = 0, ALIGN_C = 1, ALIGN_R = 2;
    private static final boolean TOP = false, MIDDLE = true;

    private final Bitmap[] bg = new Bitmap[COUNT];
    private final Bitmap init;
    private final Typeface digital, sans;
    /** egui sizes fonts by ascent-descent; Android by the em. Text size = egui size / ratio. */
    private final float digitalRatio, sansRatio;
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(), img = new Paint();
    private final Rect src = new Rect(0, 0, 320, 170);
    private final RectF dst = new RectF();
    private final SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.ROOT);

    private int screen = MINER;
    private JSONObject st = new JSONObject();
    private float s = 1, ox, oy;

    MinerView(Context c) {
        super(c);
        AssetManager a = c.getAssets();
        bg[MINER] = load(a, "miner", MINER_KHS);
        bg[CLOCK] = load(a, "clock", SMALL_KHS);
        bg[GLOBAL] = load(a, "global", null);
        bg[PRICE] = load(a, "price", SMALL_KHS);
        init = load(a, "init", null);
        digital = Typeface.createFromAsset(a, "fonts/DigitalNumbers.ttf");
        sans = Typeface.createFromAsset(a, "fonts/NotoSans_Bold.ttf");
        digitalRatio = ratio(digital);
        sansRatio = ratio(sans);
        img.setFilterBitmap(false); // pixel art, like TextureOptions::NEAREST
        setBackgroundColor(Color.BLACK);
    }

    private float ratio(Typeface t) {
        text.setTypeface(t);
        text.setTextSize(100);
        Paint.FontMetrics fm = text.getFontMetrics();
        return (fm.descent - fm.ascent) / 100f;
    }

    /** RGB565 little-endian, 320x170, with the fixed "KH/s" erased so the unit can follow the hashrate. */
    private static Bitmap load(AssetManager a, String name, int[] erase) {
        byte[] raw = new byte[320 * 170 * 2];
        try (InputStream in = a.open("screens/" + name + ".rgb565")) {
            int n = 0;
            while (n < raw.length) {
                int r = in.read(raw, n, raw.length - n);
                if (r < 0) break;
                n += r;
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        int[] px = new int[320 * 170];
        for (int i = 0; i < px.length; i++) {
            int v = (raw[2 * i] & 0xFF) | (raw[2 * i + 1] & 0xFF) << 8;
            int r = ((v >> 11) & 31) * 255 / 31, g = ((v >> 5) & 63) * 255 / 63, b = (v & 31) * 255 / 31;
            px[i] = Color.rgb(r, g, b);
        }
        if (erase != null) {
            for (int y = erase[1] - 1; y <= erase[3] + 1; y++) {
                int c = px[y * 320 + erase[0] - 3];
                for (int x = erase[0] - 2; x <= erase[2] + 2; x++) px[y * 320 + x] = c;
            }
        }
        return Bitmap.createBitmap(px, 320, 170, Bitmap.Config.ARGB_8888);
    }

    void setScreen(int n) {
        screen = ((n % COUNT) + COUNT) % COUNT;
        invalidate();
    }

    int screen() {
        return screen;
    }

    void update(JSONObject status) {
        st = status;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        s = Math.min(getWidth() / 320f, getHeight() / 170f);
        ox = (getWidth() - 320 * s) / 2;
        oy = (getHeight() - 170 * s) / 2;
        dst.set(ox, oy, ox + 320 * s, oy + 170 * s);
        if (!st.optBoolean("running")) {
            c.drawBitmap(init, src, dst, img);
            sans(c, "SandraMine", 24, 147, 11, BLACK, ALIGN_L, TOP);
            return;
        }
        c.drawBitmap(bg[screen], src, dst, img);
        switch (screen) {
            case MINER: miner(c); break;
            case CLOCK: clock(c); break;
            case GLOBAL: global(c); break;
            default: price(c); break;
        }
    }

    // --- Screens (coordinates from screens.rs)

    private void miner(Canvas c) {
        double h = st.optDouble("screen_hashrate", 0);
        String[] hr = hashrateParts(h);
        digital(c, hr[0], 118, 114, 35, BLACK, ALIGN_R);
        unit(c, hr[1], MINER_KHS);
        badge(c, 4, 102);
        double mhashes = st.optLong("total_hashes") / 1e6;
        digital(c, mhashes < 1e5 ? String.format(Locale.ROOT, "%.0f", mhashes) : si(mhashes), 268, 138, 18, BLACK, ALIGN_R);
        digital(c, String.valueOf(st.optLong("templates")), 186, 20, 18, C_DEDB, ALIGN_L);
        digital(c, bestDiff(st.optDouble("best_diff", 0)), 186, 48, 18, C_DEDB, ALIGN_L);
        digital(c, String.valueOf(st.optLong("shares")), 186, 76, 18, C_DEDB, ALIGN_L);
        long up = st.optLong("uptime_s");
        digital(c, String.format(Locale.ROOT, "%d  %02d:%02d:%02d", up / 86400, (up % 86400) / 3600, (up % 3600) / 60, up % 60),
                315, 104, 14, C_DEDB, ALIGN_R);
        digital(c, String.valueOf(st.optLong("blocks")), 285, 56, 24, C_DEDB, ALIGN_L);
        digital(c, now(), 286, 1, 10, BLACK, ALIGN_R);
        fill.setColor(st.optBoolean("connected") ? Color.rgb(40, 200, 90) : Color.rgb(220, 50, 50));
        fill.setAntiAlias(true);
        c.drawCircle(x(296), y(6), 2.2f * s, fill);
        if (st.optBoolean("paused")) sans(c, st.optString("paused_screen", "PAUSED"), 64, 124, 11, BLACK, ALIGN_C, MIDDLE);
    }

    private void clock(Canvas c) {
        small(c);
        sansFit(c, st.optString("price_screen"), 202, 3, 13, 82, BLACK);
        digital(c, height(), 254, 140, 18, BLACK, ALIGN_R);
        sans(c, now(), 130, 50, 50, C_DEDB, ALIGN_L, TOP);
    }

    private void global(Canvas c) {
        sansFit(c, st.optString("price_screen"), 198, 3, 13, 62, BLACK);
        sans(c, now(), 268, 3, 13, BLACK, ALIGN_L, TOP);
        String fee = st.isNull("fee_half_hour") ? "-- sat/vB" : String.format(Locale.ROOT, "%.0f sat/vB", st.optDouble("fee_half_hour"));
        sans(c, fee, 302, 52, 13, C_9C92, ALIGN_R, TOP);
        sans(c, st.isNull("net_difficulty") ? "--" : diffShort(st.optDouble("net_difficulty")), 302, 88, 13, C_9C92, ALIGN_R, TOP);
        String ghr = st.isNull("net_hashrate") ? "--" : String.format(Locale.ROOT, "%.2f", st.optDouble("net_hashrate") / 1e18);
        digital(c, ghr, 274, 145, 17, BLACK, ALIGN_R);
        digital(c, height(), 140, 104, 28, C_DEDB, ALIGN_R);
        final long halving = 210_000;
        long pct = 0;
        String remaining = "--";
        if (!st.isNull("block_height")) {
            long hgt = st.optLong("block_height");
            long rem = halving - hgt % halving;
            pct = (halving - rem) * 100 / halving;
            remaining = String.valueOf(rem);
        }
        // fill(2, 149, x2, 168) like screens.rs: width x2, height 168, clipped to the screen
        float x2 = 2 + 138 * pct / 100f;
        fill.setAntiAlias(false);
        fill.setColor(C_DEDB);
        c.drawRect(x(2), y(149), Math.min(x(2 + x2), dst.right), Math.min(y(149 + 168), dst.bottom), fill);
        sans(c, remaining + " BLOCKS", 72, 159, 11, BLACK, ALIGN_C, MIDDLE);
    }

    private void price(Canvas c) {
        small(c);
        digital(c, height(), 254, 138, 18, WHITE, ALIGN_R);
        sans(c, now(), 222, 3, 13, BLACK, ALIGN_L, TOP);
        sansFit(c, st.optString("price_screen"), 300, 58, 33, 190, C_DEDB);
    }

    /** The small hashrate panel shared by the clock and price screens. */
    private void small(Canvas c) {
        String[] hr = hashrateParts(st.optDouble("screen_hashrate", 0));
        digital(c, hr[0], 94, 129, 25, BLACK, ALIGN_R);
        unit(c, hr[1], SMALL_KHS);
        badge(c, 4, 117);
    }

    // --- Drawing helpers

    private float x(float dx) {
        return ox + dx * s;
    }

    private float y(float dy) {
        return oy + dy * s;
    }

    private void digital(Canvas c, String t, float dx, float dy, float size, int color, int align) {
        draw(c, t, dx, dy, size / digitalRatio, digital, color, align, TOP);
    }

    private void sans(Canvas c, String t, float dx, float dy, float size, int color, int align, boolean middle) {
        draw(c, t, dx, dy, size / sansRatio, sans, color, align, middle);
    }

    /** Like sans(), shrinking the text to at most maxW device px (long prices in other currencies). */
    private void sansFit(Canvas c, String t, float dx, float dy, float size, float maxW, int color) {
        text.setTypeface(sans);
        text.setTextSize(size / sansRatio * s);
        float w = text.measureText(t) / s;
        float fit = w > maxW ? size * maxW / w : size;
        sans(c, t, dx, dy + (size - fit) / 2, fit, color, ALIGN_L, TOP);
    }

    private void draw(Canvas c, String t, float dx, float dy, float em, Typeface face, int color, int align, boolean middle) {
        text.setTypeface(face);
        text.setTextSize(em * s);
        text.setColor(color);
        text.setTextAlign(align == ALIGN_L ? Paint.Align.LEFT : align == ALIGN_C ? Paint.Align.CENTER : Paint.Align.RIGHT);
        Paint.FontMetrics fm = text.getFontMetrics();
        float base = middle ? y(dy) - (fm.ascent + fm.descent) / 2 : y(dy) - fm.ascent;
        c.drawText(t, x(dx), base, text);
    }

    private void unit(Canvas c, String unit, int[] b) {
        float cx = (b[0] + b[2]) / 2f + 0.5f, cy = (b[1] + b[3]) / 2f + 0.5f;
        sans(c, unit, cx, cy, 11.5f, BLACK, ALIGN_C, MIDDLE);
    }

    /** "FROTA ×3" on the yellow panel when the hashrate is the fleet's sum. */
    private void badge(Canvas c, float dx, float dy) {
        if (st.isNull("machines")) return;
        String t = st.optString("fleet_badge", "FLEET") + " ×" + st.optInt("machines");
        text.setTypeface(sans);
        text.setTextSize(8 / sansRatio * s);
        Paint.FontMetrics fm = text.getFontMetrics();
        float w = text.measureText(t), h = fm.descent - fm.ascent, px = 3 * s, py = 1 * s;
        RectF r = new RectF(x(dx), y(dy), x(dx) + w + 2 * px, y(dy) + h + 2 * py);
        fill.setAntiAlias(true);
        fill.setColor(BLACK);
        c.drawRoundRect(r, 2 * s, 2 * s, fill);
        text.setColor(C_DEDB);
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText(t, r.left + px, r.top + py - fm.ascent, text);
    }

    // --- Formatting (same as screens.rs / fmt.rs)

    private String now() {
        return hm.format(new Date());
    }

    private String height() {
        return st.isNull("block_height") ? "--" : String.valueOf(st.optLong("block_height"));
    }

    static String[] hashrateParts(double h) {
        double k = h / 1000;
        if (k < 1000) return new String[] {String.format(Locale.ROOT, "%.2f", k), "KH/s"};
        if (k < 1e6) return new String[] {String.format(Locale.ROOT, "%.2f", k / 1e3), "MH/s"};
        return new String[] {String.format(Locale.ROOT, "%.2f", k / 1e6), "GH/s"};
    }

    static String bestDiff(double d) {
        if (d < 1000) return String.format(Locale.ROOT, d < 10 ? "%.3f" : "%.1f", d);
        return diffShort(d);
    }

    static String diffShort(double d) {
        double[] v = {1e18, 1e15, 1e12, 1e9, 1e6, 1e3};
        String[] suf = {"E", "P", "T", "G", "M", "K"};
        for (int i = 0; i < v.length; i++) {
            if (d >= v[i]) {
                double x = d / v[i];
                return String.format(Locale.ROOT, x >= 100 ? "%.0f%s" : x >= 10 ? "%.1f%s" : "%.2f%s", x, suf[i]);
            }
        }
        return String.format(Locale.ROOT, "%.0f", d);
    }

    private static String si(double v) {
        String[] suf = {"", "K", "M", "G", "T", "P"};
        int i = 0;
        while (v >= 1000 && i < suf.length - 1) {
            v /= 1000;
            i++;
        }
        return String.format(Locale.ROOT, "%.1f%s", v, suf[i]);
    }
}
