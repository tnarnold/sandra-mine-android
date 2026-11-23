# SandraMine for Android

The [SandraMine](https://github.com/tnarnold/sandra-mine) NerdMiner emulator for Android TVs (and, later, phones): solo Bitcoin mining on the device's CPU, with the NerdMiner v2 screens, joining the same **fleet** as your other machines.

It runs **without getting in the TV's way**. The mining threads run at Linux's idlest priority, `SCHED_IDLE` plus nice 19, so they only get CPU time that nothing else wants. Video, apps and the home screen always come first.

> Solo mining on a TV is a lottery ticket, not income. A Sony BRAVIA (4× Cortex-A53) does ~1.9 MH/s per core; the Bitcoin network does ~1 ZH/s.

## How it works

- **`rust/`**: a JNI bridge (`libsandramine.so`) to **sandra-core**, the desktop app's engine:
  - the stratum client, CPU mining, fleet totals and network data;
  - the same code and the same tests as the desktop app.

  On 32-bit ARM, which most TVs use, sandra-core hashes with the ARMv8 SHA-256 instructions, about 6.5× faster than plain software.
- **`app/`**: a small Java app. It has no Gradle and no dependencies. It contains:
  - the NerdMiner screens (◀ ▶ to switch, OK for settings);
  - a foreground service that keeps mining while you use other apps;
  - a boot receiver that starts mining again after the device restarts;
  - a read-only status provider for home-screen cards.
- **Fleet:** the device mines to the same address under its own worker name (`sm-<model>`, e.g. `sm-bravia`). The pool groups the machines by address, so the desktop's **My fleet** lists the TV, and the TV's screens show the fleet total with the **FLEET ×N** badge.

## Build and install

You need:
- a JDK 17+;
- the Android SDK (build-tools, a platform) and NDK;
- Rust with `cargo-ndk` and the Android targets;
- [sandra-mine](https://github.com/tnarnold/sandra-mine) cloned **next to** this repo, because the engine comes from `../sandra-mine/core`.

```bash
sdkmanager "build-tools;35.0.0" "platforms;android-35" "ndk;27.3.13750724"
rustup target add armv7-linux-androideabi aarch64-linux-android
cargo install cargo-ndk

git clone https://github.com/tnarnold/sandra-mine
git clone https://github.com/tnarnold/sandra-mine-android && cd sandra-mine-android
./build.sh                  # first run creates config.properties: put your Bitcoin address there
./build.sh
adb connect <tv-ip>:5555
adb install -r SandraMine.apk
```

`config.properties` holds the first-run settings:
- address;
- worker;
- pool;
- threads (default 2);
- CPU % per core (default 50).

It is in `.gitignore`. Everything can be changed later on the device: press OK on the remote to open the settings.

## Status for other apps

`content://io.sandramine.status/status` returns one row with a `json` column. It holds:
- hashrate (this device, and the screens' value with the fleet);
- best difficulty, shares and pool status;
- fleet workers;
- block height and price.

It **never includes the wallet address**. The [Free My TV](https://github.com/tnarnold/free-my-tv) home screen uses it for its SandraMine card. Check it with:

```bash
adb shell content query --uri content://io.sandramine.status/status
```

## Credits

NerdMiner screens, backgrounds and fonts from NerdMiner v2 (© 2023 Bitmaker, MIT); see `app/assets/CREDITS.md`. MIT license.
