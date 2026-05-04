# LibraVault — Session Change Log

**Date:** 2 May 2026  
**Branch:** `feature/pro-upgrade`  
**Build tested:** `feedback-1.apk` (fdroid-debug, ~97 MB)

---

## Round 1 Feedback

### 1. TTS button greyed out for PDF files

**Problem:** The TTS (text-to-speech) icon in the reader toolbar was always enabled, even when reading a PDF where TTS is not supported.

**Fix:** Added `isTtsAvailable: Boolean = true` parameter to `ReaderTopBar` in `ReaderComponents.kt`. The `ReaderScreen` passes `isTtsAvailable = item.format == MediaFormat.EPUB`, so the button is visually disabled (greyed out) for PDF and audiobook items.

**Files changed:**
- `feature/reader/src/main/kotlin/xyz/libravault/feature/reader/components/ReaderComponents.kt`
- `feature/reader/src/main/kotlin/xyz/libravault/feature/reader/ReaderScreen.kt`

---

### 2. Seek bar thumb changed from bar to dot

**Problem:** The player seek bar used the default Material3 `Slider` thumb, which renders as a vertical bar. The desired style is a circular dot.

**Fix:** Provided a custom `thumb` lambda to the `Slider` composable that renders a 20 dp circle using `Box + clip(CircleShape) + background(primaryColor)`.

**Files changed:**
- `feature/player/src/main/kotlin/xyz/libravault/feature/player/components/PlayerComponents.kt`

---

### 3. Seek bar jitter fixed; seeking now works while paused

**Problem:** The seek bar was stuttering during playback because `Slider.value` was bound directly to the external `positionMs` state, which recomposes on every playback tick, snapping the thumb back. Seeking while paused also didn't work for the same reason.

**Fix:** Introduced local `isDragging` and `dragFraction` state inside `PlayerSeekBar`. While the user is dragging, `dragFraction` controls the slider position and the external `positionMs` is ignored. When the drag ends (`onValueChangeFinished`), the final position is committed via `onSeek`. This decouples the slider from playback ticks and makes seek-while-paused work correctly.

**Files changed:**
- `feature/player/src/main/kotlin/xyz/libravault/feature/player/components/PlayerComponents.kt`

---

### 4. Grimoire background icon removed from player

**Problem:** A grimoire (book) image was rendered as a background behind the play/pause button in `PlaybackControls`, adding unnecessary visual noise.

**Fix:** Removed the wrapping `Box` and the `Image` composable (grimoire background). `PlaybackControls` now renders the controls directly without any background image. Three related unused imports were also removed.

**Files changed:**
- `feature/player/src/main/kotlin/xyz/libravault/feature/player/PlayerScreen.kt`

---

### 5. Dark and Light reading themes now visually distinct

**Problem:** Selecting "Dark" or "Light" in reader settings both applied the same colour scheme — the system `darkTheme` flag was used as the fallback for both, making them identical.

**Fix:** Added explicit `when` branches in `Theme.kt`:
- `ReadingTheme.DARK` → always applies `DarkColorScheme`
- `ReadingTheme.LIGHT` → always applies `LightColorScheme`
- `ReadingTheme.SEPIA` → applies `SepiaColorScheme`

The system dark-mode flag is now only consulted when no reading theme is active (i.e., outside the reader).

**Files changed:**
- `core/ui/src/main/kotlin/xyz/libravault/core/ui/theme/Theme.kt`

---

## Round 2 Feedback

### 6. GitHub Sponsors removed from Settings

**Problem:** A "Sponsor on GitHub" section in Settings linked to GitHub, which is inconsistent with the app's privacy-first, offline design (no accounts, no internet).

**Fix:** Removed the GitHub Sponsors `SettingLabel` block. The "Support Development" section now shows only the Monero and Bitcoin donation address buttons (copy-to-clipboard).

**Files changed:**
- `feature/settings/src/main/kotlin/xyz/libravault/feature/settings/SettingsScreen.kt`

---

### 7. Vault management flow overhauled

**Problem:** After adding a vault folder, there was no easy way to return to the library. The vault management UI was embedded inline in the `LazyColumn`, making the layout cluttered and confusing. The "Add vault" button also toggled the panel open and closed (tap once to open, tap again to close), which felt unintuitive.

