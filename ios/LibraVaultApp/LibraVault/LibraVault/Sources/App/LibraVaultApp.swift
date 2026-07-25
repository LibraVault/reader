import SwiftUI

@main
struct LibraVaultApp: App {
    @StateObject private var appState = AppState()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(appState)
        }
    }
}

struct ContentView: View {
    @EnvironmentObject var appState: AppState
    @State private var selectedTab: AppTab = .library

    var body: some View {
        TabView(selection: $selectedTab) {
            // Library Tab
            LibraryView()
                .tabItem {
                    Label("Library", systemImage: "books.vertical")
                }
                .tag(AppTab.library)

            // Reader Tab (when a book is selected)
            if let book = appState.selectedBook {
                ReaderView(book: book)
                    .tabItem {
                        Label("Reader", systemImage: "doc.text")
                    }
                    .tag(AppTab.reader)
            }

            // Settings Tab
            SettingsView()
                .tabItem {
                    Label("Settings", systemImage: "gear")
                }
                .tag(AppTab.settings)
        }
    }
}

enum AppTab {
    case library
    case reader
    case settings
}
