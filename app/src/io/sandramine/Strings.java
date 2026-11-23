package io.sandramine;

import android.content.Context;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** The app's few texts in Portuguese and English (the screens' texts come from sandra-core). */
final class Strings {
    private Strings() {}

    private static final Map<String, String[]> T = new HashMap<>();

    private static void t(String key, String pt, String en) {
        T.put(key, new String[] {pt, en});
    }

    static {
        t("channel", "Mineração", "Mining");
        t("mining_low", "Minerando em prioridade mínima", "Mining at the lowest priority");
        t("hint", "◀ ▶ telas   ·   OK ajustes", "◀ ▶ screens   ·   OK settings");
        t("settings", "Ajustes do SandraMine", "SandraMine settings");
        t("pause", "Pausar mineração", "Pause mining");
        t("resume", "Retomar mineração", "Resume mining");
        t("threads", "Núcleos", "Cores");
        t("cpu", "Uso de CPU por núcleo", "CPU use per core");
        t("fleet_mode", "Hashrate nas telas", "Hashrate on the screens");
        t("fleet_auto", "Automático (frota com 2+ máquinas)", "Automatic (fleet with 2+ machines)");
        t("fleet_local", "Só este aparelho", "This device only");
        t("fleet_all", "Sempre a frota", "Always the fleet");
        t("language", "Idioma", "Language");
        t("lang_auto", "Automático (idioma do sistema)", "Automatic (system language)");
        t("pool_section", "Pool e carteira", "Pool and wallet");
        t("address", "Endereço Bitcoin", "Bitcoin address");
        t("worker", "Nome do worker", "Worker name");
        t("pool", "Pool", "Pool");
        t("port", "Porta", "Port");
        t("save", "Salvar e reconectar", "Save and reconnect");
        t("saved", "Salvo", "Saved");
        t("connected", "Conectado a %s", "Connected to %s");
        t("connecting", "Conectando a %s…", "Connecting to %s…");
        t("no_address", "Falta o endereço Bitcoin", "Bitcoin address missing");
        t("error", "Erro na pool: %s", "Pool error: %s");
        t("disconnected", "Desconectado", "Disconnected");
        t("engine", "SHA-256 em hardware: %s · prioridade mínima: %s", "Hardware SHA-256: %s · lowest priority: %s");
        t("yes", "sim", "yes");
        t("no", "não", "no");
        t("fleet", "Minha frota", "My fleet");
        t("fleet_total", "%d máquinas · %s no total", "%d machines · %s in total");
        t("fleet_waiting", "Aguardando a pool listar as máquinas (leva alguns minutos).", "Waiting for the pool to list the machines (takes a few minutes).");
        t("this", " (este aparelho)", " (this device)");
        t("shares", "Shares: %d aceitos, %d rejeitados", "Shares: %d accepted, %d rejected");
    }

    static boolean pt(Context c) {
        String lang;
        try {
            lang = new org.json.JSONObject(Native.config()).optString("language", "auto");
        } catch (Exception e) {
            lang = "auto";
        }
        if (lang.startsWith("pt")) return true;
        if (lang.startsWith("en")) return false;
        return Locale.getDefault().getLanguage().equals("pt");
    }

    static String get(Context c, String key) {
        String[] v = T.get(key);
        if (v == null) return key;
        return pt(c) ? v[0] : v[1];
    }

    static String fmt(Context c, String key, Object... args) {
        return String.format(Locale.ROOT, get(c, key), args);
    }
}
