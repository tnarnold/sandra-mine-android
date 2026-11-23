package io.sandramine;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Settings that work with a TV remote: plain focusable buttons and text fields. */
public final class SettingsActivity extends Activity {
    private static final int BG = Color.rgb(16, 16, 20), FG = Color.rgb(230, 230, 230), DIM = Color.rgb(150, 150, 160);
    private static final String[] MODES = {"auto", "local", "fleet"};
    private static final String[] MODE_KEYS = {"fleet_auto", "fleet_local", "fleet_all"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status, engine, shares, fleet, threadsValue, cpuValue;
    private static final String[] LANGS = {"auto", "pt-BR", "en"};
    private Button pause, mode, lang;
    private EditText address, worker, host, port;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            main.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        MinerService.ensure(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(48), dp(32), dp(48), dp(32));
        col.setBackgroundColor(BG);

        col.addView(label(Strings.get(this, "settings"), 26, FG));
        status = col(col, label("", 18, FG));
        engine = col(col, label("", 14, DIM));
        shares = col(col, label("", 14, DIM));

        pause = button("", v -> {
            boolean paused = !json(Native.status()).optBoolean("paused");
            patch("start_paused", paused);
        });
        col.addView(pause);

        threadsValue = label("", 18, FG);
        col.addView(stepper(Strings.get(this, "threads"), threadsValue, d -> {
            int n = json(Native.status()).optInt("threads", 1) + d;
            patch("cpu_threads", Math.max(1, Math.min(Native.maxThreads(), n)));
        }));
        cpuValue = label("", 18, FG);
        col.addView(stepper(Strings.get(this, "cpu"), cpuValue, d -> {
            int n = json(Native.status()).optInt("cpu_usage", 50) + 10 * d;
            patch("cpu_usage", Math.max(10, Math.min(100, n)));
        }));

        col.addView(label(Strings.get(this, "fleet_mode"), 14, DIM));
        mode = button("", v -> {
            String cur = json(Native.status()).optString("fleet_mode", "auto");
            int i = 0;
            while (i < MODES.length && !MODES[i].equals(cur)) i++;
            patch("fleet_mode", MODES[(i + 1) % MODES.length]);
        });
        col.addView(mode);
        fleet = col(col, label("", 14, DIM));

        col.addView(label(Strings.get(this, "language"), 14, DIM));
        lang = button("", v -> {
            String cur = json(Native.config()).optString("language", "auto");
            int i = 0;
            while (i < LANGS.length && !LANGS[i].equals(cur)) i++;
            patch("language", LANGS[(i + 1) % LANGS.length]);
            recreate(); // texts in the new language
        });
        col.addView(lang);

        col.addView(label(Strings.get(this, "pool_section"), 20, FG));
        JSONObject cfg = json(Native.config());
        address = field(col, "address", cfg.optString("btc_address"), InputType.TYPE_CLASS_TEXT);
        worker = field(col, "worker", cfg.optString("worker_name"), InputType.TYPE_CLASS_TEXT);
        host = field(col, "pool", cfg.optString("pool_host"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        port = field(col, "port", String.valueOf(cfg.optInt("pool_port", 21496)), InputType.TYPE_CLASS_NUMBER);
        col.addView(button(Strings.get(this, "save"), v -> savePool()));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.addView(col);
        setContentView(scroll);
        pause.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        main.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacksAndMessages(null);
    }

    private void refresh() {
        JSONObject s = json(Native.status());
        String code = s.optString("pool_status", "disconnected");
        String detail = s.optString("pool_detail");
        String line = code.equals("connected") || code.equals("connecting") || code.equals("error")
                ? Strings.fmt(this, code, detail) : Strings.get(this, code);
        String[] hr = MinerView.hashrateParts(s.optDouble("hashrate", 0));
        status.setText(hr[0] + " " + hr[1] + "  ·  " + line);
        engine.setText(Strings.fmt(this, "engine", Strings.get(this, s.optBoolean("sha_hw") ? "yes" : "no"),
                Strings.get(this, s.optBoolean("low_priority") ? "yes" : "no")));
        shares.setText(Strings.fmt(this, "shares", s.optLong("accepted"), s.optLong("rejected")));
        pause.setText(Strings.get(this, s.optBoolean("paused") ? "resume" : "pause"));
        threadsValue.setText(s.optInt("threads") + " / " + s.optInt("max_threads"));
        cpuValue.setText(s.optInt("cpu_usage") + "%");
        String m = s.optString("fleet_mode", "auto");
        for (int i = 0; i < MODES.length; i++) if (MODES[i].equals(m)) mode.setText(Strings.get(this, MODE_KEYS[i]));
        fleet.setText(fleetText(s));
        String l = json(Native.config()).optString("language", "auto");
        lang.setText(l.equals("pt-BR") ? "Português (Brasil)" : l.equals("en") ? "English" : Strings.get(this, "lang_auto"));
    }

    private String fleetText(JSONObject s) {
        JSONObject f = s.optJSONObject("fleet");
        if (f == null) return Strings.get(this, "fleet") + ": " + Strings.get(this, "fleet_waiting");
        double total = s.optDouble("hashrate", 0) + f.optDouble("others", 0);
        String[] t = MinerView.hashrateParts(total);
        StringBuilder b = new StringBuilder(Strings.get(this, "fleet")).append(": ")
                .append(Strings.fmt(this, "fleet_total", f.optInt("machines"), t[0] + " " + t[1]));
        JSONArray ws = f.optJSONArray("workers");
        for (int i = 0; ws != null && i < ws.length(); i++) {
            JSONObject w = ws.optJSONObject(i);
            boolean me = w.optBoolean("me");
            String[] h = MinerView.hashrateParts(me ? s.optDouble("hashrate", 0) : w.optDouble("hashrate", 0));
            b.append("\n  • ").append(w.optString("name")).append(me ? Strings.get(this, "this") : "")
                    .append(" — ").append(h[0]).append(' ').append(h[1]);
        }
        return b.toString();
    }

    private void savePool() {
        JSONObject p = new JSONObject();
        try {
            p.put("btc_address", address.getText().toString().trim());
            p.put("worker_name", worker.getText().toString().trim());
            p.put("pool_host", host.getText().toString().trim());
            p.put("pool_port", Integer.parseInt(port.getText().toString().trim()));
        } catch (JSONException | NumberFormatException e) {
            return;
        }
        if (Native.apply(p.toString())) Toast.makeText(this, Strings.get(this, "saved"), Toast.LENGTH_SHORT).show();
    }

    private void patch(String key, Object value) {
        JSONObject p = new JSONObject();
        try {
            p.put(key, value);
        } catch (JSONException e) {
            return;
        }
        Native.apply(p.toString());
        refresh();
    }

    // --- Small view helpers

    private interface Step {
        void by(int d);
    }

    private static JSONObject json(String s) {
        try {
            return new JSONObject(s);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static TextView col(LinearLayout parent, TextView t) {
        parent.addView(t);
        return t;
    }

    private TextView label(String text, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout stepper(String name, TextView value, Step step) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView n = label(name, 16, DIM);
        n.setMinWidth(dp(240));
        row.addView(n);
        row.addView(button("−", v -> step.by(-1)));
        value.setMinWidth(dp(90));
        value.setGravity(Gravity.CENTER);
        row.addView(value);
        row.addView(button("+", v -> step.by(1)));
        return row;
    }

    private EditText field(LinearLayout parent, String key, String value, int type) {
        parent.addView(label(Strings.get(this, key), 14, DIM));
        EditText e = new EditText(this);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(type);
        e.setTextColor(FG);
        parent.addView(e);
        return e;
    }
}
