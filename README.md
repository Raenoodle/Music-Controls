# Music Controls

Control Spotify, YouTube Music and SoundCloud from a player above the YouBar. Spotify works on Android and iOS; YouTube Music and SoundCloud need the Music Controls app on Android.

| | Spotify | YouTube Music, SoundCloud |
| --- | --- | --- |
| Android | Yes, through Discord's Spotify connection | Yes, with the Music Controls app |
| iOS | Yes, through Discord's Spotify connection | No |

## Install the plugin

Add this URL in **Settings → Plugins → Settings Icon → Repository URL** and install **Music Controls**:

```text
https://raw.githubusercontent.com/dariussbezy/dariussbezy-Plugins/main/index.json
```

Spotify needs nothing else: link Spotify in **Discord → Connections**.

## The Music Controls app (Android only)

Only needed for **YouTube Music and SoundCloud** on **Android**. It is not for iPhone, and Spotify does not need it.

1. Download [MusicControls.apk](app/MusicControls.apk?raw=1) and install it.
2. Open it and tap **Open notification access**, then turn on Music Controls.
3. On Android 13 and newer, if the switch is greyed out: **App info → ⋮ → Allow restricted settings**, then try again.
4. If the player stops updating after a while, set the app's battery usage to **Unrestricted** (on Xiaomi also allow **Autostart**). On Huawei and Honor the app shows the extra steps (App launch → Manage manually) the first time it opens.

The app reads media sessions only. It does not read or keep your notifications, has no internet access, and sends what is playing only to Discord, behind a signature permission. Android 7 or newer, any phone.

**Listening status** (optional): turn it on in the plugin settings to show Listening to YouTube Music or SoundCloud on your profile, with the song, artist, cover and time. YouTube Music covers are looked up by title and artist in Apple's public iTunes search.

Shuffle and repeat work on YouTube Music. SoundCloud does not share them with other apps, so their buttons are hidden while it plays.

## Repository

- `plugins/MC`: the plugin (TypeScript, plus a small Kotlin part for Android).
- `app/source`: the app (Kotlin). Build with `./gradlew assembleRelease`; release signing reads `keystore.properties`, which is not in the repository.

Credits: dariussbezy, with [coder.gg](https://github.com/il1ll) and kmmiio99o.dev.
