# 3D Stacker

An endless 3D stacking game for Android, built with libGDX and Kotlin. See `spec.md` for the game design.

## Layout

- `core/`: all game code (logic, rendering, screens). `core/.../logic` has no rendering code and is unit tested.
- `android/`: Android launcher and manifest.
- `lwjgl3/`: desktop launcher, for quick testing on a PC.
- `assets/`: images, font and sounds shared by both launchers.
- `design/`: sources for the temporary assets. The SVG icons are also on the "3D Stacker" page in Penpot. `make_sfx.py` regenerates the sounds.

## Build & run

The project needs JDK 17 (pinned in `.mise.toml`) and the Android SDK (path in `local.properties`).

```sh
./gradlew :core:test                 # logic unit tests
./gradlew :lwjgl3:run                # play on desktop
./gradlew :android:installDebug      # install on a connected phone/emulator
./gradlew :android:assembleRelease   # minified release APK (unsigned)
```

Desktop keys: arrows/WASD move, R rotate, T tip, Space drop, Esc pause.

Desktop test hooks (environment variables): `STACKER_AUTOPLAY=1` turns on the bot, `STACKER_CHAOS=1` makes it place pieces randomly, `STACKER_IMMORTAL=1` turns off heart loss, and `STACKER_START=menu|game|paused|settings|shop_backgrounds|shop_blocks|shop_specials` picks the start screen. To save screenshots, set `STACKER_SHOTS=/tmp/shot` and `STACKER_SHOT_TIMES=2,5`.