**Fix:**
- Converted vault management to a proper `ModalBottomSheet` that overlays the library screen. The sheet auto-dismisses on drag-down or after a vault folder is successfully picked.
- The FAB-style add button now sets `showAddVaultSheet = true` (open only); the sheet controls its own dismissal.
- After the folder picker returns a result, `showAddVaultSheet` is set to `false` so the user returns directly to the library with the scan already running.

**Files changed:**
- `feature/library/src/main/kotlin/xyz/libravault/feature/library/LibraryScreen.kt`

---

### 8. Format filter chips added to main library; Audio filter fixed

**Problem:** The format filter chips (EPUB, PDF, Audio) were only visible in search mode. Users browsing the main library had no way to filter by format. Additionally, the Audio filter chip used `MediaFormat.MP3.name` as its sentinel value, so M4B, OGG, FLAC, OPUS, and AAC audiobooks were excluded from results.

**Fix:**
- Format filter chips now appear at the top of the main library list whenever the user is not in search mode.
- The Audio filter sentinel was changed to the string `"AUDIO"`, and the filter predicate uses `it.format.isAudio()` to match all audio formats regardless of codec.
- Filter logic was extracted into a private extension function `List<LibraryItem>.applyFormatFilter(filter: String?)` and applied consistently to search results, single-vault views, and grouped vault views.

**Files changed:**
- `feature/library/src/main/kotlin/xyz/libravault/feature/library/LibraryScreen.kt`

---

### 9. EPUB tap navigation fixed (left/right page turn)

**Problem:** Tapping the left or right third of the screen in the EPUB reader was not turning pages. Returning `false` from `EpubNavigatorFragment.Listener.onTap` (the intended way to let Readium handle taps natively) had no effect in Readium 3.0.0-beta.2.

**Fix:** Explicit navigation calls are now made inside `onTap`:
- Left third: `navRef?.goBackward(animated = true)`
- Right third: `navRef?.goForward(animated = true)`
- Centre third: `currentOnCentreTap.value.invoke()`

All branches return `true` (tap consumed). A local `var navRef` variable is filled after the fragment is committed with `commitNow`, so it is available by the time the user taps. `navRef` is cleared in `onDispose`.

The `goForward`/`goBackward` methods were verified against the Readium navigator bytecode (`OverflowableNavigator` interface) before implementation.

**Files changed:**
- `feature/reader/src/main/kotlin/xyz/libravault/feature/reader/epub/EpubReaderScreen.kt`

---

### 10. Library scanner race condition fixed (new PDFs not appearing)

**Problem:** Triggering a rescan (via pull-to-refresh or vault addition) while a previous scan was still in progress would silently skip the new scan. `LibraryScannerImpl` uses an `AtomicBoolean` to guard against concurrent scans. The old `triggerScan()` cancelled the previous coroutine job but did not wait for its `finally` block to run, so the `AtomicBoolean` was still `true` when the new scan attempted `compareAndSet(false, true)`, causing it to bail out.

**Fix:** `triggerScan()` now captures the old job reference before overwriting `scanJob`, then calls `previousJob?.cancelAndJoin()` (suspending) inside the new coroutine. This guarantees the old scan's `finally` block has run — and the `AtomicBoolean` has been cleared — before the new scan begins.

**Files changed:**
- `feature/library/src/main/kotlin/xyz/libravault/feature/library/LibraryViewModel.kt`

---

### 11. PDF crash fixed for files opened from external apps (e.g. Telegram)

**Problem:** Opening a PDF shared from an external app (such as Telegram) caused the app to crash or display an infinite loading spinner. The content URI provided by an external `FileProvider` is not persistable — calling `ContentResolver.openFileDescriptor` on it without the original intent's permission context throws `SecurityException`.

**Fix:** The `DisposableEffect` in `PdfReaderScreen` that opens the PDF is now wrapped in a `try/catch`:
- `SecurityException` → shows "Permission denied — the file cannot be read from this source."
- Any other `Exception` → shows "Could not open the PDF: \<message\>"
- `null` `ParcelFileDescriptor` → shows "Could not open the PDF — file may be inaccessible."

The error is displayed in-place using a `Text` composable styled with `colorScheme.error`. No crash, no infinite spinner.

**Files changed:**
- `feature/reader/src/main/kotlin/xyz/libravault/feature/reader/pdf/PdfReaderScreen.kt`

---

## Test APK

`feedback-1.apk` — fdroid-debug build, ~97 MB, unsigned (debug keystore).

Install with:

```
adb install -r feedback-1.apk
```

All 11 changes are included in this build.
