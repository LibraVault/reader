package xyz.libravault.feature.vault

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.libravault.core.ui.components.CoverFormatBadge
import xyz.libravault.core.ui.components.GeneratedCover
import xyz.libravault.core.ui.components.VaultLockBadge

/**
 * Cover-art placeholder for a vault entry with no imported/embedded cover
 * (issue #169). Built on top of [GeneratedCover] — same deterministic
 * title-hashed gradient+initials and "No cover art" caption band everyone
 * else sees (#168), now with a format-specific icon/label there too (#308) —
 * plus [VaultLockBadge] in the corner, so a missing cover here reads as
 * "this vault item has no cover" rather than looking like a rendering bug or,
 * worse, like the app failed to decrypt something.
 *
 * [format] is [VaultManifestEntry.format][xyz.libravault.core.vaultstore.VaultManifestEntry.format],
 * a raw `String` (the manifest stores `MediaFormat.name`, not the enum itself —
 * see that type's own doc comment) — passed straight through to
 * [CoverFormatBadge.fromFormatName], which already tolerates an unrecognized
 * value by falling back to the generic treatment.
 */
@Composable
fun VaultCoverPlaceholder(title: String, format: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        GeneratedCover(
            title = title,
            modifier = Modifier.fillMaxSize(),
            format = CoverFormatBadge.fromFormatName(format),
        )
        VaultLockBadge(modifier = Modifier.align(Alignment.TopEnd).padding(3.dp))
    }
}
