package io.sandramine;

/** sandra-core (Rust, the desktop SandraMine's engine) through rust/src/lib.rs. JSON in and out. */
final class Native {
    private Native() {}

    static {
        System.loadLibrary("sandramine");
    }

    /** Starts the engine once per process. seed: JSON written over the defaults on first run. */
    static native boolean start(String configPath, String seed);

    /** Hashrate, shares, pool, fleet and network: see status() in rust/src/lib.rs. No wallet address. */
    static native String status();

    /** The full config, wallet address included (only for this app's settings). */
    static native String config();

    /** Writes the keys of a JSON object over the config, applies and saves it. */
    static native boolean apply(String patch);

    static native int maxThreads();
}
