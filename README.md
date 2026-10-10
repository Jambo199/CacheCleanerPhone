# Cache Cleaner

An Android app that clears the cache of all your apps in one go.

It lists every app that's holding cache, biggest first, shows the total, and with one tap clears them all. Then it tells you how much space it freed.

## How it works

Since Android 6, Android hasn't let an ordinary app wipe other apps' caches directly. You can still do it by hand, one app at a time: **Settings → Apps → *app* → Storage → Clear cache**. Cache Cleaner does exactly that for you, using an accessibility "helper". When you press **Clean**, the helper opens each app's Settings page and taps **Clear cache**. You'll see your phone flip quickly through those pages, with a progress bar and a **Stop** button at the top of the screen.

The helper is careful about what it taps:

- It only ever taps the storage row, **Clear cache**, and a confirmation that is clearly about the cache.
- It never taps **Clear data** or **Clear storage**. The one exception is on Xiaomi phones, where "Clear data" just opens a menu, and there it only picks "Clear cache".
- It only acts while a clean is running. It doesn't read, collect or send anything, and the app has no internet permission.

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

## Building it yourself

You need JDK 17 and the Android SDK (Android Studio includes both).

```sh
./gradlew assembleRelease       # APK ends up in app/build/outputs/apk/release/
./gradlew testDebugUnitTest     # unit tests for the button-matching rules
```

The APK is signed with the key in `app/signing/`, so every build installs over the previous one. That key is only good for sideloading. If you ever publish the app, use your own key.
