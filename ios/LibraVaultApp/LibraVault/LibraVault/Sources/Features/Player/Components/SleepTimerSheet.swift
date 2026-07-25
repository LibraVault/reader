import SwiftUI

/// Mirrors Android's SleepTimerSheet (feature/player/components). Presets stop
/// playback for real via AppState.scheduleSleepTimer — a genuine countdown Timer,
/// not a cosmetic label.
struct SleepTimerSheet: View {
    let remainingSeconds: Double?
    let onSelect: (Double) -> Void
    let onCancel: () -> Void

    private let presets: [Double] = [5, 15, 30, 45, 60]

    var body: some View {
        VStack(alignment: .leading, spacing: LibraVaultSpacing.lg) {
            Text("Sleep timer")
                .font(LibraVaultTypography.headlineSmall)
                .foregroundStyle(LibraVaultColor.onSurface)

            if let remainingSeconds {
                HStack {
                    Text("Stopping in \(formatPlaybackTime(remainingSeconds))")
                        .font(LibraVaultTypography.bodyMedium)
                        .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                    Spacer()
                    Button("Cancel", action: onCancel)
                        .font(LibraVaultTypography.labelLarge)
                        .foregroundStyle(LibraVaultColor.primary)
                }
            }

            VStack(spacing: LibraVaultSpacing.sm) {
                ForEach(presets, id: \.self) { minutes in
                    Button(action: { onSelect(minutes) }) {
                        HStack {
                            Text("\(Int(minutes)) minutes")
                                .font(LibraVaultTypography.bodyLarge)
                                .foregroundStyle(LibraVaultColor.onSurface)
                            Spacer()
                        }
                        .padding(LibraVaultSpacing.md)
                        .background(LibraVaultColor.background)
                        .clipShape(RoundedRectangle(cornerRadius: LibraVaultRadius.card))
                    }
                }
            }

            Spacer()
        }
        .padding(LibraVaultSpacing.lg)
        .background(LibraVaultColor.surface)
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
    }
}

#Preview {
    SleepTimerSheet(remainingSeconds: nil, onSelect: { _ in }, onCancel: {})
}
