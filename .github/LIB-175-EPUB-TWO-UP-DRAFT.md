---
name: LIB-175: EPUB Two-Up Reading Mode
about: Add side-by-side reading for tablets (Libby/Mantano precedent)
title: "LIB-175: EPUB Two-Up Reading Mode"
labels: enhancement, reader, tablet
---

Context

EPUB reader on tablets uses single-column layout — wastes screen real estate, no side-by-side reference mode.

Competitor Precedents

App        | Behavior
-----------|-----------------
Libby      | Two-up on iPad/tablet, auto-switches
Mantano    | Facing Pages toggle in Reader Settings
Google Play Books | Two-up automatically on tablets

Proposal

Phone (smallestWidth < 600dp) | Single column (no change)
Tablet (smallestWidth >= 600dp) | Single column by default, toggle in overflow: Two-Up

UX Flow

1. User opens EPUB on tablet
2. Reader UI shows Two-Up checkbox in overflow menu (only on tablet)
3. When enabled, layout inflates reader_epub_twoupcolumn.xml
4. Scroll sync: tap Sync Scroll -> align both columns

Technical Notes

- Reuse ReaderFragment + ReaderViewModel architecture
- New ReaderState.showTwoUp: Boolean in ViewModel
- Layout switch in reader_epub.xml using ViewStub or ConstraintLayout
- Only affects EPUB format; PDF/other stay single column
- No breaking changes to phone UX

Tasks

- Add showTwoUp: Boolean to ReaderState
- Toggle in overflow menu (tablet-only)
- Create reader_epub_twoupcolumn.xml layout
- Implement scroll listeners for both columns
- Apply two-up mode only to EPUB format
- Add integration test for scroll sync

Effort Estimate

~2-3 days MVP (layout + toggle + sync), +1 day Tablet-only UX polish.

Related

- Brainstorming idea #16 in .github/LIB-174-BRAINSTORMING.md
- Original issue LIB-174 (cold-start vault recovery + robustness)
