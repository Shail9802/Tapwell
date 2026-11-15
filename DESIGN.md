# Tapwell — Material 3 Expressive

Reference: https://m3.material.io/ and https://m3.material.io/blog/building-with-m3-expressive

Tapwell uses native Compose MaterialExpressiveTheme and MotionScheme.expressive. Keep wallpaper-based dynamic colors and light/dark appearance. The current Expressive dependency is pinned to Material 3 1.5.0-alpha03 to match the existing Compose 1.9 toolchain; this API surface is experimental.

- Give the primary task a clear focal point: large Water intake, bedtime/wake-up numbers, or Focus state. Use primary/secondary containers selectively, not on every settings row.
- Use contrasting corner sizes: 36 dp for feature surfaces, 16–20 dp for supporting controls, and pill-like primary actions. Primary action shapes compress on press with a spring.
- Keep one obvious primary action per habit. Configuration is secondary; explanations remain expandable.
- Destination changes use a short fade/slide. Compose animation duration scaling remains respected. Widget water motion uses launcher-hosted ViewFlipper frames, never a background animation service or frequent alarm; reduced motion and battery saver use a still frame.
- Settings uses descriptive tappable rows and one horizontal category selector. Advanced device controls remain collapsed by default.
- Water uses the native wavy progress component inside the app and a data-driven wave fill in RemoteViews widgets. Text and accessibility descriptions must retain the actual amount and goal.
- Settings groups and Sleep sections share 20 dp content insets and a 28 dp card shape. Align headings, descriptions and controls to the same content edge; put secondary Sleep actions in Options rather than scattering buttons across the page. Charts use clean baselines without rules beneath individual bars.
- Informational dialogs use a common left edge for the logo, heading, body and link labels. Avoid the centered AlertDialog icon slot for these layouts; remove extra text-button content padding on inline links.
- Review UI changes on the connected phone with screenshots of every reachable screen, menu and dialog. Include scrolled content, both themes and widget states. Do not complete destructive or personal-data actions solely for visual testing; document any untested states.
- Motion supports interaction: spring press feedback, selected navigation icons, settings fades, smooth card resizing and animated progress/chart values. Honor the system animation scale; avoid decorative motion across reading content.
- Widgets share the system palette, larger outer corners, and adaptive layouts. A single habit fills the whole surface. Keep labels inside safe content padding and fill shapes clipped to their bounds.

Review native screens at the phone's real font/display scale and verify actions, empty states, light/dark appearance and reminder prompts whenever theme or dependencies change.
