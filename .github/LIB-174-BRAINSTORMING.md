# LIB-174: Brainstorming — Follow-ups & Improvements

> **Quick Start for Contributors**
> - Read this doc before working on library/reader/vault features
> - Completed fixes are marked ✅ with commit hash
> - New ideas are numbered #1-#48 (highest priority first)
> - Draft issue for EPUB Two-Up: `.github/LIB-175-EPUB-TWO-UP-DRAFT.md`

## Completed Robustness Improvements (commit 5a6f80e)

### What Changed
- Added `recoveredCount` tracking + logged success/failure per URI
- Wrapped `observeVaults().first { it.isNotEmpty() }` in `withTimeout(2000 ms)`
- Falls back to scan on timeout instead of blocking indefinitely

---

## Remaining Ideas (from original LIB-174 brainstorming)

### 1. Test Coverage Gap
**Opportunity:** No test validates timing relationship between recovery and scan.

**Approach:** `LibraryViewModelTest.kt` using `runTest`, clear DB, simulate persisted URIs, assert scan only runs after recovery emits non-empty.

---

### 2. UI Feedback for Recovery
**Problem:** User sees no indication recovery happened.

**Proposal:** Show one-shot snackbar: "Recovering vaults from previous session…"

---

### 3. Empty Recovery Edge Case
**Observation:** If `recoveredCount > 0` but scanner still emits `Completed(0)` (all vault URIs stale), user sees "0 items" with no context.

**Proposal:** If recovery succeeds but scan reports 0, emit `RecoveryFailed` UI state + hint to rescan.

---

### 4. Vault Grouped Items Test
**Opportunity:** Confirm vault grouping is correct after recovery. The UI groups `allItems` by vault, but no end-to-end test verifies recovery preserves correct `vaultFolderId`.

---

### 5. Memory Leak Check
**Quick win:** Run LeakCanary after recovery scenario. Room `Flow` + `withTimeout` could leak if coroutines aren't correctly scoped.

---

## Additional Brainstorming (2026-04-28)

### 6. VaultManager Concurrent Access
**Problem:** Multiple rapid vault add/remove operations could race on `persistedVaultUris()` + `persistPermission()` + `releasePermission()`.

**Proposal:** Wrap vault folder operations in a single `Mutex` or use `ConflatedBroadcastChannel` for vault state changes.

---

### 7. Scan Cancellation & Job Leak
**Problem:** If user taps "Refresh" while a scan is running, `triggerScan()` returns early — no explicit cancellation of flow collection.

**Proposal:** Track `val scanJob: Job?` and `scanJob?.cancel()` before launching new scan.

---

### 8. LibraryItem Caching Pressure
**Problem:** `getLibrary()` is queried multiple times in `uiState` combine — potential duplicate work.

**Proposal:** Cache `getLibrary()` in a singleton use case with `shareIn(WhileSubscribed(5s))`.

---

### 9. VaultGroupedItems Recomposition Pressure
**Problem:** Full `items.groupBy { }` recomputation on every `uiState` change — O(n²) on large libraries.

**Proposal:**
- Memoize via `derivedStateOf { items.groupBy { ... } }` in UI layer, OR
- Precompute in ViewModel with invalidation on vault/items changes

---

### 10. Smart Vault Reassignment on Re-Mount
**Problem:** USB drive reappears at different URI (e.g., `/storage/ABCD-1234` vs `/storage/DCBA-4321`) — existing vaults point to old path.

**Proposal:**
- Periodically query `ContentResolver.query(DocumentsContract.Document.COLUMN_LAST_MODIFIED)` to detect re-mounts
- Or: Allow "re-link" UI that repoints vault URIs without full rescan

---

### 11. Offline-First Vault Health
**Problem:** Currently validates access only when reader opens file.

**Proposal:** Background job proactively tests `uri.path?.fileDescriptor()` readability weekly. Emit health metric: `"healthy" | "permission_missing" | "io_error"`.

---

### 12. Vault Grouping Heuristics
**Problem:** `vaultGroupedItems` groups only by vault folder.

**Proposal:** Secondary grouping by last-read date:
- "Currently Reading"
- "Finished in last 7 days"
- "Stale"

Uses `observeCurrentlyReading.book()` + timestamps to compute.

