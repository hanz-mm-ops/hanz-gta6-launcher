# CLAUDE.md

This repo turns an Android TV's home screen into a clean, glass-style one and removes
adware/bloat over ADB. When someone opens this repo and asks for help with their TV,
**follow [PROMPT.md](PROMPT.md) phase by phase** — it has the safety rules, the steps, and the
known pitfalls. Start by asking what TV they have and whether it's on the same Wi-Fi as this
computer.

Layout:
- `launcher/` — the home screen app (plain Java, built with the Android SDK tools, no Gradle)
- `tools/` — find the TV, back up, disable/restore packages, set the dock
- `bootvideo/` — optional startup video for HiSilicon TVs
