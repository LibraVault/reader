import SwiftUI

struct ReaderView: View {
    let book: BookItem
    @State private var currentPage = 1
    @State private var totalPages = 100
    @State private var showingControls = true

    var body: some View {
        ZStack {
            // Content Area
            VStack {
                // Reader Content
                VStack(spacing: 16) {
                    Text(book.title)
                        .font(.headline)
                        .padding()

                    ScrollView {
                        Text("Chapter \(currentPage)")
                            .font(.body)
                            .padding()

                        // TODO: Integrate with core:tts for text-to-speech
                        // TODO: Load actual book content from core:storage
                    }
                    .frame(maxHeight: .infinity)

                    // Page navigation
                    HStack(spacing: 16) {
                        Button(action: { if currentPage > 1 { currentPage -= 1 } }) {
                            Image(systemName: "chevron.left")
                        }
                        .disabled(currentPage <= 1)

                        Text("Page \(currentPage) of \(totalPages)")
                            .font(.caption)

                        Button(action: { if currentPage < totalPages { currentPage += 1 } }) {
                            Image(systemName: "chevron.right")
                        }
                        .disabled(currentPage >= totalPages)
                    }
                    .padding()
                }
            }

            // Overlay Controls
            if showingControls {
                VStack {
                    HStack {
                        Button(action: { showingControls = false }) {
                            Image(systemName: "xmark.circle")
                                .foregroundColor(.white)
                        }

                        Spacer()

                        // TODO: Add highlight, bookmark, TTS controls
                        Menu {
                            Button("Add Bookmark", action: {})
                            Button("Settings", action: {})
                        } label: {
                            Image(systemName: "ellipsis.circle")
                                .foregroundColor(.white)
                        }
                    }
                    .padding()
                    .background(Color.black.opacity(0.4))

                    Spacer()

                    HStack(spacing: 16) {
                        Button(action: {}) {
                            Image(systemName: "bookmark")
                                .frame(width: 44, height: 44)
                        }

                        Button(action: {}) {
                            Image(systemName: "highlighter")
                                .frame(width: 44, height: 44)
                        }

                        Button(action: {}) {
                            Image(systemName: "speaker.wave.2")
                                .frame(width: 44, height: 44)
                        }

                        Spacer()

                        Button(action: { showingControls = false }) {
                            Image(systemName: "chevron.down")
                                .frame(width: 44, height: 44)
                        }
                    }
                    .padding()
                    .background(Color.black.opacity(0.4))
                }
                .foregroundColor(.white)
            }
        }
        .background(Color(.systemBackground))
        .onTapGesture {
            withAnimation {
                showingControls.toggle()
            }
        }
        .navigationTitle("Reading", displayMode: .inline)
    }
}

#Preview {
    ReaderView(book: BookItem(id: "1", title: "Sample Book", author: "Author", coverUrl: nil))
}
