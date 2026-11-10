# Tapwell

Tapwell is a native Android app for setting NFC-backed app limits and keeping up with water, stand breaks, and other habits. Scan a physical tag to open a distracting app for a short session or to confirm a habit.

Developed with love ❤️ by Shail. [GitHub profile](https://github.com/Shail9802)

Tapwell was vibe coded with AI assistance. The app is open source under the [MIT License](LICENSE). Bug reports and pull requests are welcome.

## Features

- **Focus sessions.** Lock selected apps or the YouTube Shorts player, unlock them for a timed session with an NFC tag, and set up a location-aware work break.
- **Habit reminders.** Track a daily water goal, take stand breaks, and create custom routines. Choose when reminders repeat and which habits require an NFC tag.
- **Flexible alerts.** Use Android notifications, optional reminder pop-ups, a chime, or vibration. Create extra reminders in Samsung Reminder or your calendar where supported.
- **Settings backup.** Connect Google Drive to back up app rules, NFC pairings, and habit settings and restore them on another phone.

## Appearance

Tapwell uses Jetpack Compose Material 3 with the phone’s dynamic colors and light or dark appearance.

Tapwell follows device colors by default. Its **Follow device** launcher icon uses plum and lime in standard mode and lets supported launchers tint the mark when themed icons are enabled. On Galaxy phones, the launcher switch is **Wallpaper and style → Color palette → Apply palette to app icons**.

In **Setup → Appearance**, you can turn off **Follow device colors** to use Tapwell’s palette. The icon has separate overrides. **Normal Android** provides the brand artwork, **Android Monet** uses the system accent palette, and **Samsung Monet** uses a softer wallpaper palette and a smaller mark. Your launcher can still apply its own icon tint.

## Install and set up

Tapwell requires Android 12 or later and an NFC-capable phone.

1. Download the `.apk` from the latest release and open it on your phone. Allow installation from that source when Android prompts you. [GitHub Releases](https://github.com/Shail9802/Tapwell/releases/latest) · [Samsung installation help](https://www.samsung.com/us/support/troubleshoot/TSG10001913/)
2. Open Tapwell and go to **Setup**. Turn on NFC and pair tags for **Focus**, **Water**, and **Stand**. Hold each tag against the back of your unlocked phone while Tapwell’s scanner is open. You can name and pair several tags per task, such as Home and Work.
3. From **Setup**, enable **Tapwell app blocker** in Android Accessibility. If the switch is restricted, open **Settings → Apps → Tapwell → ⋮ → Allow restricted settings**, then return to Accessibility. [Android’s restricted settings guide](https://support.google.com/android/answer/12623953)
4. Allow notifications and exact alarms in **Setup**. Then go to **Habits → Configure** to turn on and customize each reminder.
5. In **Setup → Reminder pop-ups**, allow Tapwell to appear over other apps. A due reminder can then open an animated prompt over the current app or the lock screen. Unlock the phone to see habit details and scan the tag or mark the habit done. Standard notifications still open the prompt when tapped.

## How the rules work

- **Focus.** Instagram and YouTube Shorts are selected by default. Use **Add app** for other installed apps. Opening a locked app takes you to a screen where you can scan your Focus tag for a session. The default session lasts 10 minutes. The Shorts rule targets the player in the official YouTube app and keeps regular browsing and videos available. YouTube UI updates can change which controls are exposed to Android.
- **Removal.** Select **Remove** next to a rule and scan your Focus tag. The rule stays in force for two hours and then expires automatically. You can cancel a pending removal before it expires.
- **Work break.** Set your location while at work, choose the radius, days, hours and break length, and scan your Focus tag to save the rule. Start a break from an app’s lock screen. Tapwell checks precise location at the start, allows one shared break per work window, and ends it no later than the end of that window.
- **Water.** Defaults to 1,000 mL per day and 250 mL per scan, or four servings. When the reminder is due, it retries every five minutes until you scan the Water tag. Every scan counts one serving and restarts the configured interval, which defaults to 60 minutes. Reminders stop when you reach the daily goal. The total resets at local midnight, and the first reminder of the new day is due one interval later.
- **Water notification.** The pending alert stays in the notification shade with a **Scan Water tag** action. If Android allows an individual swipe, Tapwell restores the alert silently and reopens the scanner when reminder pop-ups are enabled. Closing the scanner keeps the reminder pending. A matching tag clears the alert and restarts the timer.
- **Standing.** Scan the Stand tag to complete the current reminder and restart the interval. Overdue reminders retry every five minutes. Standing reminders continue while enabled.
- **Custom habits.** Open **Habits → Add habit** and enter a name and reminder interval. Turn on **Require NFC tag** to pair a separate tag from the habit card. Reminders start once the tag is paired. With that switch off, tap **Mark done**. Completion restarts the reminder interval.
- **Repeat alerts.** Turn **Repeat until done** on or off for each habit, including Water and Stand. The repeat interval defaults to five minutes and can be changed independently of the main interval. Turning repeats off gives you one alert per cycle.
- **Reminder alerts.** In **Setup → Reminder alerts**, switch between a gentle two-note chime with strong vibration and vibration only. The built-in chime gets a boost at low notification volume and softens at the top of the slider. For quiet alerts, use Tapwell’s vibration-only setting or Android’s Vibrate mode. Android’s Silent mode, Do Not Disturb, and notification channel settings still control delivery.
- **Test alerts.** Use **Send test alert** in **Reminder alerts** for a water preview or in a habit’s **Configure** screen for that habit. Open the notification and scan a paired tag when the prompt asks for one. A test scan leaves the habit timer and water total as they are.
- **System reminder drafts.** Open a saved habit’s **Configure** screen and choose **Share to Samsung Reminder** or **Add to calendar**, when available. Review the entry and save it in the receiving app. These are separate reminders. Use Tapwell to complete the habit, scan a tag, or restart its timer.
- **Google Drive sync.** Open **Setup → Google Drive sync → Connect Google Drive** on each device and choose the same Google account. Tapwell backs up app locks, paired tags, habit and alert settings, theme colors, and work rules in its private Drive app folder. On first connection, backed-up settings take priority where they overlap. Use **Sync now** to request an immediate refresh. Active unlocks, reminder timers, daily progress, and Android permissions are device-specific.
- **Manage tags.** Use **Setup → NFC tags** for Focus, Water, and Stand. Use **Manage tags** on an NFC habit card for that habit. Any paired tag works for its task. Adding, removing, or replacing tags on a paired task requires scanning one of its current tags.
- **Log between reminders.** Scan a paired habit tag or tap **Mark done** for a habit that uses manual logging. Confirm to log the habit and restart its interval. Paired Tapwell text tags also work from the unlocked Home screen. Water reminders stop at your goal, and you can still log extra drinks.
- **Meal snooze.** Open **Setup → Meal snooze** to enable or edit quiet windows. Lunch defaults to 12:30–14:00 and dinner to 19:30–21:30. Due alerts wait until the window ends.
- **Activity dashboard.** Open **Activity** to chart Focus prompts, exits, and app launches, along with habit alerts and completions. With Android Usage access, a donut chart shows each Focus app’s share of screen time, a weekly bar chart shows usage by day, and daily limit bars show time used and time left. Choose **Today** or tap a day in **Last 7 days** to see its breakdown. Usage and activity counts are local to this phone.
- **Daily focus limits.** Choose **Daily limit** next to a locked app and grant Android Usage access. Enter a daily allowance in minutes. The limit uses total foreground time for the app, including total YouTube time for the Shorts rule. Tapwell cannot read the configured timer from Digital Wellbeing, so enter the same number of minutes to mirror it.

## Google Cloud setup for this build

1. Enable the **Google Drive API** in one Google Cloud project for Tapwell.
2. Configure the OAuth consent screen. Add the Google accounts you will use as test users if the consent app is in Testing.
3. Under **Data access**, declare `https://www.googleapis.com/auth/drive.appdata` for the private configuration folder.
4. Create an **Android** OAuth client for package `app.tapwell` and the SHA-1 of the certificate used to sign the installed APK. Run `apksigner verify --print-certs app/build/outputs/apk/debug/app-debug.apk` to get the fingerprint. Register each signing certificate you use under the same Cloud project so the builds can access the same backup.

Tapwell uses Google Play services to request access to the private Drive app folder. Self-built APKs use the OAuth registration for their own signing certificate. Focus and habits work locally without a Google account.

## Test with Tagfold

1. Open a task’s tag manager in Tapwell and tap **Generate Tagfold code**. Copy the whole `tapwell-tag:…` value into a **Text** tag in Tagfold on a second Android phone.
2. Start emulating the tag in Tagfold, then tap **Add tag** in Tapwell. Keep both phones unlocked with both apps visible and hold their NFC antennas together.
3. To add a second emulated tag, reopen the tag manager to generate a fresh code. In Tapwell, verify the first tag, pull the phones apart, switch Tagfold to the second tag, and touch them together again. Either code can then complete the task.

Use a fresh code for each emulated tag. Anyone with the same code can emulate that tag, so keep codes you use outside testing private.

## Permissions and data

Accessibility lets Tapwell detect the foreground app and identify the YouTube Shorts player. NFC reads the tags you pair, and precise location is used when setting a work location or starting a work break. Tag IDs and the saved work location are included in the private Drive backup only when you connect Google Drive.

Android may delay five-minute retries during deep idle, even with exact alarms enabled. The phone owner can disable the accessibility service that enforces app locks. [Android’s alarm timing documentation](https://developer.android.com/training/monitoring-device-state/doze-standby)

## Build from source

Open this folder in Android Studio with JDK 17 or newer, Android SDK Platform 36, and Android SDK Build-Tools 36.0.0. From PowerShell, build the app and run the unit tests.

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

On macOS or Linux, run `./gradlew :app:assembleDebug :app:testDebugUnitTest`. The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`.

The bundled Gradle wrapper is distributed under its upstream Apache 2.0 license.
