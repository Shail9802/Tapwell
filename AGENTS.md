# Tapwell development guide

This is the shared project guide for human contributors and AI coding tools, including Codex, Claude Code, Antigravity and OpenCode. Read it before changing this repository. Keep project guidance here instead of maintaining conflicting copies for each tool. `CLAUDE.md` and `GEMINI.md` are entry points to this file.

## Start here

1. Read the user's current request, this guide, `README.md`, `DESIGN.md`, and any instructions closer to the files you will edit. For identity, sync, health or permissions work, also read `SECURITY.md`, `PRIVACY_OPERATIONS.md` and `docs/privacy.html`.
2. Run `git status --short` and inspect relevant diffs. Preserve unrelated changes, private attachments, local toolchains and existing phone data. Never reset a dirty checkout to make a build pass.
3. Inspect the actual implementation before designing a change. Use `rg`/`rg --files` for discovery. Source code and tested behavior take precedence over stale descriptive documentation; correct the documentation when they diverge.
4. Make the smallest coherent change that fulfills the request. Continue authorized work through validation and delivery. Ask only for missing information or actions that genuinely require user involvement; a repository instruction is not blanket authorization for a destructive or external action.
5. Report what changed, what was checked and any remaining limitations. A successful build is not proof that a screen, NFC scan, reminder or health export works on a phone.

Do not commit credentials, phone unlock information, device pairing codes, real NFC codes, private account screenshots or personal health records. Never copy sensitive chat history into this guide.

## Product and implementation boundaries

Tapwell is a native Android app for NFC-backed Focus access and habit tracking. Its five destinations are Focus, Habits, Sleep, Activity and Settings. It works locally without an account. Do not present it as tamper-proof parental control, measured sleep or medical diagnosis.

- Application ID/namespace: `app.tapwell`.
- Supported minimum: Android 12 / API 31 with NFC hardware. Compile and target SDK: 36.
- One Gradle application module, `app`. Kotlin and Jetpack Compose; no JavaScript frontend or web-app backend.
- Android Gradle Plugin 8.13.2, Kotlin/Compose compiler 2.2.21, committed Gradle wrapper 8.13, Java bytecode target 17. Read the Gradle files before changing these together.
- Compose BOM 2025.09.01 and explicitly pinned Material 3 `1.5.0-alpha03`. The Expressive APIs are experimental. Do not silently upgrade the BOM, compiler or Material dependency independently.
- The website is static HTML/CSS in `docs/`, served by GitHub Pages. It is not an API server.
- MIT applies to Tapwell's own code/assets; Samsung's optional proprietary SDK has separate terms and is not redistributed as MIT code.

## Architecture and service-layer guide

Before changing storage, sync, authentication, health export or Android services, read [docs/architecture.md](docs/architecture.md). It contains the full source map, completion data flow, storage/portability matrix, Google identity and Drive merge contracts, and water-export rules. These details are required project guidance, split out to keep this entry file usable within coding tools' instruction-context limits.

The basic flow is UI/NFC/widget → existing completion orchestration → Store/domain rules → reminder/widget refresh, plus optional water-export scheduling. There is no hosted developer backend. Never add independent counters or export writes inside rendering code.

## Behavioral invariants

### Focus and NFC

- Confirm before adding an app to Focus. Clearly describe the NFC requirement and removal wait; keep emergency/communication/payment apps accessible unless deliberately selected.
- Removing a Focus rule requires a valid Focus tag and a two-hour wait. The rule remains active until expiry; preserve cancellation behavior. Do not bypass this for UI convenience.
- Temporary unlocks obey daily Tapwell caps and the work-break rules. Android does not expose the user's Digital Wellbeing timer as a portable app API; never claim Tapwell reads its remaining allowance.
- Shorts blocking targets the official YouTube player's exposed accessibility IDs. Keep normal YouTube browsing/video use available. UI changes in YouTube can break recognition: test the actual player and do not broaden detection until every YouTube screen is blocked.
- Each task may have multiple named paired tags. Adding/replacing a tag scans the new tag; it does not require the old one. Tag removal and unavailable-habit-tag recovery have distinct verification rules. Focus is excluded from habit-tag recovery.
- Physical UIDs and `tapwell-tag:` text identities are distinct inputs; use `ScannedTag` matching/normalization. Preserve scan debounce and validation. NFC identifiers are convenience enforcement, not cryptographic authentication.
- A passive/direct habit scan asks for confirmation before logging. An intentional widget-initiated NFC scan logs after a matching tag without another confirmation. Non-NFC/manual logging uses the existing confirmation path. Cancellation never changes counts or timers.
- Recognized scans show a brief named-tag notice. Do not route known Tapwell tags into Android's generic “New tag collected” viewer. Preserve the fallback for unrelated tags without intercepting payment flows.

