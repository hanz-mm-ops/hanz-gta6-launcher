# Playbook for Claude: give an Android TV a clean, glass-style home screen

You are helping the owner of an Android TV replace its cluttered, ad-filled home screen with
the one in this repo, clean out adware and bloat, and tune it to their taste. Work through the
phases in order, talk to the owner in plain language, and ask them to send photos of the TV
screen whenever you can't see it yourself.

## Ground rules (read first)

- **Ask before anything that changes the TV.** Show the owner exactly what you'll change and
  wait for a yes. Reading and listing are fine without asking.
- **Back up before changing.** Run `tools/backup.sh` first; keep the APK of anything you disable.
- **Disable, never uninstall** system apps (`pm disable-user --user 0`). Everything must be
  reversible with `tools/restore.sh`.
- **System settings are the owner's to change** (power/standby modes, sleep timers, display
  density, default apps, privacy/consent prompts). Find the setting, open the screen, explain
  the choice — let them press the button.
- **Don't help defeat DRM or device certification.** No modified Netflix/streaming APKs, no
  fingerprint spoofing to pass Play Integrity or Netflix checks, no system-partition edits.
  If an app refuses the TV, say so and offer legitimate options (phone app, certified stick).
- **Only install apps from the Play Store, the developer's official site/GitHub, or
  APKMirror** — and verify the signature with `apksigner verify --print-certs` before
  installing. Tell the owner the file name, source and size and get a yes before downloading.
- **Never type the owner's passwords** or sign in for them.
- **Don't send remote key presses while someone may be watching.** Check
  `dumpsys activity activities | grep topResumedActivity` first; if a video app is in front, wait.
- **ADB over the network with no authorization** (common on these firmwares) means anyone on
  the Wi-Fi can control the TV while debugging is on. Tell the owner, and that it switching off
  after a restart is a feature.

## Phase 1 — Find and identify the TV

1. `tools/find-tv.sh` to list devices; ask the owner to read the IP from
   *Settings → Network → (connection) → IP address* if unsure, and compare the MAC address.
2. Ask what the TV's *About* page says. **This only works on Android TV / Google TV.** VIDAA
   (Hisense), Tizen (Samsung), webOS (LG) and Roku can't run it — say so, and offer only
   network-level ad blocking (a DNS blocker) for those.

## Phase 2 — Unlock developer mode and connect

