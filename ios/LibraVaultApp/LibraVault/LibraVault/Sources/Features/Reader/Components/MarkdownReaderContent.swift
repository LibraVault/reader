import SwiftUI
import UIKit

/// Reports the Markdown content's scroll offset (relative to its own top) up through
/// a SwiftUI preference, using the standard GeometryReader-in-background idiom —
/// deliberately not the newer `onScrollGeometryChange` modifier, whose minimum
/// platform version couldn't be confirmed against this project's iOS 17 deployment
/// target without a local Xcode toolchain to check.
private struct MarkdownScrollOffsetKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = nextValue()
    }
}

/// Renders [MarkdownBlock] (see MarkdownDocumentParser) in its own scroll view,
/// following the same theme/font/line-spacing inputs EPUB/PDF's plain-text rendering
/// already uses in ReaderView — a thin style adapter over the block model, not
/// hardcoded styling baked into the parser.
///
/// Progress is tracked at block granularity (which block is nearest the top),
/// matching EPUB's per-chapter and PDF's per-page granularity elsewhere in this
/// reader — not a pixel-exact scroll offset.
struct MarkdownReaderContent: View {
    let blocks: [MarkdownBlock]
    /// Resolved image bytes for `.image` blocks, keyed by their raw (unresolved)
    /// reference string — see ReaderView.markdownImages for why this is loaded
    /// eagerly rather than resolved here.
    let images: [String: Data]
    let colors: LibraVaultColorScheme
    let fontSize: Double
    let lineSpacing: Double
    let fontDesign: Font.Design
    /// 0...1 fraction of the way through `blocks`, restored from saved progress.
    let initialScrollFraction: Double
    let onScrollFractionChanged: (Double) -> Void
    /// One-shot scroll target set when the user taps a TOC entry — a top-level index
    /// into `blocks` (see MarkdownTocEntry.blockIndex), not a scroll fraction.
    var scrollToBlockIndex: Int? = nil
    var onBlockScrollConsumed: () -> Void = {}

    @State private var contentHeight: CGFloat = 0
    @State private var viewportHeight: CGFloat = 0
    private static let coordinateSpaceName = "markdownScroll"

    var body: some View {
        ScrollViewReader { scrollProxy in
            ScrollView {
                VStack(alignment: .leading, spacing: LibraVaultSpacing.md) {
                    ForEach(Array(blocks.enumerated()), id: \.offset) { index, block in
                        MarkdownBlockView(
                            block: block,
                            images: images,
                            colors: colors,
                            fontSize: fontSize,
                            lineSpacing: lineSpacing,
                            fontDesign: fontDesign
                        )
                        .id(index)
                    }
                }
                .padding(LibraVaultSpacing.lg)
                .textSelection(.enabled)
                .background(
                    GeometryReader { contentGeometry in
                        Color.clear
                            .preference(
                                key: MarkdownScrollOffsetKey.self,
                                value: contentGeometry.frame(in: .named(Self.coordinateSpaceName)).minY
                            )
                            .onAppear { contentHeight = contentGeometry.size.height }
                            .onChange(of: contentGeometry.size.height) { _, newHeight in contentHeight = newHeight }
                    }
                )
            }
            .coordinateSpace(name: Self.coordinateSpaceName)
            .background(
                GeometryReader { viewportGeometry in
                    Color.clear
                        .onAppear { viewportHeight = viewportGeometry.size.height }
                        .onChange(of: viewportGeometry.size.height) { _, newHeight in viewportHeight = newHeight }
                }
            )
            .onPreferenceChange(MarkdownScrollOffsetKey.self) { minY in
                let scrollableHeight = contentHeight - viewportHeight
                guard scrollableHeight > 0 else { return }
                let fraction = (-minY / scrollableHeight)
                onScrollFractionChanged(min(max(fraction, 0), 1))
            }
            .task {
                guard !blocks.isEmpty, initialScrollFraction > 0 else { return }
                let targetIndex = min(
                    max(Int((initialScrollFraction * Double(blocks.count)).rounded()), 0),
                    blocks.count - 1
                )
                scrollProxy.scrollTo(targetIndex, anchor: .top)
            }
            // TOC navigation: by the time a user can tap a TOC entry this view is
            // already on screen (the TOC button lives in ReaderView's own toolbar),
            // so no "wait for layout" dance is needed here — same one-shot shape as
            // scrollToOffset/onScrollConsumed on the Android side.
            .onChange(of: scrollToBlockIndex) { _, newIndex in
                guard let newIndex else { return }
                withAnimation {
                    scrollProxy.scrollTo(newIndex, anchor: .top)
                }
                onBlockScrollConsumed()
            }
        }
    }
}

