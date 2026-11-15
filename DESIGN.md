# Tapwell — Material 3 Expressive

Reference: https://m3.material.io/ and https://m3.material.io/blog/building-with-m3-expressive

Tapwell uses native Compose MaterialExpressiveTheme and MotionScheme.expressive. Keep wallpaper-based dynamic colors and light/dark appearance. The current Expressive dependency is pinned to Material 3 1.5.0-alpha03 to match the existing Compose 1.9 toolchain; this API surface is experimental.

- Give the primary task a clear focal point: large Water intake, bedtime/wake-up numbers, or Focus state. Use primary/secondary containers selectively, not on every settings row.
- Use contrasting corner sizes: 36 dp for feature surfaces, 16–20 dp for supporting controls, and pill-like primary actions. Primary action shapes compress on press with a spring.
- Keep one obvious primary action per habit. Configuration is secondary; explanations remain expandable.
- Destination changes use a short fade/slide. Compose animation duration scaling remains respected. Do not run continuous widget animations.
- Settings uses descriptive tappable rows and one horizontal category selector. Advanced device controls remain collapsed by default.
- Water uses the native wavy progress component inside the app and a data-driven wave fill in RemoteViews widgets. Text and accessibility descriptions must retain the actual amount and goal.
- Widgets share the system palette, larger outer corners, and adaptive layouts. A single habit fills the whole surface. Keep labels inside safe content padding and fill shapes clipped to their bounds.

Review native screens at the phone's real font/display scale and verify actions, empty states, light/dark appearance and reminder prompts whenever theme or dependencies change.
