package xyz.libravault.feature.library

import xyz.libravault.core.domain.model.LibraryItem

/**
 * Pure decision logic lifted out of [LibraryScreen]'s composable body.
 *
 * `LibraryScreen.kt` is the largest file in the repo (1,344 lines) and was at
 * **8.7% coverage** — 619 uncovered lines, the single biggest gap in the
 * inventory (docs/TEST_COVERAGE_PRD.md, S2). Most of that is genuinely layout,
 * which belongs behind screenshot tests rather than unit tests. But the part
 * that decides *what the screen shows* was a chain of interlocking `if`s
 * scattered through a ~500-line composable, where it could not be tested at
 * all and where a subtle mistake — two sections showing at once, or a section
 * silently never showing — is invisible in review.
 *
 * Extracted here per `AGENTS.md`'s guidance to make such logic `internal` so it
 * can be unit-tested, rather than deferring the whole screen to manual QA.
 *
 * Nothing here knows about Compose.
 */

/**
 * Which body the library grid renders. Exactly one applies — that mutual
 * exclusivity is the property worth pinning, since the original was an
 * `if / else if / else if / else` chain whose ordering carried real meaning
 * (search wins over vault selection, which wins over format filtering).
 */
internal enum class LibraryContent {
    /** No items at all and no scan running — the onboarding-ish empty state. */
    EmptyLibrary,

    /** Search mode: `searchResults != null`, regardless of any other filter. */
    SearchResults,

    /** A single vault is selected; its items are already format-filtered upstream. */
    SingleVault,

    /** No format filter: everything, split into Books and Audiobooks sections. */
    AllGrouped,

    /** A format filter is active but matched nothing in any vault. */
    FilteredEmpty,

    /** A format filter is active and matched something: per-vault sections. */
    VaultSections,
}

/**
 * The full "what is visible" decision for one render of [LibraryScreen].
 *
 * The three flags are deliberately part of the same value rather than computed
 * separately at their call sites: several of them are mutually constrained
 * (nothing but the empty state shows when the library is empty; the chips hide
 * during search), and keeping the constraints in one place is what makes them
 * assertable.
 */
internal data class LibraryLayout(
    val content: LibraryContent,
    val showContinue: Boolean,
    val showVaultChips: Boolean,
    val showFormatChips: Boolean,
)

/**
 * Decides the layout for [state].
 *
 * Mirrors the original inline conditions exactly, including their order:
 *  1. empty library (no items, not scanning) replaces the whole grid;
 *  2. otherwise search results win over everything;
 *  3. then a selected vault;
 *  4. then "no format filter" — the default Books/Audiobooks split;
 *  5. then a format filter that matched nothing;
 *  6. otherwise per-vault sections.
 *
 * The `isScanning` clause in (1) is why an empty library mid-scan keeps showing
 * the grid rather than flashing the empty state — a scan in progress is not the
 * same as "you have no books".
 */
internal fun libraryLayoutFor(state: LibraryUiState): LibraryLayout {
    if (state.allItems.isEmpty() && !state.isScanning) {
        // The empty state replaces the grid entirely, so no chips and no
        // Continue row — modelled explicitly rather than left implicit.
        return LibraryLayout(
            content = LibraryContent.EmptyLibrary,
            showContinue = false,
            showVaultChips = false,
            showFormatChips = false,
        )
    }

    val inSearch = state.searchResults != null
    val content = when {
        inSearch -> LibraryContent.SearchResults
        state.selectedVault != null -> LibraryContent.SingleVault
        state.formatFilter == null -> LibraryContent.AllGrouped
        state.vaultGroupedItems.values.all { it.isEmpty() } -> LibraryContent.FilteredEmpty
        else -> LibraryContent.VaultSections
    }

    return LibraryLayout(
        content = content,
        showContinue = state.continueItems.isNotEmpty(),
        // Only worth offering when there is a choice to make and the user is
        // not already inside one vault or inside search.
        showVaultChips = state.vaults.size > 1 && state.selectedVault == null && !inSearch,
        // Search has its own copy of the format chips in the overlay, so the
        // grid must not render a second set behind it.
        showFormatChips = !inSearch,
    )
}

/**
 * Splits the library into (books, audiobooks) for the default ungrouped view.
 *
 * A single pass rather than two `filter` calls, and — more to the point — one
 * definition of "is this an audiobook" instead of the predicate and its
 * negation being written out separately at two call sites, which is how an item
 * ends up in both sections or neither.
 */
internal fun partitionByMedium(items: List<LibraryItem>): Pair<List<LibraryItem>, List<LibraryItem>> =
    items.partition { !it.format.isAudio() }

/**
 * Derives a vault's display name from a SAF tree URI's last path segment.
 *
 * Was inline inside the folder-picker's `ActivityResult` callback, where it
 * could not be reached by a test at all despite deciding the name the user sees
 * for every vault they add.
 *
 * SAF hands back segments like `primary:Books/Fiction`; the document authority
 * prefix before `:` and any parent path are both noise. Falls back to
 * "My Vault" when there is nothing usable, rather than showing an empty title.
 */
internal fun vaultDisplayNameFrom(lastPathSegment: String?): String =
    lastPathSegment
        ?.substringAfterLast(':')
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }
        ?: DEFAULT_VAULT_NAME

internal const val DEFAULT_VAULT_NAME = "My Vault"