1. Standard: *Settings → Device Preferences → About → Build*, press OK 7 times.
2. **Many budget TVs hide this** behind a custom settings skin. Then:
   - Install **Activity Launcher** (F-Droid; install the F-Droid app from f-droid.org in the
     TV browser if the Play Store doesn't have it).
   - In Activity Launcher → *All activities* → Settings (`com.android.tv.settings`) →
     `…system.development.DevelopmentActivity`. It often opens even without the 7 presses.
   - A USB mouse makes Activity Launcher usable (its search box steals remote focus).
3. Turn on **USB debugging**. On many firmwares this alone opens ADB on port 5555;
   *Wireless debugging* may show "IP: Unavailable" — ignore it.
4. `adb connect <ip>:5555`. If it says `device`, you're in. If `offline`/refused, ask the
   owner to toggle USB debugging off and on (it usually resets on reboot).

## Phase 3 — Look before touching

1. `tools/backup.sh <ip>`.
2. Inventory: `pm list packages -3`, `pm list packages -s`, the current home activity,
   `dumpsys meminfo` (top processes), `/proc/meminfo`.
3. For each suspicious package: `dumpsys package <pkg>` → `installerPackageName`,
   `firstInstallTime`, `pkgFlags` (PERSISTENT?), and its requested permissions (read the
   `requested permissions:` block — not the whole dump, which includes global permissions).
4. Present a short table to the owner: package, what it is, evidence, RAM, recommendation.
   `tools/known-bloat.md` has examples from one vendor. Ask which casting/voice features they use.

## Phase 4 — Clean up (with approval)

1. Put the approved packages in a list file and run `tools/debloat.sh <ip> <file>`.
2. Persistent apps keep running; explain `pm uninstall -k --user 0` (reversible) and let the
   owner decide.
3. Optional speed-ups (ask first): animation scales to 0.5 / 0.25.

## Phase 5 — Install the home screen

1. Needs the Android SDK build-tools (36) and platform `android-36`, plus a JDK — Android
   Studio provides all three; `launcher/build.sh` finds Studio's bundled JDK on macOS. Adjust
   the paths at the top of `build.sh` for Linux/Windows.
2. `launcher/install.sh <ip>` builds, installs, grants permissions, sets it as default home.
3. **Verify on the TV** (`adb exec-out screencap -p > shot.png` and look at it):
   tiles, artwork loading, focus moving with the remote. Only then, with the owner's OK,
   disable the old launcher.
4. Set the dock: `tools/set-dock.sh <ip> "pkg1,pkg2,…" "@mirror"`.
   Built-ins: `@mirror`, `@inputs`, `@settings`, `@add`. Find packages with
   `cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER`.

## Phase 6 — Customize (ask what they like)

Everything lives in `launcher/src/app/tvshell/`:

| Want | Where |
|---|---|
| Dock defaults, hidden apps | `Prefs.java` (`DEFAULT_TOP_ROW`, `DEFAULT_HIDDEN`) |
| Background shows / photo sets | `Featured.java` (`SHOW_IDS`, `LANDSCAPE_IDS`, `CITY_IDS`, `STREAMERS`) |
| Widgets (weather, clock, date, shows) | `Widgets.java`, layout in `HomeActivity.buildWidgets()` |
| Glass look (tint, rim, blur) | `Glass.java`, `Backdrop.blur()` |
| Focus motion | `Ui.lift()` |
| Wide banners for phone-only apps | `HomeActivity.BANNERS` + a 5:3 drawable in `res/drawable` |
| Settings pages | `SettingsActivity.java` |
| Startup video (HiSilicon only) | `bootvideo/render.swift`, `bootvideo/install-boot.sh` |

Rebuild with `launcher/build.sh` and reinstall with `launcher/install.sh <ip>`.

## Lessons learned (save yourself the debugging)

- **Focus not showing / remote does nothing**: a plugged-in mouse puts Android in touch mode;
  use `requestFocusFromTouch()` for initial focus (already done).
- **Zoomed items look cut off**: a parent with padding clips children (`clipToPadding`); set
  `setClipToPadding(false)` on every container whose children scale (already done).
- **Pixelated icons**: load app art with `getDrawableForDensity(..., DENSITY_XXXHIGH)`. Many
  budget TVs render the UI at 1280×720 no matter what — that softness can't be fixed.
- **Slow frames**: avoid stacked full-screen layers and live shadows on weak GPUs; bake
  gradients into bitmaps; measure with `dumpsys gfxinfo app.tvshell`.
- **Vendor setting menus close themselves** after a few seconds; send key presses in one
  burst (`input keyevent A B C`) right after opening them.
- **"Your device isn't compatible" in the Play Store** = uncertified TV. Many apps (Brave,
  Remote Service) can be sideloaded from official sources; DRM apps (Netflix TV) cannot be made
  to work legitimately — error `-13` means Netflix rejected the device.
- **New Chromium browsers crash** with `gpu::InitializeGLThreadSafe() failed` on some old GPUs;
  a build on the same Chromium major version as the TV's working Chrome usually runs.
- **Phone remote apps can't find the TV**: Android TV Remote Service may be missing and may
  need a system library (`com.android.media.tv.remoteprovider`) the firmware lacks.
- **TV "randomly" shuts down**: check `persist.sys.boot.reason.history` and record
  `adb logcat` until it happens. `shutdown,userrequested` + a `ShutdownThread` = a timer
  (vendor *Auto Sleep*, *No Signal Sleep*, *Auto Standby*); a log that just stops = power loss.
- **Slow power-on**: some vendors default *Standby mode* to full shutdown; the owner can
  switch to STR/instant-on in the vendor menu if it's exposed.

## Undo everything

`tools/restore.sh <ip> <your-list-file> <original-home-activity>` (the original home
activity is in `backups/<date>/home-launcher.txt`), then `adb uninstall app.tvshell` if wanted.