---

### 12b. Continue Cards Heuristics
**Current State:** Only shows `currentBook + currentAudiobook` — last-opened item for each format.

**Opportunity:** Expand to show more relevant "continue" items:
- Top 3 most-recently-opened items (regardless of format)
- "Continue reading" + "Continue listening" + "Reopen last item"
- Persist last-opened item across app restarts (already implemented), but add "recent history" for multi-format readers

**Design Questions:**
- How to handle mixed-format users? (e.g., reading EPUB in morning, audio at night)
- Should "reopening" count as "continue" if >24h since last session?

---

### 12c. Search Scope Filter
**Problem:** Library search queries all items — no way to filter by vault before typing.

**Proposal:** Add "Scope" dropdown next to search bar:
- All Vaults
- [ vault 1]
- [ vault 2]

**UX Flow:**
1. User taps vault filter chip → selects vault
2. Search bar placeholder changes to "Search My Vault"
3. Search is scoped to selected vault only (faster, more targeted)

---

### 13. Unit Test Coverage — Coroutines Test Infrastructure
**Problem:** `LibraryViewModel.init` race wasn't caught pre-LIB-174.

**Proposal:** Add `coroutines-test` module with `TestDispatcher` injection for `viewModelScope`.

---

### 14. GitHub Repo Link in README
**Opportunity:** `FUNDING.yml` mentions `libravault-xyz` but README doesn't link the repo.

**Proposal:** Add GitHub star/badge + direct link to the repository in the README.

---

### 15. CI Status Badge
**Opportunity:** No CI badge in README — helps contributors understand build status.

**Proposal:** Add GitHub Actions badge to README top.

---

### 16. EPUB Two-Up Reading Mode (Tablet Optimized) — **LIB-175**
**Problem:** Single-column EPUB rendering wastes screen real estate on tablets. No side-by-side reference mode.

**Proposal:**
- Detect tablet (screen width ≥ 600 dp)
- Offer "Two-Up" toggle in reader settings
- Two columns: left page (odd), right page (even)
- Sync scroll by chapter + offset, or enable independent scrolling via scroll-aware gesture (e.g., two-finger tap → toggle sync)

**UX Flow:**
1. User opens EPUB on tablet
2. Reader UI shows "Two-Up" checkbox in overflow menu
3. When enabled, `PageProvider` returns pairs of pages
4. `PDFRenderer` or `EPUBWebView` renders two-page spread
5. Scroll sync: track `yOffset` offset per column, apply delta on tap to sync

**Technical Notes:**
- Reuse existing `ReaderFragment` + `ReaderViewModel` architecture
- New `ReaderState.showTwoUp: Boolean` state in ViewModel
- Layout switch in `reader_epub.xml` using `ViewStub` or conditional `ConstraintLayout`
- No breaking changes to phone UX (single column always)

**Competitor Precedent:**
- **Libby:** Two-up on iPad/tablet, single on phone
- **Mantano:** "Facing Pages" toggle in settings
- **Google Play Books:** Two-up automatically on tablets

---

### 17. EPUB Night-Mode Rendering (Dark Invert)
**Problem:** Reading EPUBs in dark rooms causes eye strain; most readers force dark UI but keep bright text.

**Proposal:**
- EPUB-specific "Night Mode" (not just app theme toggle)
- Invert *only* the content background to dark, text to light
- Apply via CSS injection: `body { background: #121212 !important; color: #f0f0f0 !important; }`
- Offer fade animation to reduce jarring transition

---

## 2026-04-28 Additional Ideas

### 18. Readium Kotlin Toolkit LCP Support
**Problem:** v2 roadmap mentions DRM (Readium LCP), but current version uses vanilla Readium Kotlin Toolkit without LCP.

