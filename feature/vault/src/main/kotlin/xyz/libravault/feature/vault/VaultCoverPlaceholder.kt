package xyz.libravault.feature.vault

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.libravault.core.ui.components.GeneratedCover

/**
 * Cover-art placeholder for a vault entry with no imported/embedded cover
 * (issue #169). Built on top of [GeneratedCover] — same deterministic
 * title-hashed gradient+initials and "No cover art" caption band everyone
 * else sees (#168) — plus a small padlock badge in the corner, so a missing
 * cover here reads as "this vault item has no cover" rather than looking
 * like a rendering bug or, worse, like the app failed to decrypt something.
 */
@Composable
fun VaultCoverPlaceholder(title: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        GeneratedCover(title = title, modifier = Modifier.fillMaxSize())
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(3.dp)
                .size(16.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = "Encrypted vault item",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(10.dp),
                )
            }
        }
    }
}
