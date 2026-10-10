# Cache Cleaner

An Android app that clears the cache of all your apps in one go.

It lists every app that's holding cache and shows the total. With one tap it clears them all, then tells you how much space it freed. The **Sort** drop-down above the list puts the apps with the most cache first, the least cache first, or in name order.

## How it works

Since Android 6, Android hasn't let an ordinary app wipe other apps' caches directly. You can still do it by hand, one app at a time: **Settings → Apps → *app* → Storage → Clear cache**. Cache Cleaner does exactly that for you, using an accessibility "helper". When you press **Clean**, the helper opens each app's Settings page and taps **Clear cache**. While it works, a full-screen progress page with a **Stop** button covers the phone, so you don't see the pages flipping. The pages still open behind it, because Android only lets the helper press buttons on screen. If you'd rather watch, turn off **Hide Settings while cleaning**. You'll then see each page, with a small progress bar at the top.

The helper is careful about what it taps:

- It only ever taps the storage row, **Clear cache**, and a confirmation that is clearly about the cache.
- It never taps **Clear data** or **Clear storage**. The one exception is on Xiaomi phones, where "Clear data" just opens a menu, and there it only picks "Clear cache".
- It only acts while a clean is running, and it doesn't read, collect or send anything. The app goes online only to check GitHub for updates, and you can turn that off.

### Cleaning in the background (with Shizuku)

The helper needs the screen while it works. To keep using your phone instead, set up the free [Shizuku](https://shizuku.rikka.app/) app. Shizuku lets Cache Cleaner use Android's built-in `pm trim-caches` command. That clears every app's cache at once in a few seconds, in the background, with only a notification showing progress and the result.

1. Install Shizuku (from Google Play, or from its [GitHub releases](https://github.com/RikkaApps/Shizuku/releases) if your phone has no Play Store).
2. Turn on **Developer options** (Settings → About phone → tap the version number 7 times; on vivo it's **Software version**). Connect to Wi-Fi.
3. Open Shizuku, choose **Start via Wireless debugging**, and follow its pairing steps.
4. Back in Cache Cleaner, tap **Allow** when it asks to use Shizuku.

From then on, the main screen has a **Clean all apps in the background** button. Shizuku stops when the phone restarts. When that happens, open Shizuku and start it again (step 3, without the pairing). Until then, the app falls back to the helper.

On Android 11 and newer there is also a **Quick clean**. It uses Android's built-in "clear app cache" dialog to empty every app's shared cache folder at once, with no helper needed. It can't reach the apps' private caches, so it frees less than a full clean.

> iPhones can't do this. iOS doesn't allow any app to clear other apps' caches, so this project is Android only.

## Install

1. On your phone, open this repository's **Releases** page (sign in to GitHub first, since the repository is private) and download **CacheCleaner.apk** from the "latest" release. Every push rebuilds it automatically.
2. Open the downloaded file. If Android asks, allow your browser or Files app to "install unknown apps".
3. Open **Cache Cleaner** and follow the two setup steps:
   1. **Allow usage access**, so it can see how much cache each app has.
   2. **Turn on the auto-clean helper** in Accessibility settings.
      On Android 13 and newer, sideloaded apps are blocked from accessibility until you allow it. If the switch is greyed out or you see "Restricted setting", go to **Settings → Apps → Cache Cleaner → ⋮ (top right) → Allow restricted settings**, then try again.
4. Tap **Clean**.

If an app can't be cleared automatically, the results card lists it with an **Open** button, so you can finish it by hand.

### Settings and updates

Tap **Settings** at the top of the main screen. There you can:

- choose whether the Settings pages are hidden while cleaning,
- pick how the app list is sorted,
- check which permissions are on,
- clear the activity list,
- check for updates.

When you open the app, it asks GitHub (at most every few hours) whether there's a newer build. If there is, an **Update now** button appears. Tapping it downloads the new version and checks that it's really Cache Cleaner, signed with the same key. Then it opens Android's own "update this app?" screen. Nothing installs unless you confirm there. The first time, Android asks you to allow Cache Cleaner to install apps.

The updater can only see new versions if this GitHub repository is **public**. While it's private, GitHub only shows the downloads to people who are signed in. The app then offers to open the download page in your browser instead.

### Seeing what it's doing

Tap **See every step** (or **See what it did last time**) to open the activity screen. It lists everything the helper did in plain words, grouped by app: which page it opened, what it tapped, and whether the cache was cleared. Each app is marked ✓ cleared, – nothing to clear, or ! a problem. The list fills in live while a clean runs, and the bar at the top of the screen shows the current step too. The last clean's list is kept until the next one starts.

### vivo phones

vivo's Settings (Funtouch OS and OriginOS) call the storage row **Internal storage**, and the helper knows that. Two vivo-specific settings can get in the way:

- **Background pop-ups.** If the clean stops with "Your phone stopped the helper", vivo is blocking it from opening the next app's page. Go to **Settings → Apps → Cache Cleaner → Permissions** and turn on **Background pop-ups** (it may be under "All permissions" or "Other permissions"). Then tap Clean again.
- **Battery saving.** If the helper keeps switching itself off, allow Cache Cleaner high background power use in **Settings → Battery**.

## Building it yourself

You need JDK 17 and the Android SDK (Android Studio includes both).

```sh
./gradlew assembleRelease       # APK ends up in app/build/outputs/apk/release/
./gradlew testDebugUnitTest     # unit tests for the button-matching rules
```

The APK is signed with the key in `app/signing/`, so every build installs over the previous one. That key is only good for sideloading. If you ever publish the app, use your own key.
