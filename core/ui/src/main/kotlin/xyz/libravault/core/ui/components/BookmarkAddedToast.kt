package xyz.libravault.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import xyz.libravault.core.ui.theme.Dimens

/**
 * Brief confirmation banner shown after a bookmark is added.
 *
 * Place this inside a parent that uses `Modifier.fillMaxSize()`; the toast
 * anchors itself ~22% from the top of that parent so it doesn't cover the
 * top toolbar or clash with the reader's first paragraph. Auto-dismisses
 * after [autoDismissMillis] (default 2.5 s). Tapping anywhere on the
 * banner invokes [onEdit], intended to open the bookmark editor.
 *
 * The toast disappears instantly when the parent sets `visible = false`,
 * and the auto-dismiss timer is reset each time visibility flips on.
 */
@Composable
fun BookmarkAddedToast(
    visible: Boolean,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    autoDismissMillis: Long = 2_500L,
) {
    LaunchedEffect(visible) {
        if (visible) {
            delay(autoDismissMillis)
            onDismiss()
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.spaceLg),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit  = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.inverseSurface)
                    .clickable(onClick = onEdit)
                    .padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceMd),
            ) {
                Icon(
                    imageVector = Icons.Default.Bookmark,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.inverseOnSurface,
                )
                Text(
                    text = "Bookmark added",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit bookmark",
                    tint = MaterialTheme.colorScheme.inversePrimary,
                )
            }
        }
    }
}