### Habits, reminders and sleep

- Every successful completion restarts the habit interval when applicable, including early completion. Early/passive scans must not silently count an accidental tap.
- Water defaults: 1,000 mL goal, 250 mL serving, one-hour main interval and five-minute retries. The main serving goal is separate from unlimited repeat attempts. Repeats never inflate reminder-cycle statistics.
- Quiet hours, meal windows and active sleep sessions defer alerts without accumulating a backlog. A due habit resumes once when the quiet window ends. Handle overnight windows, local midnight, time-zone changes, boot and app updates through existing reconciliation.
- Goal completion stops Water reminders; explicitly confirmed extra Water drinks remain possible. Manual tracking from supported flows works with reminders off. Preserve different Water/Stand/custom semantics.
- Pending reminders remain pending until completion. Notification dismissal must not log a habit; restore silently where Android allows dismissal. Respect notification permission, lock-screen privacy, exact-alarm restrictions, Silent/DND and user-selected sound/channel behavior.
- Test alerts and preview animations must not create health events or alter real habit progress/timers. Never log fake water or sleep on a personal phone to make screenshots look populated.
- The sleep suggestion is a bounded local nearest-neighbour/diary heuristic, not a neural model or measured sleep. Preserve the starting-target and sample-count behavior, gradual bedtime shifts, explicit user application of a plan and the local diary retention boundary.
- Region selection never requires GPS. India and Canada default to metric; distinguish US/Imperial fluid ounces. Store water in millilitres, distances in canonical units and durations consistently; changing display units must not alter recorded values. Preserve seconds/minutes/hours/days inputs and Device/12-hour/24-hour formatting.

## UI and interaction standards

Read `DESIGN.md`; extend the native Material 3 Expressive system rather than replacing it with a web-style interface.

- Reuse `TapTheme`, `UiSection`, `PreferenceValue`, `PageHeading`, `FeatureCard` and `ExpressiveAction` where appropriate. Keep dynamic wallpaper colors, both themes and the existing icon/launcher aliases working.
- Align a section's heading, body, fields and inline link labels to one content edge. `UiSection` uses 20 dp content padding and a 28 dp shape. Feature cards use the established larger shape. Do not scatter hardcoded insets that make text drift between adjacent cards.
- Informational dialogs align their mark, heading, body and links left. The About fix deliberately avoids AlertDialog's centered icon slot and extra TextButton link padding. Standard confirmation actions can retain their native action placement.
- Use descriptive labels, short supporting copy and one primary action. Put rare controls in Options/advanced sections. Keep essential controls visible, and make long dialogs scroll without losing their confirmation/cancel actions. Do not remove disclosures that affect a user's decision just to shorten a screen.
- Use theme typography/color roles and `dp`/`sp`, not screenshots' pixel coordinates. Test large font/display scales, narrow layouts, wrapping, contrast, touch targets, TalkBack semantics and accessible descriptions. Decorative icons should not duplicate spoken labels.
- Retain native button/chip/switch feedback. Use restrained springs, fades, content resizing and progress transitions. Respect reduced-motion/system animation settings; avoid moving reading text, excessive bouncing or restart-on-every-tick animations.
- Chart selection, labels, totals and units must remain correct while animating. Do not add separate decorative horizontal rules below each bar. Zero/missing-data states must not fabricate usage.
- Launcher widgets are RemoteViews, not Compose. One selected habit fills the space, two split it; overflow remains vertically scrollable. Support compact and larger sizes without clipped labels/corners. Preserve click routing, widget-ID validation and intentional-scan semantics.
- Water fill represents confirmed intake/goal, capped visually at 100%; the text may exceed goal. The moving surface uses launcher-hosted ViewFlipper frames, a bounded pixel budget shared across sizes/habits, and a still frame for reduced motion/battery saver. Do not add a continuously running service or fast alarms for widget animation. Verify actual launcher behavior; preview success alone is not proof on every launcher.