**Proposal:**
- Audit Readium Kotlin Toolkit 3.x LCP support
- Identify required dependencies and custom player hooks
- Create demo EPUB-LCP file for testing (or use O'Reilly sample)

---

### 19. Search Index Rebuild Trigger
**Problem:** Library search is powered by `LibraryItem` entities in Room — no way to rebuild search index after bulk metadata change.

**Proposal:** Settings toggle "Rebuild Search Index" that truncates FT3 table and re-encodes all items.

---

### 20. Reader Zoom Presets
**Problem:** Font scaling 0.8–2.0× is too granular; user wants "comfortable reading" presets.

**Proposal:**
- Add `TextScalePreset` enum: `compact`, `comfortable`, `large`, `extraLarge`
- Map to underlying Float (e.g., `comfortable = 1.2f`)
- Persist per-document

---

### 21. EPUB Table of Contents Navigation
**Observation:** EPUBs have NCX/OPF TOC but reader only supports chapter boundaries from Readium.

**Proposal:** Expose navigation drawer (hamburger → TOC) with hierarchical links to chapters/sections.

---

### 22. Audio Chapter Marker Editing
**Problem:** Users cannot add custom markers in MP3/M4B files without existing CHAP tags.

**Proposal:** Allow user to tap a timestamp in playback progress → "Add Chapter Marker" → name it → persist to `library_items` as `custom_chapters: Json`.

---

### 23. Battery-Safe Background Scans
**Problem:** Background scan might run while device is charging-only or in doze.

**Proposal:** Check `BatteryManager.EXTRA_POWER_SOURCE` before scanning; skip if on battery < 20% or in Doze.

---

### 24. Export Highlights to Markdown
**Problem:** User wants to share notes with non-Libravault readers.

**Proposal:** Long-press highlight → "Copy" → "Export to Markdown" → saves to `/Downloads/Libravault/Clips/`.

---

### 25. Auto-Continue Last Title on Launch
**Problem:** User opens app, navigates to library, then finds correct book manually.

**Proposal:** If `lastOpenedItemId` exists and file still accessible, launch reader immediately on cold start (settings toggle to opt-out).

---

## 2026-04-28 Competition & Trend Analysis

### Apple Books / Apple Books iPad UX
**Observed Features:**
- Two-up reading on iPad (automatic, no toggle)
- "Current Page" navigation in portrait vs "Facing Pages" in landscape
- Tap bottom to reveal scroll bar (opacity-based)
- Page-turn animation (flip vs slide, customizable)

**Libravault Alignment:**
- We have two-up brainstorming (LIB-174 #16), but only for tablets
- Consider adding "Page Scroll Indicator" during continuous scroll mode
- Page-turn animation: EPUB-only, configurable (flip vs slide)

### Mantano Free EPUB Reader
**Observed Features:**
- "Night Mode" (sepia) vs "Dark Theme" (full black)
- Smart font scaling (presets: small, normal, large)
- Night mode applies CSS injection to body background + text color

**Libravault Alignment:**
- We already plan Night Mode (#17)
- Missing: Sepia overlay vs pure black
- Font scaling presets (#20) align well

### PocketBook / Aldiko
**Observed Features:**
- Text-to-Speech (TTS) integration
- "Sync with audiobook" when both EPUB + MP3 present in same folder
- TTS speed 0.5–4.0×, voice selection (system/default)

**Libravault Alignment:**
- v1.1 roadmap includes TTS
- Sync EPUB+Audio feature could be unique differentiator
- Consider "Auto-Sync" when EPUB + MP3/M4B share filename prefix

### Libby (Books by OverDrive)
**Observed Features:**
- Highlight+Note export to Notion, Evernote, PDF
- Bookmark folder organization
- "Continue Reading" progress bar on cover card

**Libravault Alignment:**
- Export highlights (#24) is in brainstorming
- Missing: Folder organization for bookmarks
- Progress bar on cover card is already in library UI

### Google Play Books Web Reader
**Observed Features:**
- Horizontal swipe to change pages (phone), vertical scroll (tablet)
- "Tablet Mode" auto-enables two-up
- Settings drawer persists across sessions

**Libravault Alignment:**
- We have tap zones and swipe detection; could add swipe threshold setting
- Settings persistence already implemented via `EpubPreferences`
- Tablet two-up (#16) aligns well

---

## 2026-04-28 Technical Debt & Refinements

### 26. EPUB Table of Contents (NCX/OPF Navigation)
**Current State:** Readium navigator supports internal navigation but TOC not surfaced in UI.
**Proposal:** Add "TOC" icon in top bar → drawer list → tap to navigate. Reuse `Publication.tableOfContents` from Readium.

---

### 27. EpubPreferences Injection Pattern
**Current State:** Settings are pushed via `navigator.submitPreferences()`.
**Risk:** No diffing — every recomposition sends full settings.
**Proposal:** Cache `EpubPreferences` and diff before submitting; avoid thrashing.

---

### 28. Scanner Background Threading
**Current State:** `VaultScanner.scan()` runs on main dispatcher.
**Risk:** Disk I/O on main can block UI.
**Proposal:** Offload to `Dispatchers.IO` + withContext where needed.

---

### 29. Highlight Storage Schema
**Current State:** `Highlight.entity.kt` stores `positionRef` as String (CFI or page:N).
**Risk:** CFI can be hundreds of chars, no index on positionRef.
**Proposal:** Add `positionRefHash: String` column + index for faster lookups.

---

### 30. Cover Art Placeholder Strategy
**Current State:** No cover art fallback — blank until EPUB metadata extracted.
**Proposal:** Generate placeholder SVG from title initials or use generic book icon.

---

## WCAG / Accessibility Updates (2026-04-28)

### LIB-176 ✅ **FIXED** (commit a4948bb)
**Problem:** WCAG AA contrast failure on dark mode.

**Fix:** Updated `DarkSurfaceVar` from `#3D1F07` to `#522A0D`.  
**Result:** Contrast ratio improved from 2.85:1 to ~5.2:1 (meets WCAG AA 4.5:1 minimum).

---

### LIB-178 ⚠️ **RECTIFIED** (commit 9899186)
**Problem:** `LIB-178` commit incorrectly added `decorative=true` to 9 icons instead of content descriptions.

**Fix:** Reverted icons to explicit content descriptions:
- Folder icons (vault filter chips, section headers) now have `contentDescription = "Folder"`
- Settings icon (`TextFields`) now has `contentDescription = "Text formatting"`
- Bookmark icons now have `contentDescription = "Bookmark"`

**Lesson:** Always test a11y tools with TalkBack before merging—even simple icon changes need explicit descriptions.

---

### 43. Color Contract System — WCAG AA Compliance Checklist
**Opportunity:** Proactive color contrast validation before release.

**Proposal:** Add pre-commit hook or Gradle task that runs:
- `color-contrast-check` for all theme colors against white/black
- Fallback to Material 3 color scheme if custom colors fail AA
- Fail CI if any contrast ratio < 4.5:1 (text) or 3:1 (large text)

**Tooling Options:**
- `color-utils` Gradle plugin (custom)
- `a11y-contrast-checker` CLI (open-source)

---

## Next Steps (for someone picking up LIB-174)

### 🚀 High Priority

1. **LIB-175: EPUB Two-Up Reading Mode**
   - Draft issue: `.github/LIB-175-EPUB-TWO-UP-DRAFT.md`
   - ~2–3 days MVP (tablet layout + toggle + scroll sync)
   - Aligns with Libby/Mantano/Google Play Books UX

2. **LIB-174 #7 Scan Cancellation** ✅ **DONE** (commit e7a0302)
   - Cancel old scan job on re-trigger
   - No more job leaks on repeated Refresh taps

---

### 🔍 Medium Priority

| # | Idea | Status | Notes |
|---|------|--------|-------|
| 16 | EPUB Night-Mode | Ready to build | CSS injection, ~1 hour |
| 26 | EPUB TOC Drawer | Low effort | Reuse Readium `Publication.tableOfContents` |
| 30 | Cover Placeholder | Quick win | SVG from title initials |

---

### 📦 Low Priority / Long-Term

| # | Idea | Effort |
|---|------|--------|
| 28 | Scanner Background Threading | Medium (thread safety review) |
| 29 | Highlight DB Index | Medium (migration + query update) |
| 27 | EpubPreferences Diffing | Low (state caching) |
| 24 | LibraryItem Caching | Medium (redesign) |

---

### 🧪 Testing Improvements

| # | Idea | Notes |
|---|------|-------|
| 1 | Coroutine Test Infrastructure | Add `TestDispatcher` injection for ViewModel |
| 2 | Vault Recovery Race Test | Verify `triggerScan()` only runs post-recovery |

---

### 📊 Quick-Start Guide for New Contributors

1. Read `.github/LIB-174-BRAINSTORMING.md` (this doc)
2. Pick an idea from **High Priority** or **Medium Priority**
3. Check for existing PRs/issues before starting
4. See `.github/LIB-175-EPUB-TWO-UP-DRAFT.md` for an example issue spec
5. Open a draft PR labeled `WIP: LIB-17X: <your feature>`

---

### 41. Feature Flags Architecture — `FeatureFlags.kt` stub ✅ **NEW**
**Current State:** Empty `FeatureFlags.kt` with enum of 5 features: `PARALLEL_SCANNING`, `SCAN_FORMAT_BREAKDOWN`, `SCAN_HEALTH_DASHBOARD`, `SCAN_PREVIEW`, `SMART_RESUMPTION`.

**What's Next:**
- DataStore integration for persistent flags (debug: toggle via Settings → Advanced → Experimental Features)
- Production: all flags disabled by default (release notes for opt-in)
- @Composable helper `featureFlag(Feature)` for UI conditional rendering

**Implementation Plan:**
1. Wire up `DataStore<Preferences>` for flag persistence
2. Create Settings UI screen "Experimental Features"
3. Add runtime check in `LibraryScanner`, `VaultManager`, etc.
4. Add flag override for unit tests (`FeatureFlags.override()`)

---

### 42. LibraryScanner Progress API Improvements ✅ **NEW**
**Fix:** `ScanProgress.Completed` now includes `processed` count (not just `total`) for more accurate completion tracking.
**Fix:** `ScanProgress.Error` now includes optional `throwable` for debugging.

**Impact:** UI can now distinguish between "0 items processed" vs "0 total" on empty vaults.

---

*Last updated: 2026-04-28 08:15 UTC* | *Current HEAD: a4948bb*

## New Ideas (2026-04-28) — Not Yet Added to Main List

### 44. Vault Health Dashboard
**Problem:** No visibility into vault health — missing permissions, stale paths, I/O errors.

**Proposal:** Add Settings → Advanced → Scan Health Dashboard showing:
- `healthy` vaults (valid URIs + readable)
- `permission_missing` (URI revoked by user)
- `io_error` (readable URI but file access fails)

**Tech:** Background worker checks `contentResolver.openFile(uri, "r")` weekly, stores metrics in `EpubPreferences`.

**Effort:** ~1 day MVP, +1 day dashboard UI.

---

### 45. EPUB Text Scaling (Accessibility)
**Problem:** No font size adjustment in EPUB reader — hard for low-vision users.

**Proposal:** Add `reader.textScale: Float` preference (default `1.0f`). Inject via `navigator.submitPreferences()`.

**Competitor precedent:** Mantano has 80%-200% scaling; Libby has 3-step slider.

**Effort:** ~0.5 day (preference + CSS injection).

---

### 46. Scan Performance Profiler
**Problem:** No data on scan times per vault — could optimize hot paths.

**Proposal:** Instrument `VaultScanner.scanFolder()` timings. Log `duration_ms` per vault. Add graph in Settings.

**Tech:** Wrap `scanFolder()` in `measureTimeMillis()`, emit `ScanProgress.Timing(vaultId, duration)`.

**Effort:** ~0.5 day (profiling), +0.5 day (Settings UI).

---

### 47. Library Search Debounce Tuning
**Problem:** Current debounce is 300ms — too slow for fast typists, too fast for slow typists.

**Proposal:** Make debounce configurable via Settings → Search → Debounce (200/300/400ms).

**Tech:** Add `searchDebounceMs: Int` to `EpubPreferences`, pass to `delay()` in `onSearchQueryChanged()`.

**Effort:** ~0.25 day.

---

### 48. Swipe Threshold Customization
**Problem:** Swipe dismissal in player is fixed — no way to adjust sensitivity.

**Proposal:** Add `player.swipeDismissThreshold: Dp` preference (default `120.dp`). Increase threshold to reduce accidental swipes.

**Tech:** Read `swipeDismissThreshold` in `PlayerViewModel`, apply to `SwipeToDismissBox` swipeable state.

**Effort:** ~0.25 day.

---

*Last updated: 2026-04-28 08:15 UTC* | *Current HEAD: a4948bb*
