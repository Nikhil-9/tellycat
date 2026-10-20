# TellyCat 🐈‍⬛

**A small, grumpy cat that lives on your Android TV's home screen.**

![TellyCat on an Android TV home screen](docs/demo.gif)

Soot (the cat) sits on top of whatever you've selected and reacts to what you do with the remote:

- **Browsing:** hops from tile to tile as you move along the row.
- **Scrolling fast:** the row slides away under him, so he has to sprint to keep up.
- **Too much scrolling:** gets irritated, then properly furious (flames). Keep going for a few seconds and he collapses, exhausted, then throws a tantrum. He calms down if you leave him alone.
- **Opening an app:** the floor disappears. He falls, crashes, goes dizzy, gets up angry and storms off, so he's never in the way while you watch.
- **Back home:** walks or leaps back in.
- **Left alone for 3 minutes:** naps. Wake him with any key, at your own risk.
- **Back button:** a startled hiss. **Up/Down on the Glow Home row** (which does nothing): a "what?" look.

## What's in here

| | |
|---|---|
| `tellycat/` | **TellyCat**, the cat. An accessibility service that draws a small overlay window. Works with any launcher. |
| `glowhome/` | **Glow Home**, an optional minimal launcher (looping glow video, one row of app banners). With it, the cat stands *on* the selected tile and hops between tiles. |
| `art/soot/` | The cat's 27 animations as standalone HTML files (128×128 SVG, 12 drawings per second). |
| `tools/` | Turns animation HTML into the frames the app plays, so you can make your own cat. |

### With or without Glow Home

- **With Glow Home** (recommended): Glow Home tells TellyCat exactly where the selected tile is, so the cat stands on it, hops between tiles, runs when the row slides, and falls when you open an app.
- **With any other launcher:** the cat lives on the bottom edge of the home screen and walks or runs along it as you press Left/Right. It still leaves when you open an app, comes back on the home screen, naps and wakes. Launchers don't share where their tiles are, so tile-hopping needs Glow Home.

## Privacy

TellyCat is an accessibility service because that's the only way a TV app can draw over other apps and see remote presses. It only receives:

- which app is in front (window-state changes), and
- remote key presses, which always pass through to the TV untouched.

It **cannot read what's on screen** (`canRetrieveWindowContent="false"`), has **no internet permission**, and stores nothing. Glow Home's link to the cat is a signature-level permission: only an app signed with the same key can send it tile positions.

## Install

You need an Android TV / Google TV running Android 8.0 or newer, and a way to sideload APKs (adb, or a file manager on the TV).

1. Download `TellyCat.apk`, and `GlowHome.apk` if you want it, from the [latest release](../../releases/latest).
2. Install them:

   ```bash
   adb connect <tv-ip>:5555
   adb install TellyCat.apk
   adb install GlowHome.apk        # optional
   ```

3. Turn TellyCat on: **Settings → Device Preferences → Accessibility → TellyCat → Enable.**
   Or with adb (this replaces any other enabled accessibility services, so add them back after a colon if you use any):

   ```bash
   adb shell settings put secure enabled_accessibility_services com.nikhil.glowcat/com.nikhil.glowcat.CatService
   adb shell settings put secure accessibility_enabled 1
   ```

   On Android 13+, if the toggle is greyed out ("restricted setting"), go to **Settings → Apps → TellyCat**, open the menu and choose **Allow restricted settings** first.

4. Optional, to use Glow Home: press Home and pick Glow Home as the default. On Google TV and some other devices the built-in launcher can't be replaced from the UI; with adb:

   ```bash
   adb shell cmd package set-home-activity com.nikhil.glowhome/.HomeActivity
   ```

**Install TellyCat and Glow Home from the same release.** Their signing keys must match for tile-hopping to work. Release APKs are signed by the build (CI) for that release, so when updating, update both, and uninstall first if Android refuses the update.

To turn the cat off, disable it in the accessibility settings or uninstall it (`adb uninstall com.nikhil.glowcat`).

## Build from source

Requirements: JDK 17 and the Android SDK (Android Studio installs both; the command-line tools are enough).

```bash
git clone https://github.com/<you>/tellycat.git
cd tellycat
./gradlew assembleDebug        # gradlew.bat on Windows
```

The APKs end up in `tellycat/build/outputs/apk/debug/` and `glowhome/build/outputs/apk/debug/`. Builds from one machine share its debug key, so the two apps can talk to each other. For release builds, sign **both** APKs with the same key.

Things you may want to change:

- **Glow Home's background:** replace `glowhome/src/main/res/raw/glow.mp4` with any short looping video (1080p H.264, a few MB). The text and tiles are styled for a light background; colours are at the top of `HomeActivity.java`.
- **Which apps come first / are hidden:** `PREFERRED` and `HIDDEN` in `HomeActivity.java`.
- **The cat's timings and moods:** the constants at the top of `CatService.java` (when he leaves for a video, how fast he gets angry, when he falls asleep, and so on).

## Make your own cat

The app plays pre-rendered frames: `tellycat/src/main/assets/cat/<clip>/NNN.webp` plus `clips.txt`. To replace Soot:

1. **Draw the animations** (the originals were made in Claude Design). One HTML file per clip, named after the clips in `art/soot/` (`idle_grumpy`, `walk`, `run`, `hop_takeoff`, `fall_air`, `dizzy` …). Each file must:
   - expose `window.CAT_ANIM = { duration, loop, fps, frameCount, renderFrame(t), renderFrameIndex(i) }`,
   - draw into an element with `id="cat"` (an `<svg>`, or a wrapper containing one) with `viewBox="0 0 128 128"`,
   - use a transparent background, feet on **y = 112**, cat facing **right**, and stay **in place** (the app moves the cat around the screen and mirrors it to face left).
2. **Render the frames:**

   ```bash
   python tools/frame_server.py --src art/soot --out tools/frames
   # open http://127.0.0.1:8765/ in Chrome or Edge and wait until it says DONE
   ```

3. **Pack them into the app** (needs `pip install pillow`):

   ```bash
   python tools/pack_frames.py --frames tools/frames --assets tellycat/src/main/assets/cat
   ```

4. Rebuild and install.

Each clip's role (what triggers it, and what plays before and after it) is documented in `CatService.java`. Missing clips are skipped, so you can start with a few.

## Limitations

- Built and tested on one Android TV 11 set (1080p UI on a 4K panel). Other TVs and launchers should work, but haven't been tested.
- The TV's UI layer is often 1080p even on 4K panels, so the cat is drawn at 1080p and looks slightly soft up close.
- "Is a video playing?" is detected from audio activity, so music apps and autoplaying trailers also send the cat away.
- Keys sent with `adb shell input keyevent` don't reach accessibility services, so for scripted testing use Glow Home (whose tile moves count as activity) or a real remote.

## Credits

- Cat animations designed with **Claude Design**.
- Apps written with **Claude Code**.

## License

[MIT](LICENSE). The code and the Soot artwork in this repository are both covered.
