# Known bloat and adware on budget Android TVs

These were found on a Zeasn/ZVT "Smart TV" (HiSilicon Hi3751V350, Android 14), sold under
several brands. **Never disable a package just because it's listed here** — check it exists,
look at what it does (`dumpsys package <pkg>`: installer, permissions, services, running
state) and ask the owner first. Disable with `pm disable-user`; never uninstall system apps.

| Package | What it was | Safe to disable? |
|---|---|---|
| `com.whale.display` | Adware silently installed by the Zeasn launcher: ad connector, ad screen saver, dynamic code loader, can install/delete apps | Yes |
| `com.zeasn.asdprovider.*` | Zeasn ad provider; persistent, starts at boot | Yes (stays running until removed for the user, see notes) |
| `zeasn.open.technical.tv.store` | Zeasn app store (installs phone versions of apps) | Yes, Play Store remains |
| `com.chijimlee.retailmode` | Store demo mode | Yes |
| `com.zvt.ota` | Vendor firmware updater (can push new bloat); persistent | Owner's choice — no more firmware updates |
| `com.tcl.browser` | "BrowseHere", ad-supported browser | Yes |
| `com.google.android.katniss` | Google voice search | Only if the owner doesn't use voice |
| `com.zeasn.launcher` | Stock home screen; the thing that installed the adware; ~55 MB RAM | Only **after** the new home screen is installed and working |

Keep: anything `com.android.*`, `com.google.android.gms`, `com.android.vending`, the TV
player / input apps (`*.tvui`, `*.tv.service`, `*.tvsetting`), casting/DLNA services
(`*.miracast`, `*.dlna.*`, `com.newlink.cast`) unless the owner says otherwise.

Notes
- **Persistent system apps** (`pkgFlags` contains `PERSISTENT`) restart at boot even when
  disabled. Stopping them needs `pm uninstall -k --user 0 <pkg>` (reversible with
  `cmd package install-existing <pkg>`). Explain this and let the owner decide.
- Look for apps whose `installerPackageName` is the vendor launcher or store and that were
  installed months after the firmware date — that's how the adware above was found.
