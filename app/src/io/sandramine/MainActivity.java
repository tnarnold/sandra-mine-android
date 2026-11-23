package io.sandramine;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

/** Full-screen NerdMiner screens. ◀ ▶ (or a tap) change screen; OK / Menu opens the settings. */
public final class MainActivity extends Activity {
    private static final long EVERY = 1000, HINT = 5000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MinerView view;
    private TextView hint;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                view.update(new JSONObject(Native.status()));
            } catch (JSONException e) {
                // keep the last frame
            }
            main.postDelayed(this, EVERY);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        MinerService.ensure(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new MinerView(this);
        view.setScreen(getPreferences(MODE_PRIVATE).getInt("screen", MinerView.MINER));
        view.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_UP) step(e.getX() < v.getWidth() / 2f ? -1 : 1);
            return true;
        });
        view.setOnLongClickListener(v -> {
            settings();
            return true;
        });
        hint = new TextView(this);
        hint.setText(Strings.get(this, "hint"));
        hint.setTextColor(Color.argb(200, 255, 255, 255));
        hint.setTextSize(16);
        hint.setPadding(24, 12, 24, 12);
        hint.setBackgroundColor(Color.argb(140, 0, 0, 0));
        FrameLayout root = new FrameLayout(this);
        root.addView(view);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = 32;
        root.addView(hint, lp);
        setContentView(root);
        immersive();
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        hint.setVisibility(View.VISIBLE);
        main.postDelayed(() -> hint.setVisibility(View.GONE), HINT);
        main.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacksAndMessages(null);
    }

    private void step(int d) {
        view.setScreen(view.screen() + d);
        getPreferences(MODE_PRIVATE).edit().putInt("screen", view.screen()).apply();
    }

    private void settings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: step(-1); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: step(1); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SETTINGS:
                settings();
                return true;
            default:
                return super.onKeyDown(code, e);
        }
    }
}