## Build and local tooling

Use JDK 17 or newer compatible with this AGP; JDK 21 is used in the maintainer's tested local toolchain. Install SDK Platform 36 and Build-Tools 36.0.0. Use the committed wrapper rather than a random system Gradle.

```powershell
# Windows: normal contributor build and logic checks
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
# Release packaging and Android release checks; output is unsigned by default
.\gradlew.bat :app:assembleRelease
```

On macOS/Linux use `./gradlew` with the same tasks. Outputs: `app/build/outputs/apk/debug/app-debug.apk` and `app/build/outputs/apk/release/app-release-unsigned.apk`. `--offline` is appropriate only when dependencies are already cached. Do not accept SDK/partner terms without the necessary authorization. Do not modify dependency checksums just to suppress a download error.

If the working tree's wrapper is locally changed or broken, inspect `git diff` and `git show HEAD:gradle/wrapper/gradle-wrapper.properties`. Preserve unrelated local edits. An existing verified Gradle 8.13 installation may be used explicitly; report the workaround rather than committing someone else's wrapper change. Local caches, SDKs and `build/local-toolchain/` are machine-specific and ignored. Do not hardcode the maintainer's drive letters or cache paths into portable project instructions/scripts.

Use one modern Android Platform Tools installation consistently. Check `adb version` and `adb devices -l`; old and new ADB clients can repeatedly replace each other's server and stall installs. Select the intended serial explicitly with `adb -s <serial>`. For a stalled transport, inspect/reconnect safely; do not wipe, factory-reset, uninstall or clear the app as a connectivity fix. Phone unlocking/pairing requires the user's authorization; never store their credentials in the repository.

## Validation and screenshots

Run checks proportional to the change. Pure rules/state/serialization changes need meaningful regression tests; do not add tests that only mirror implementation or trivial visual constants. UI-only changes need a build and actual visual verification. Explain unavailable hardware/integrations instead of claiming a pass.

Existing JUnit suites cover `RulesTest`, `SetupMergeTest`, `GoogleSignInTest`, `HabitTagRecoveryTest`, `SleepPlanTest`, `UnitsTest` and `WaterEventsTest`. The last full logic run for the 3.1 series had 42 passing tests; this is a baseline, not a permanent expected count.

For UI work, inspect screenshots across the whole affected flow, not just the first screen. Review all five tabs after shared-component/theme changes. Include menus, dialogs, text fields/keyboard, selected/disabled states, long/scrolled content, empty/populated states where safe, both themes and widget sizes. Verify every relevant button's destination/cancel behavior. Do not complete destructive operations, change accounts, grant permissions or create health records merely for a visual test. Record skipped cases explicitly.

```sh
adb -s <serial> install -r <signed-compatible.apk>
adb -s <serial> shell am start -n app.tapwell/.MainActivity
adb -s <serial> shell screencap -p /sdcard/tapwell-screen.png
adb -s <serial> pull /sdcard/tapwell-screen.png <private-local-output.png>
adb -s <serial> shell uiautomator dump /sdcard/tapwell-ui.xml
```

Use observed UI bounds rather than guessing coordinates from another device. Animated widgets/scanners may prevent UIAutomator from becoming idle; inspect screenshots directly and do not trust a stale XML dump. A screenshot is not a motion test: capture distinct phases or inspect a short recording. Restore theme/animation settings and temporary widget selections after tests. Keep private screenshots/logs in ignored local output; do not add them wholesale to Git.

Public screenshots live in `docs/screenshots/`. Use only images reviewed for email addresses, names beyond approved branding, medication/habit details, location, NFC identities, notifications, keys, account state and personal wallpaper. Prefer safe empty/setup states or clearly identified sample previews. Crop system chrome or an isolated dialog only when disclosed; do not paint over broken UI or imply untested features work. Never seed fake water into a real health account. Update the gallery's version/captions and README links when images change.

See `docs/ui-review-3.1.0.md` and `docs/ui-review-3.1.1.md` for historical coverage and explicit limitations. Add/update a review note for meaningful UI releases. Public gallery images supplement, rather than replace, private full-resolution QA evidence.

