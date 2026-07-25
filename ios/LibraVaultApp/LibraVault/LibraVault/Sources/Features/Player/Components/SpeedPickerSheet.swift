import SwiftUI

/// Mirrors Android's SpeedPickerSheet (feature/player/components). Speed genuinely
/// affects playback: AppState.estimateDuration scales with it, and the wall-clock
/// timer advances elapsedSeconds by playbackSpeed per tick.
struct SpeedPickerSheet: View {
    @Binding var speed: Double

    private let presets: [Double] = [0.75, 1.0, 1.25, 1.5, 1.75, 2.0]

    var body: some View {
        VStack(alignment: .leading, spacing: LibraVaultSpacing.lg) {
            Text("Playback speed")
                .font(LibraVaultTypography.headlineSmall)
                .foregroundStyle(LibraVaultColor.onSurface)

            VStack(spacing: LibraVaultSpacing.sm) {
                ForEach(presets, id: \.self) { preset in
                    Button(action: { speed = preset }) {
                        HStack {
                            Text("\(String(format: "%.2f", preset))×")
                                .font(LibraVaultTypography.bodyLarge)
                                .foregroundStyle(preset == speed ? LibraVaultColor.onPrimary : LibraVaultColor.onSurface)
                            Spacer()
                            if preset == speed {
                                Image(systemName: "checkmark")
                                    .foregroundStyle(LibraVaultColor.onPrimary)
                            }
                        }
                        .padding(LibraVaultSpacing.md)
                        .background(preset == speed ? LibraVaultColor.primary : LibraVaultColor.background)
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
    SpeedPickerSheet(speed: .constant(1.0))
}
