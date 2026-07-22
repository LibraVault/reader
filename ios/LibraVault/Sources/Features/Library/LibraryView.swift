import SwiftUI

struct LibraryView: View {
    @EnvironmentObject var appState: AppState
    @State private var searchText = ""

    var filteredBooks: [BookItem] {
        if searchText.isEmpty {
            return appState.books
        }
        return appState.books.filter { book in
            book.title.localizedCaseInsensitiveContains(searchText) ||
            book.author.localizedCaseInsensitiveContains(searchText)
        }
    }

    var body: some View {
        NavigationStack {
            if appState.isLoading {
                ProgressView()
            } else if filteredBooks.isEmpty {
                VStack(spacing: 16) {
                    Image(systemName: "books.vertical")
                        .font(.system(size: 48))
                        .foregroundColor(.gray)
                    Text("No Books Found")
                        .font(.title2)
                    Text("Add books to your library to get started")
                        .foregroundColor(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 120))], spacing: 16) {
                        ForEach(filteredBooks) { book in
                            NavigationLink(destination: BookDetailView(book: book)) {
                                BookCoverView(book: book)
                                    .onTapGesture {
                                        appState.selectBook(book)
                                    }
                            }
                        }
                    }
                    .padding()
                }
            }

            .navigationTitle("Library")
            .searchable(text: $searchText, prompt: "Search books")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(action: { Task { await appState.loadLibrary() } }) {
                        Image(systemName: "arrow.clockwise")
                    }
                }
            }
            .onAppear {
                Task {
                    await appState.loadLibrary()
                }
            }
        }
    }
}

struct BookCoverView: View {
    let book: BookItem

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let coverUrl = book.coverUrl {
                // TODO: Load actual cover images from core:storage
                AsyncImage(url: URL(string: coverUrl)) { image in
                    image
                        .resizable()
                        .scaledToFill()
                } placeholder: {
                    Color.gray
                }
                .frame(height: 150)
                .clipped()
            } else {
                ZStack {
                    Color.blue.opacity(0.3)
                    Image(systemName: "book")
                        .font(.system(size: 40))
                }
                .frame(height: 150)
            }

            VStack(alignment: .leading, spacing: 4) {
                Text(book.title)
                    .font(.caption)
                    .fontWeight(.semibold)
                    .lineLimit(2)

                Text(book.author)
                    .font(.caption2)
                    .foregroundColor(.secondary)
                    .lineLimit(1)

                ProgressView(value: book.progress)
                    .scaleEffect(y: 0.75, anchor: .center)
            }
            .padding(8)
        }
        .background(Color(.systemBackground))
        .cornerRadius(8)
        .shadow(radius: 2)
    }
}

struct BookDetailView: View {
    let book: BookItem
    @Environment(\.dismiss) var dismiss

    var body: some View {
        VStack(spacing: 16) {
            HStack {
                Button(action: { dismiss() }) {
                    HStack(spacing: 4) {
                        Image(systemName: "chevron.left")
                        Text("Back")
                    }
                }

                Spacer()

                VStack(alignment: .trailing) {
                    Text(book.title)
                        .font(.headline)
                    Text(book.author)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
            .padding()

            Divider()

            VStack(alignment: .leading, spacing: 12) {
                Label("Progress: \(Int(book.progress * 100))%", systemImage: "percent")
                Label("Continue Reading", systemImage: "arrow.right")
            }
            .padding()

            Spacer()
        }
        .navigationBarBackButtonHidden()
    }
}

#Preview {
    LibraryView()
        .environmentObject(AppState())
}
