import SwiftUI

struct ReaderView: View {
    let book: BookItem
    @State private var currentPage = 1
    @State private var totalPages = 100
    @State private var showingControls = true
    @State private var selectedText: String = ""
    @State private var showHighlightOptions = false
    @State private var isSpeaking = false
    @State private var fontSize: Double = 1.0

    var body: some View {
        ZStack {
            // Content Area
            VStack {
                VStack(spacing: 16) {
                    Text(book.title)
                        .font(.headline)
                        .padding()

                    ScrollView {
                        Text(sampleChapterContent(page: currentPage))
                            .font(.system(size: 16 * fontSize))
                            .lineSpacing(8)
                            .padding()
                            .textSelection(.enabled)
                            .onContinuousHover { phase in
                                if case .active = phase {
                                    // Selection tracking for future highlight feature
                                }
                            }
                    }
                    .frame(maxHeight: .infinity)
                    .background(Color(.systemBackground))

                    // Page navigation
                    HStack(spacing: 16) {
                        Button(action: { if currentPage > 1 { currentPage -= 1; updateProgress() } }) {
                            Image(systemName: "chevron.left")
                        }
                        .disabled(currentPage <= 1)

                        Text("Page \(currentPage) of \(totalPages)")
                            .font(.caption)

                        ProgressView(value: Double(currentPage) / Double(totalPages))
                            .frame(maxWidth: .infinity)

                        Button(action: { if currentPage < totalPages { currentPage += 1; updateProgress() } }) {
                            Image(systemName: "chevron.right")
                        }
                        .disabled(currentPage >= totalPages)
                    }
                    .padding()
                    .background(Color(.systemGray6))
                }
            }

            // Overlay Controls
            if showingControls {
                VStack {
                    HStack {
                        Text("Reading: \(book.title)")
                            .font(.caption)
                            .fontWeight(.semibold)
                            .foregroundColor(.white)

                        Spacer()

                        Menu {
                            Button("Add Bookmark", action: { addBookmark() })
                            Button("Adjust Font Size", action: {})
                            if isSpeaking {
                                Button("Stop Speaking", action: { stopSpeaking() })
                            } else {
                                Button("Read Aloud", action: { startSpeaking() })
                            }
                        } label: {
                            Image(systemName: "ellipsis.circle")
                                .foregroundColor(.white)
                        }
                    }
                    .padding()
                    .background(Color.black.opacity(0.5))

                    Spacer()

                    HStack(spacing: 16) {
                        Button(action: { addBookmark() }) {
                            VStack(spacing: 4) {
                                Image(systemName: "bookmark.fill")
                                    .frame(width: 44, height: 44)
                                Text("Bookmark")
                                    .font(.caption)
                            }
                        }

                        Button(action: { showHighlightOptions.toggle() }) {
                            VStack(spacing: 4) {
                                Image(systemName: "highlighter")
                                    .frame(width: 44, height: 44)
                                Text("Highlight")
                                    .font(.caption)
                            }
                        }

                        Button(action: { isSpeaking ? stopSpeaking() : startSpeaking() }) {
                            VStack(spacing: 4) {
                                Image(systemName: isSpeaking ? "speaker.fill" : "speaker.wave.2")
                                    .frame(width: 44, height: 44)
                                Text(isSpeaking ? "Stop" : "Speak")
                                    .font(.caption)
                            }
                        }

                        Spacer()

                        Button(action: { withAnimation { showingControls = false } }) {
                            VStack(spacing: 4) {
                                Image(systemName: "chevron.down")
                                    .frame(width: 44, height: 44)
                                Text("Hide")
                                    .font(.caption)
                            }
                        }
                    }
                    .padding()
                    .background(Color.black.opacity(0.5))
                }
                .foregroundColor(.white)
                .transition(.opacity)
            }
        }
        .background(Color(.systemBackground))
        .onTapGesture {
            withAnimation(.easeInOut(duration: 0.2)) {
                showingControls.toggle()
            }
        }
        .navigationTitle("Reading", displayMode: .inline)
    }

    private func updateProgress() {
        Task {
            let progress = Double(currentPage) / Double(totalPages)
            try? await LibravaultDomainBridge.shared.updateProgress(bookId: book.id, progress: progress)
        }
    }

    private func addBookmark() {
        Task {
            try? await LibravaultDomainBridge.shared.addBookmark(bookId: book.id, position: "page:\(currentPage)")
        }
    }

    private func startSpeaking() {
        isSpeaking = true
        Task {
            try? await LibravaultDomainBridge.shared.startSpeaking(text: sampleChapterContent(page: currentPage))
        }
    }

    private func stopSpeaking() {
        isSpeaking = false
        Task {
            await LibravaultDomainBridge.shared.stopSpeaking()
        }
    }

    private func sampleChapterContent(page: Int) -> String {
        let chapters = [
            "Chapter 1: The Beginning\n\nIt was a bright cold day in April, and the clocks were striking thirteen. The city stretched before them, vast and incomprehensible, full of secrets and mysteries waiting to be discovered.",
            "Chapter 2: Into the Depths\n\nThey ventured deeper into the ancient library, their footsteps echoing against stone walls. The air grew colder as they descended, and the books seemed to watch their progress with silent judgment.",
            "Chapter 3: The Discovery\n\nAmong the forgotten volumes, they found it—a manuscript bound in leather, its pages yellowed with age. The words seemed to shimmer, as if alive with their own peculiar power.",
            "Chapter 4: Revelations\n\nAs they read, the truth began to unfold. Every sentence was a thread, weaving together into a tapestry of understanding. What they had thought was lost was merely hidden, waiting for someone brave enough to seek it.",
            "Chapter 5: The Choice\n\nNow came the moment of decision. Would they close the book and return to their ordinary lives, or would they follow the path laid out before them, into territories unknown?",
        ]
        return chapters[(page - 1) % chapters.count]
    }
}

#Preview {
    ReaderView(book: BookItem(id: "1", title: "The Great Gatsby", author: "F. Scott Fitzgerald", coverUrl: nil, progress: 0.35))
}
