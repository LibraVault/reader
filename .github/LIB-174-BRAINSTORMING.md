# LIB-174: Brainstorming — Follow-ups & Improvements

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
**Quick win:** Run LeakCanary after recovery scenario. Room `Flow` + `withTimeout` could leak if coroutines aren’t correctly scoped.

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

### 13. Unit Test Coverage — Coroutines Test Infrastructure
**Problem:** `LibraryViewModel.init` race wasn’t caught pre-LIB-174.

**Proposal:** Add `coroutines-test` module with `TestDispatcher` injection for `viewModelScope`.

---

### 14. GitHub Repo Link in README
**Opportunity:** `FUNDING.yml` mentions `libravault-xyz` but README doesn’t link the repo.

**Proposal:** Add GitHub star/badge + direct link to the repository in the README.

---

### 15. CI Status Badge
**Opportunity:** No CI badge in README — helps contributors understand build status.

**Proposal:** Add GitHub Actions badge to README top.

---

### 16. EPUB Two-Up Reading Mode (Tablet Optimized)
**Problem:** Single-column EPUB rendering wastes screen real estate on tablets. No side-by-side reference mode.

**Proposal:**
- Detect tablet (screen width ≥ 600 dp)
- Offer “Two-Up” toggle in reader settings
- Two columns: left page (odd), right page (even)
- Sync scroll by chapter + offset, or enable independent scrolling via scroll-aware gesture (e.g., two-finger tap → toggle sync)

**UX Flow:**
1. User opens EPUB on tablet
2. Reader UI shows “Two-Up” checkbox in overflow menu
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
- **Mantano:** “Facing Pages” toggle in settings
- **Google Play Books:** Two-up automatically on tablets

---

### 17. EPUB Night-Mode Rendering (Dark Invert)
**Problem:** Reading EPUBs in dark rooms causes eye strain; most readers force dark UI but keep bright text.

**Proposal:**
- EPUB-specific “Night Mode” (not just app theme toggle)
- Invert *only* the content background to dark, text to light
- Apply via CSS injection: `body { background: #121212 !important; color: #f0f0f0 !important; }`
- Offer fade animation to reduce jarring transition

---

*Last updated: 2026-04-28 07:20 UTC* | *Current HEAD: 5a6f80e*