private struct MarkdownBlockView: View {
    let block: MarkdownBlock
    let images: [String: Data]
    let colors: LibraVaultColorScheme
    let fontSize: Double
    let lineSpacing: Double
    let fontDesign: Font.Design

    var body: some View {
        switch block {
        case let .heading(level, text):
            runsText(text, baseSize: headingSize(for: level))
                .fontWeight(.bold)
                .lineSpacing(8 * lineSpacing)
                .foregroundStyle(colors.onBackground)

        case let .paragraph(text):
            runsText(text, baseSize: 16 * fontSize)
                .lineSpacing(8 * lineSpacing)
                .foregroundStyle(colors.onBackground)

        case let .codeBlock(code, _):
            Text(code)
                .font(.system(size: 14 * fontSize, design: .monospaced))
                .foregroundStyle(colors.onBackground)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(LibraVaultSpacing.sm)
                .background(colors.surface)
                .clipShape(RoundedRectangle(cornerRadius: 8))

        case let .blockQuote(blocks):
            HStack(spacing: LibraVaultSpacing.sm) {
                Rectangle()
                    .fill(colors.primary)
                    .frame(width: 3)
                VStack(alignment: .leading, spacing: LibraVaultSpacing.sm) {
                    ForEach(Array(blocks.enumerated()), id: \.offset) { _, nested in
                        MarkdownBlockView(
                            block: nested, images: images, colors: colors, fontSize: fontSize,
                            lineSpacing: lineSpacing, fontDesign: fontDesign
                        )
                    }
                }
            }

        case let .unorderedList(items):
            VStack(alignment: .leading, spacing: LibraVaultSpacing.sm) {
                ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                    listRow(marker: "•", blocks: item)
                }
            }

        case let .orderedList(items, start):
            VStack(alignment: .leading, spacing: LibraVaultSpacing.sm) {
                ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                    listRow(marker: "\(start + index).", blocks: item)
                }
            }

        case .thematicBreak:
            Divider()

        case let .image(url, altText):
            if let data = images[url], let uiImage = UIImage(data: data) {
                Image(uiImage: uiImage)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(maxWidth: .infinity)
            } else {
                VStack(spacing: LibraVaultSpacing.xs) {
                    Image(systemName: "photo")
                        .font(.system(size: 32))
                        .foregroundStyle(colors.onSurfaceVariant)
                    if !altText.isEmpty {
                        Text(altText)
                            .font(.system(size: 14 * fontSize, design: fontDesign))
                            .foregroundStyle(colors.onSurfaceVariant)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(LibraVaultSpacing.lg)
                .background(colors.surface)
                .clipShape(RoundedRectangle(cornerRadius: 8))
            }
        }
    }

    private func listRow(marker: String, blocks: [MarkdownBlock]) -> some View {
        HStack(alignment: .top, spacing: LibraVaultSpacing.sm) {
            Text(marker)
                .font(.system(size: 16 * fontSize, design: fontDesign))
                .foregroundStyle(colors.onSurfaceVariant)
            VStack(alignment: .leading, spacing: LibraVaultSpacing.sm) {
                ForEach(Array(blocks.enumerated()), id: \.offset) { _, block in
                    MarkdownBlockView(
                        block: block, images: images, colors: colors, fontSize: fontSize,
                        lineSpacing: lineSpacing, fontDesign: fontDesign
                    )
                }
            }
        }
    }

    private func headingSize(for level: Int) -> Double {
        // H1 largest, clamped floor at H6 so deeply nested headings stay legible.
        let scale = max(6 - level, 1)
        return (16 + Double(scale) * 3) * fontSize
    }

    private func runsText(_ runs: [MarkdownInlineRun], baseSize: Double) -> Text {
        runs.reduce(Text("")) { partial, run in
            var piece = Text(run.text)
                .font(.system(size: baseSize, design: run.code ? .monospaced : fontDesign))
            if run.bold { piece = piece.bold() }
            if run.italic { piece = piece.italic() }
            return partial + piece
        }
    }
}
