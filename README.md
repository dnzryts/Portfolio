# Source code: Portfolio and Dial Clock

Two Android apps, written in plain Java on the Android framework only (no AndroidX or other
libraries). Each folder is an Android Studio project and also includes the APK built from it.

## Portfolio (`Portfolio/`, package `com.uliptracker`, v9.1.1)

| File | What it does |
|---|---|
| `MainActivity.java` | All screens: bottom menu (Dashboard, Portfolio, Analytics, Markets, More), holding detail, add/search flows, ULIP paste, settings, backup, welcome screen, watch sync settings |
| `Store.java` | Data model (stocks, mutual funds, ULIP funds, indexes, other assets, loans, family owners, policies, platforms) saved as JSON in app storage; maths for value, P&L, history |
| `Net.java` | Price sources: mfapi.in for mutual funds, Yahoo Finance chart/search for NSE/BSE stocks, ETFs and indexes |
| `Updater.java` | Refreshes prices (fetches each symbol once even when held on several platforms) |
| `Alerts.java`, `AlertJob.java` | Move alerts, one-shot price targets, daily closing summary, and the periodic background check (JobScheduler) |
| `Pdf.java` | PDF text extractor: xref tables/streams, object streams, RC4/AES-128/AES-256 decryption, fonts with ToUnicode |
| `Cas.java` | Reads CAMS/KFintech CAS text into funds, units, cost and transactions; name/ISIN matching helpers |
| `Tabular.java` | CSV/XLSX reader for broker holdings, MF holdings and tradebooks/order history |
| `Ledger.java` | Transactions (buy/sell/dividend), FIFO cost basis, automatic SIP instalments, XIRR solver |
| `Backup.java` | Password-encrypted (PBKDF2 + AES-GCM) automatic backups to a chosen folder |
| `CardStack.java` | Wallet-style stack of summary cards on the Dashboard (swipe up/down) |
| `IntroView.java` | Animated launch logo (also draws the static logo) |
| `Analytics.java`, `ForecastView.java` | Asset classes, past performance, Monte Carlo forecast and goal maths; the forecast fan chart |
| `Fx.java` | Market-mood page background and colour blending |
| `RollText.java` | Numbers that roll like an odometer when they change |
| `TreemapView.java` | Heat-map of holdings (squarified treemap) |
| `Avatars.java`, `Seasons.java` | Profile pictures for you and family members; festival and finance-day greetings |
| `Changelog.java` | Release history for More › About & what's new; generated from `CHANGELOG.md` by `tools/changelog.py` |
| `PageNav.java` | Reads a fund's NAV and date from an ordinary web page (e.g. Moneycontrol) |
| `UlipLink.java` | Matches ULIP fund names to their SBI Life listings online (Yahoo Finance) |
| `AppDialog.java` | Every pop-up in the app's style: rounded card, app fonts, pill buttons, filled text fields, rich menu rows |
| `RefreshRing.java` | Round ↻ button: progress ring while refreshing, countdown to the next auto refresh |
| `PullRefresh.java` | Pull down from the top of a page to refresh prices |
| `WatchLink.java` | Phone ⇄ watch sync over Bluetooth (secure RFCOMM between the paired devices) |
| `WatchActivity.java` | The Galaxy Watch app (built from the same sources as the `watch` module) |
| `Widget.java` | Home-screen widget (value, today's change, 1-month chart), drawn as an image |
| `Lock.java` | Fingerprint / PIN lock with the system BiometricPrompt |
| `Parser.java` | Reads pasted SBI Life NAV tables |
| `ChartView.java`, `DonutView.java`, `NavIcon.java`, `Ui.java` | Charts, donut, bottom-bar icons, colours, animations |

## Portfolio for Galaxy Watch (`Portfolio/watch`, package `com.uliptracker.watch`, v1.2)

A small Wear OS app (Wear OS 3 or later; built and checked here, not yet tried on a watch): total value, today's change, overall gain,
totals by type and top holdings, with family profiles and privacy mode. It reuses the phone
app's classes; `tools/pack_watch.py` packages `PortfolioWatch.apk` from the same `classes.dex`.

**How data gets to the watch.** No Google Play services: the phone app listens over Bluetooth
(RFCOMM, service UUID `6f1c2a4e-…`) while it's open and *More › Galaxy Watch › Sync with watch*
is on. The watch connects to its paired phone, the phone asks you once to allow that watch,
then sends your holdings, profiles and name as gzipped JSON (prices trimmed to 10 days). After
that the watch refreshes prices itself over its own connection; ULIP NAVs and other assets
update when you sync again.

**Install on the watch** (it isn't on the Play Store, so it's sideloaded with ADB):
1. Watch: Settings › About watch › Software information › tap *Software version* 5 times.
2. Settings › Developer options: turn on *ADB debugging* and *Wireless debugging*.
3. Wireless debugging › *Pair new device* shows a code and IP:port. On a computer on the same Wi-Fi:
   `adb pair IP:PORT CODE`, then `adb connect IP:PORT` (the address shown on the Wireless debugging
   screen), then `adb install PortfolioWatch.apk`.
   Without a computer, an ADB app on the phone (e.g. Bugjaeger) can do the same.
4. Turn Wireless debugging off again afterwards to save battery.

## Dial Clock (`DialClock/`, package `com.dialclock`)

`ClockView` draws the rotating-dial clock. `ClockWallpaper` is the live wallpaper,
`ClockDream` is the screensaver, and `MainActivity` is the full-screen clock with the
"Set as wallpaper" button.

## Building

**Android Studio (easiest):** File › Open, choose the `Portfolio` or `DialClock` folder, let
Gradle sync, then Build › Build APK(s). It needs Android SDK 34.

**What I used here (no Android SDK):** each project's `tools/pack.py` writes the binary
manifest, resource table and APK v2 signature in Python. The steps are:

```bash
javac --release 8 -cp android.jar -d build/classes app/src/main/java/com/uliptracker/*.java
(cd build/classes && jar cf ../classes.jar com)
java -cp dx.jar com.android.dx.command.Main --dex --min-sdk-version=26 --output=build/classes.dex build/classes.jar
python3 tools/pack.py build/classes.dex Portfolio.apk              # needs Pillow + cryptography
python3 tools/pack_watch.py build/classes.dex PortfolioWatch.apk   # the Galaxy Watch app
```

`pack.py` expects the shared helper from Dial Clock's `tools/pack.py` (it imports it as `pack`).
Copy that file next to it, or change the `sys.path` line at the top.

## Signing key (keep private)

`signing/` holds the private key the installed APKs were signed with. You need it to install
updates over the apps already on your phone. Apps built in Android Studio use its own debug key,
so you'll have to uninstall the old app first, which deletes its data. **Make a backup first**
(More › Share backup).
Don't share this key with anyone or upload it publicly.

## Releases

See [CHANGELOG.md](CHANGELOG.md). When releasing: add the version at the top of CHANGELOG.md, bump the version in
`tools/pack.py` and `app/build.gradle`, then run `python3 Portfolio/tools/changelog.py CHANGELOG.md Portfolio/app/src/main/java/com/uliptracker/Changelog.java`.
