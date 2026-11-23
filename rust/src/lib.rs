//! JNI bridge: the Android app (io.sandramine.Native) drives sandra-core, the same engine
//! as the desktop SandraMine. Everything crosses as JSON strings, so the Java side stays
//! small and the core keeps its own types.

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jstring};
use sandra_core::config::Config;
use sandra_core::state::{PoolStatus, Shared};
use sandra_core::{currency, fleet, i18n, miner, netinfo, stratum, work};
use serde_json::{Value, json};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::PathBuf;
use std::sync::atomic::Ordering::*;
use std::sync::{Arc, OnceLock};

struct Engine {
    shared: Arc<Shared>,
    path: PathBuf,
}

static ENGINE: OnceLock<Engine> = OnceLock::new();

/// Fields that change the pool session: editing them reconnects.
const POOL_KEYS: [&str; 6] = ["pool_host", "pool_port", "btc_address", "worker_name", "pool_password", "suggest_diff"];

fn jstr(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(|s| s.into()).unwrap_or_default()
}

fn out(env: &mut JNIEnv, s: String) -> jstring {
    env.new_string(s).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// `base` with the keys of the JSON object `patch` written over it.
fn overlay(base: &Config, patch: &str) -> Option<Config> {
    let mut v = serde_json::to_value(base).ok()?;
    let Value::Object(p) = serde_json::from_str::<Value>(patch).ok()? else { return None };
    let obj = v.as_object_mut()?;
    for (k, val) in p {
        obj.insert(k, val);
    }
    serde_json::from_value(v).ok()
}

/// Copies the runtime fields of `cfg` into the atomics the miners read.
fn apply_runtime(s: &Shared, cfg: &Config) {
    s.cpu_enabled.store(cfg.cpu_enabled, Relaxed);
    s.cpu_threads.store(cfg.cpu_threads.clamp(1, miner::max_threads()), Relaxed);
    s.cpu_usage.store(cfg.cpu_usage.clamp(1, 100), Relaxed);
    s.paused.store(cfg.start_paused, Relaxed);
}

/// Starts the engine once per process. `path` is the config file; when it doesn't exist
/// yet, `seed` (JSON, from the build's config.properties) is written over the defaults.
fn start(path: String, seed: String) -> bool {
    if ENGINE.get().is_some() {
        return true;
    }
    let path = PathBuf::from(path);
    let mut cfg = Config::load_from(&path).unwrap_or_else(|| {
        let mut base = Config::default();
        base.low_priority = true;
        base.gpu_enabled = false;
        overlay(&base, &seed).unwrap_or(base)
    });
    cfg.gpu_enabled = false; // no GPU miner on Android
    cfg.cpu_threads = cfg.cpu_threads.clamp(1, miner::max_threads());
    cfg.save_to(&path);
    i18n::set(i18n::resolve(&cfg.language));

    let shared = Shared::new(cfg);
    if ENGINE.set(Engine { shared: shared.clone(), path }).is_err() {
        return true;
    }
    stratum::spawn(shared.clone());
    miner::spawn_cpu(shared.clone());
    miner::spawn_stats(shared.clone());
    netinfo::spawn(shared.clone());
    fleet::spawn(shared);
    true
}

fn pool_status(s: &PoolStatus) -> (&'static str, String) {
    match s {
        PoolStatus::Disconnected => ("disconnected", String::new()),
        PoolStatus::NoAddress => ("no_address", String::new()),
        PoolStatus::Connecting(h) => ("connecting", h.clone()),
        PoolStatus::Connected(h) => ("connected", h.clone()),
        PoolStatus::Error(e) => ("error", e.clone()),
    }
}

/// Everything the screens and the Free My TV card show. Never the wallet address.
fn status() -> String {
    let Some(e) = ENGINE.get() else { return json!({ "running": false }).to_string() };
    let s = &e.shared;
    let cfg = s.config.read().unwrap().clone();
    let net = s.net.lock().unwrap().clone();
    let (screen, machines) = s.screen_hashrate();
    let (code, detail) = pool_status(&s.pool_status.lock().unwrap());
    let fleet = s.fleet.lock().unwrap().clone().map(|f| {
        json!({
            "machines": f.machines,
            "others": f.others,
            "best": f.best,
            "age_s": f.at.elapsed().as_secs(),
            "workers": f.workers.iter().map(|w| json!({
                "name": w.name, "hashrate": w.hashrate, "best": w.best, "me": w.me,
            })).collect::<Vec<_>>(),
        })
    });
    // The pool's "Authorized as <address>.<worker>" line: other apps read this, so hide the address.
    let address = cfg.btc_address.trim().to_string();
    let log: Vec<String> = {
        let l = s.log.lock().unwrap();
        l.iter().rev().take(8).rev().map(|m| redact(m, &address)).collect()
    };
    json!({
        "running": true,
        "hashrate": s.hashrate(),
        "screen_hashrate": screen,
        "machines": machines,
        "best_diff": s.screen_best_diff(),
        "best_diff_local": s.best_diff.get(),
        "templates": s.templates.load(Relaxed),
        "shares": s.shares32.load(Relaxed),
        "accepted": s.accepted.load(Relaxed),
        "rejected": s.rejected.load(Relaxed),
        "blocks": s.blocks.load(Relaxed),
        "total_hashes": s.total_hashes(),
        "uptime_s": s.started.elapsed().as_secs(),
        "connected": s.pool_connected.load(Relaxed),
        "paused": s.paused.load(Relaxed),
        "pool_status": code,
        "pool_detail": detail,
        "pool_host": cfg.pool_host,
        "worker": cfg.worker_name,
        "has_address": !cfg.btc_address.trim().is_empty(),
        "threads": s.cpu_threads.load(Relaxed),
        "max_threads": miner::max_threads(),
        "cpu_usage": s.cpu_usage.load(Relaxed),
        "low_priority": cfg.low_priority,
        "sha_hw": work::arm_sha_hw(),
        "fleet_mode": cfg.fleet_mode,
        "fleet": fleet,
        "currency": cfg.currency,
        "price": net.price(&cfg.currency),
        // Texts for the NerdMiner screens, translated and formatted like the desktop's.
        "price_screen": net.price(&cfg.currency).map(|p| currency::price_screen(p, &cfg.currency))
            .unwrap_or_else(|| format!("{}--", currency::screen_symbol(&cfg.currency))),
        "fleet_badge": i18n::t().fleet_badge,
        "paused_screen": i18n::t().paused_screen,
        "block_height": net.block_height,
        "net_hashrate": net.net_hashrate,
        "net_difficulty": net.net_difficulty,
        "fee_half_hour": net.fee_half_hour,
        "log": log,
    })
    .to_string()
}

fn redact(line: &str, address: &str) -> String {
    if address.is_empty() { line.to_string() } else { line.replace(address, "…") }
}

/// The full config (including the address), for the app's own settings screen.
fn config() -> String {
    ENGINE
        .get()
        .and_then(|e| serde_json::to_string(&*e.shared.config.read().unwrap()).ok())
        .unwrap_or_else(|| "{}".into())
}

/// Writes the keys of `patch` (JSON object) over the config, applies and saves it.
fn apply(patch: String) -> bool {
    let Some(e) = ENGINE.get() else { return false };
    let s = &e.shared;
    let old = s.config.read().unwrap().clone();
    let Some(mut cfg) = overlay(&old, &patch) else { return false };
    cfg.gpu_enabled = false;
    cfg.cpu_threads = cfg.cpu_threads.clamp(1, miner::max_threads());
    cfg.cpu_usage = cfg.cpu_usage.clamp(1, 100);
    let old_v = serde_json::to_value(&old).unwrap_or_default();
    let new_v = serde_json::to_value(&cfg).unwrap_or_default();
    let reconnect = POOL_KEYS.iter().any(|k| old_v.get(k) != new_v.get(k));
    apply_runtime(s, &cfg);
    i18n::set(i18n::resolve(&cfg.language));
    cfg.save_to(&e.path);
    *s.config.write().unwrap() = cfg;
    if reconnect {
        s.config_gen.fetch_add(1, Release);
    }
    true
}

fn guard<T>(fallback: T, f: impl FnOnce() -> T) -> T {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or(fallback)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_sandramine_Native_start(mut env: JNIEnv, _c: JClass, path: JString, seed: JString) -> jboolean {
    let (path, seed) = (jstr(&mut env, &path), jstr(&mut env, &seed));
    guard(false, || start(path, seed)) as jboolean
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_sandramine_Native_status(mut env: JNIEnv, _c: JClass) -> jstring {
    let s = guard("{}".to_string(), status);
    out(&mut env, s)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_sandramine_Native_config(mut env: JNIEnv, _c: JClass) -> jstring {
    let s = guard("{}".to_string(), config);
    out(&mut env, s)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_sandramine_Native_apply(mut env: JNIEnv, _c: JClass, patch: JString) -> jboolean {
    let patch = jstr(&mut env, &patch);
    guard(false, || apply(patch)) as jboolean
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_sandramine_Native_maxThreads(_env: JNIEnv, _c: JClass) -> jint {
    miner::max_threads() as jint
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn overlay_keeps_defaults_and_sets_keys() {
        let base = Config::default();
        let c = overlay(&base, r#"{"worker_name":"sm-tv","cpu_threads":2,"low_priority":true}"#).unwrap();
        assert_eq!(c.worker_name, "sm-tv");
        assert_eq!(c.cpu_threads, 2);
        assert!(c.low_priority);
        assert_eq!(c.pool_host, base.pool_host);
        assert!(overlay(&base, "not json").is_none());
    }

    #[test]
    fn status_log_hides_the_address() {
        assert_eq!(redact("Authorized as bc1qxyz.sm-tv", "bc1qxyz"), "Authorized as ….sm-tv");
        assert_eq!(redact("Pool difficulty: 1", ""), "Pool difficulty: 1");
    }
}
