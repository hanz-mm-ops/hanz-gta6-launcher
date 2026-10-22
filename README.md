# GTA6 TV Launcher

**An unofficial Android TV home screen by Hanz Studio.** A live countdown to GTA VI, backgrounds you
pick from the skyline video and artwork, your apps with their TV banners, inputs and shortcuts, and
a matching screen saver. Built on [Glass TV Home](#glass-tv-home) (MIT).

> Not affiliated with Rockstar Games, Take-Two or Google. The GTA VI artwork, logo and skyline video
> belong to Rockstar Games and are **not** in this repository. `launcher/fetch-art.sh` downloads them
> from [rockstargames.com/VI](https://www.rockstargames.com/VI/media/artwork-wallpapers) onto your
> own computer the first time you build (`build.sh` runs it for you). Don't publish builds that
> include them.

## Install with Claude Code

1. On the TV: *Settings → Device Preferences → About → Build*, press OK seven times, then turn on
   **USB debugging** in *Developer options*. Note the TV's IP (*Settings → Network*).
2. In [Claude Code](https://claude.com/claude-code) on a Mac on the same Wi-Fi, paste:

```text
Clone github.com/hanz-mm-ops/hanz-gta6-launcher and install the GTA6 TV Launcher on my TV.
The TV's address is TV_IP and USB debugging is on. Follow PROMPT.md and CLAUDE.md in the repo:
1. Install whatever is missing on this Mac: adb, Java 17 and the Android SDK (build-tools 36, platform android-36).
2. Connect to the TV and make a backup with tools/backup.sh.
3. Build the app, install it with launcher/install.sh, and check that the Home button opens it.
4. Take a screenshot of the TV and show it to me.
Ask me before any change on the TV, before any download, and before accepting any licence.
When you finish, give me the command that restores the TV, and remind me to turn USB debugging off.
```

Fonts (Anton, Oswald, Inter; OFL) are bundled in `launcher/res/font`. The artwork and video are downloaded
automatically on the first build.

## By hand

```bash
brew install --cask android-platform-tools android-commandlinetools
brew install openjdk@17
JAVA_HOME=/opt/homebrew/opt/openjdk@17 sdkmanager --licenses
JAVA_HOME=/opt/homebrew/opt/openjdk@17 sdkmanager "build-tools;36.0.0" "platforms;android-36"
git clone https://github.com/hanz-mm-ops/hanz-gta6-launcher && cd hanz-gta6-launcher
tools/backup.sh TV_IP        # snapshot before changes
launcher/install.sh TV_IP    # download art, build, install, set as home
```

Undo: `tools/restore.sh <ip> <your-bloat-list> <original-home-activity>`, or `adb uninstall app.tvshell`.

---

## Glass TV Home

The launcher this project grew from: a fast, glass-style home screen for Android TVs plus scripts to
clean adware off cheap smart TVs, designed to be set up by Claude. See [`PROMPT.md`](PROMPT.md).

- Requires an **Android TV / Google TV** (Android 11+). Not VIDAA, Tizen, webOS or Roku.
- Weather: [Open-Meteo](https://open-meteo.com); approximate location from [ipwho.is](https://ipwho.is).
- Code: MIT (see `LICENSE`).
