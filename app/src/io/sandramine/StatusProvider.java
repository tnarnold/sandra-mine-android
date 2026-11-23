package io.sandramine;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Read-only status for other apps (the Free My TV home card):
 * content://io.sandramine.status/status -> one row, column "json" (see status() in rust/src/lib.rs).
 * content://io.sandramine.status/screen.png[?screen=0..3&width=640] -> the NerdMiner screen as a PNG
 * (0 miner, 1 clock, 2 network, 3 price), for cards that just want to show the screen.
 * Never includes the wallet address. Starts the miner if it isn't running.
 */
public final class StatusProvider extends ContentProvider {
    private MinerView view;
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MinerService.ensure(getContext());
        MatrixCursor c = new MatrixCursor(new String[] {"json"}, 1);
        c.addRow(new Object[] {Native.status()});
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return "screen.png".equals(uri.getLastPathSegment()) ? "image/png" : "application/json";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"screen.png".equals(uri.getLastPathSegment()) || !mode.equals("r")) throw new FileNotFoundException(uri.toString());
        MinerService.ensure(getContext());
        Bitmap png = render(param(uri, "screen", MinerView.MINER), Math.max(160, Math.min(1920, param(uri, "width", 640))));
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            new Thread(() -> {
                try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    png.compress(Bitmap.CompressFormat.PNG, 100, out);
                } catch (IOException ignored) {
                    // the reader went away
                }
            }, "screen-png").start();
            return pipe[0];
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    private static int param(Uri uri, String key, int fallback) {
        try {
            return Integer.parseInt(uri.getQueryParameter(key));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** Draws one screen off-screen with the current status. */
    private synchronized Bitmap render(int screen, int width) {
        if (view == null) view = new MinerView(getContext());
        int height = Math.round(width * 170f / 320f);
        try {
            view.update(new JSONObject(Native.status()));
        } catch (JSONException e) {
            // draws the loading screen
        }
        view.setScreen(screen);
        view.layout(0, 0, width, height);
        Bitmap b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(b));
        return b;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only");
    }
}