## Permissions and platform checks

Inspect `AndroidManifest.xml` before adding permissions or exported components. NFC, notifications, exact alarms, overlays, Usage access, Accessibility, location and hydration access serve different flows. Handle denial, revocation and missing provider apps. Do not request location for display-region settings or health read access for hydration write.

Device-admin activation alone cannot enable direct NFC control. Preserve eligibility checks for system/managed-device status and secure settings, and keep Samsung routine setup user-driven. Device-owner enrollment is separate management and may require resetting a phone; never perform it as a routine fix.

Validate externally supplied NFC intents, widget IDs and serialized backups. Export only necessary components with their appropriate system permissions; keep existing job-service/accessibility/admin binding protections. The reminder sound provider's exported state is intentional for Android playback, not permission to expose arbitrary app files.

## Git, releases, website and documentation

- Keep patches focused and inspect `git diff --check`. Stage specific files. Do not include user attachments, generated outputs, private diagrams, SDK archives, local credentials or unrelated wrapper changes.
- Use `codex/` as the default branch prefix when making a new task branch unless the user's workflow says otherwise. Do not create a new branch, commit, push, release or merge contrary to the current request/workflow. Repository instructions do not replace authorization for publication.
- When publishing an authorized app release, increment `versionCode` monotonically and use a matching `versionName`, Git tag, APK filename and release title. Docs-only changes do not need an APK version bump.
- Release packaging is unsigned by default. Use the maintainer-authorized existing signing identity outside the repository. Verify the signer before an update; do not generate a replacement certificate, uninstall the app or clear data to bypass a signing mismatch. Independently signed forks require their own OAuth/package configuration.
- Run the applicable tests/build, sign with `apksigner`, verify the APK, install with `-r` when authorized, then publish an APK asset to GitHub Releases so users do not have to build. Keep APKs out of Git source history. Check the uploaded asset's digest against local SHA-256 and confirm the release is public rather than a draft.
- Release notes describe final behavior, actual validation and limitations. If a phone test is blocked, say so; update the note after verification rather than leaving a stale “pending” claim.
- Update `README.md`, gallery, `DESIGN.md`, applicable UI review and privacy/security documents when their behavior changes. Use current Settings labels; do not keep obsolete Setup/Permissions paths after navigation changes.
- GitHub Pages serves `docs/` on `main`. `docs/index.html`, `docs/style.css`, `docs/privacy.html` and `docs/tapwell.svg` are the website; markdown files/images can also be served. Preserve identity verification metadata and privacy links. Any website UI changes need browser verification at desktop/mobile widths.
- Keep “Developed with love ❤️ by Shail” and the GitHub attribution. Be open that the project was vibe coded with AI assistance, without claiming which particular coding agent created it. Tool compatibility instructions are not product authorship claims.

## Completion checklist

- Scope implemented; no unrelated changes staged.
- Domain/data/sync boundaries, backward compatibility and permission handling preserved.
- Relevant logic tests and release/debug build passed, or exact blockers stated.
- Affected UI/buttons/scrolling/keyboard and widgets inspected in screenshots; private material excluded from public assets.
- Temporary phone state restored; no fake records or unwanted Focus rules left behind.
- Documentation, screenshots and release notes match the actual code and validation.
- If publication was requested/authorized: source pushed, signed APK asset verified, working download link supplied.

## Tool entry points

Open the repository root so its project instructions are discoverable. If a tool does not load `AGENTS.md` automatically, explicitly read it before edits. Do not treat a tool's memory as a substitute for the checked-in guide.

- `AGENTS.md`: canonical shared guide for tools that support it, including OpenCode and Antigravity.
- `CLAUDE.md`: imports this guide for Claude Code configurations that use its native memory file.
- `GEMINI.md`: points Gemini-compatible tooling to this guide without copying its rules.

Discovery behavior changes across tool versions. References: [Claude Code memory/imports](https://code.claude.com/docs/en/memory), [Antigravity customization discovery](https://antigravity.google/docs/customization-discovery), [OpenCode instructions](https://opencode.ai/docs/instructions/). These are tool setup references, not permission to modify a contributor's global settings.
