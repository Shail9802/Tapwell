# Tapwell 3.1.0 visual review

Device: Samsung S21 FE (SM-G990E), One UI launcher. Screenshots and UI hierarchies are kept locally under `build/uiqa-3.1.0/` because they contain personal account and launcher information.

Reviewed on the device:

- All five main tabs in light and dark themes.
- Focus app list, side-by-side actions, daily-limit dialog, removal confirmation, app picker and work-break configuration.
- Water and other habit cards, custom-habit creation, scrolled Water interval/goal/serving/repeat controls and paired-tag management.
- Sleep plan, empty history, Options menu, plan explanation, goal editor and wake-time picker including its keyboard.
- Activity Today and Last 7 days, usage chart, Focus and habit charts, and scrolled count summaries.
- All four Settings categories; time format, regional-unit card, country picker and measurement menu.
- Reminder alerts, quiet hours, meal breaks, account, appearance, About, NFC automation, device administration and Water sync screens.
- Existing habit and activity widgets, widget configuration and two distinct phases of the sample-only water surface animation.

The review found and corrected inconsistent card insets, floating setting groups, repeated bar-chart rules, scattered Sleep actions, oversized optional-reminder controls, dropdown-arrow alignment, clipped settings-category navigation and low-resolution water frames. The final targeted recheck covers these corrections.

Limits: this is a visual review of the reachable states on this phone, not proof of every possible runtime state, font scale, screen size or launcher. No real drink, sleep diary entry, account change, permission grant, tag replacement or destructive action was performed. The app-picker Add action was left untouched following automatic approval review; its confirmation code was inspected. Physical NFC scans, active-session history and real health exports were not exercised in this visual pass. Water motion was verified in the native RemoteViews preview; the launcher's zero-progress widget correctly remains still.

Validation: 42 unit tests, release build, APK signature verification and installation over the existing app. Original dark theme restored after light-theme checks.
