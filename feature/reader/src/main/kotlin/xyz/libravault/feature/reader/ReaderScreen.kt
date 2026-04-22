package xyz.libravault.feature.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

// M2: EPUB + PDF reader implementation
@Composable
fun ReaderScreen(itemId: Long, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Reader — M2", style = MaterialTheme.typography.headlineMedium)
    }
}
