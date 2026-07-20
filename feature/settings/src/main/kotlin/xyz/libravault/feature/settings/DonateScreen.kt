package xyz.libravault.feature.settings

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Validates and launches a checkout URL from BTCPay.
 *
 * Defense against compromised BTCPay or MITM returning a malicious scheme
 * (review finding #23 / WS5.2): we explicitly require `https://` and
 * extract the host so the UI can show "Opens checkout.btcpayserver.com".
 * Anything else (`javascript:`, `intent:`, raw `file:`, malformed URL) is
 * rejected and the caller is expected to surface that to the user.
 *
 * Returns `null` if the URL is unsafe or unparseable — caller should
 * disable the "Open checkout" button rather than silently dropping the
 * click.
 *
 * Uses [java.net.URI] (not [Uri]) for validation because URI is a pure
 * JVM class that works in unit tests without an Android runtime. URI is
 * also stricter about scheme parsing — `javascript:alert(1)` is not a
 * valid URI because `alert(1)` is not a valid hier-part.
 */
internal fun safeCheckoutLink(raw: String): java.net.URI? {
    if (raw.isBlank()) return null
    val parsed = runCatching { java.net.URI(raw) }.getOrNull() ?: return null
    if (parsed.scheme == null || !parsed.scheme.equals("https", ignoreCase = true)) return null
    if (parsed.host.isNullOrBlank()) return null
    return parsed
}

/**
 * Wraps a validated [java.net.URI] as an Android [Uri] for [Intent.ACTION_VIEW].
 * Kept separate so [safeCheckoutLink] can be tested without an Android runtime.
 */
internal fun uriForIntent(uri: java.net.URI): Uri = Uri.parse(uri.toString())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DonateSheet(
    isSupporter: Boolean,
    donationState: DonationState,
    onDismiss: () -> Unit,
    onCreateInvoice: (amountUsd: Int, coin: String) -> Unit,
    onCancel: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = {
            onCancel()
            onDismiss()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        DonateSheetContent(
            isSupporter = isSupporter,
            donationState = donationState,
            onCreateInvoice = onCreateInvoice,
            onCancel = onCancel,
        )
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun DonateSheetContent(
    isSupporter: Boolean,
    donationState: DonationState,
    onCreateInvoice: (amountUsd: Int, coin: String) -> Unit,
    onCancel: () -> Unit,
) {
    var selectedCoin by remember { mutableStateOf("BTC") }
    var selectedAmount by remember { mutableIntStateOf(5) }
    val context = LocalContext.current

    LaunchedEffect(donationState) {
        if (donationState is DonationState.Paid) {
            Toast.makeText(context, "★ Thank you! Supporter badge activated.", Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (donationState) {
            is DonationState.Idle -> {
                if (isSupporter) {
                    Text(
                        text = "★ Thank you, Supporter!",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFB300),
                    )
                    Text(
                        text = "Want to donate again? We appreciate it!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "Support LibraVault",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Free, open-source, and ad-free. Any amount helps keep it alive.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("BTC", "XMR").forEach { coin ->
                        FilterChip(
                            selected = selectedCoin == coin,
                            onClick = { selectedCoin = coin },
                            label = { Text(coin) },
                        )
                    }
                }

                Text(
                    text = "Amount (USD)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 3, 5, 10).forEach { amount ->
                        FilterChip(
                            selected = selectedAmount == amount,
                            onClick = { selectedAmount = amount },
                            label = { Text("$$amount") },
                        )
                    }
                }

                Button(
                    onClick = { onCreateInvoice(selectedAmount, selectedCoin) },
                    // WS5.3 / review finding #20 — disable while Creating to
                    // prevent a fast double-tap from creating two BTCPay
                    // invoices (BTCPay does not dedupe; no idempotency key).
                    enabled = donationState !is DonationState.Creating,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Get Payment Address")
                }
            }

            is DonationState.Creating -> {
                Text(
                    text = "Support LibraVault",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                CircularProgressIndicator()
                Text(
                    text = "Creating invoice…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is DonationState.Pending -> {
                Text(
                    text = "Send Payment",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )

                val qrContent = donationState.paymentLink.ifEmpty { donationState.address }
                val qrBitmap = rememberQrBitmap(qrContent)
                if (qrBitmap != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White,
                        modifier = Modifier.size(220.dp),
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Payment QR code",
                            modifier = Modifier.padding(12.dp).fillMaxSize(),
                        )
                    }
                }

                if (donationState.cryptoAmount.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = donationState.cryptoAmount,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        TextButton(onClick = {
                            copyToClipboard(context, donationState.cryptoAmount)
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                Toast.makeText(context, "Amount copied", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Text("Copy")
                        }
                    }
                }

                AddressRow(
                    address = donationState.address,
                    onCopy = {
                        copyToClipboard(context, donationState.address)
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            Toast.makeText(context, "Address copied", Toast.LENGTH_SHORT).show()
                        }
                    },
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = "Waiting for payment…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                safeCheckoutLink(donationState.checkoutLink)?.let { link ->
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, uriForIntent(link)))
                        }
                    }) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Open in browser")
                            Text(
                                text = "Opens ${link.host}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                TextButton(onClick = onCancel) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            is DonationState.Paid -> {
                Text(
                    text = "★ Thank you!",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFFB300),
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Your Supporter badge is now active.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            is DonationState.NoMethod -> {
                Text(
                    text = "Payment method not ready",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (donationState.coin == "BTC")
                        "The BTC node is still syncing. Try again shortly, or send manually to the address below."
                    else
                        "The XMR wallet isn't configured yet. Try again shortly, or send manually to the address below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                val qrBitmap = rememberQrBitmap(donationState.fallbackAddress)
                if (qrBitmap != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White,
                        modifier = Modifier.size(200.dp),
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Fallback address QR",
                            modifier = Modifier.padding(12.dp).fillMaxSize(),
                        )
                    }
                }

                AddressRow(
                    address = donationState.fallbackAddress,
                    onCopy = {
                        copyToClipboard(context, donationState.fallbackAddress)
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            Toast.makeText(context, "Address copied", Toast.LENGTH_SHORT).show()
                        }
                    },
                )

                Text(
                    text = "Payment not automatically tracked when sent to this address.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    safeCheckoutLink(donationState.checkoutLink)?.let { link ->
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, uriForIntent(link)))
                            }
                        }) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Open checkout")
                                Text(
                                    text = "Opens ${link.host}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    TextButton(onClick = onCancel) {
                        Text("Back", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            is DonationState.Error -> {
                Text(
                    text = "Something went wrong",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = donationState.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("Try again")
                }
            }
        }
    }
}

@Composable
private fun AddressRow(address: String, onCopy: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = address,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCopy) { Text("Copy") }
        }
    }
}

@Composable
private fun rememberQrBitmap(content: String): Bitmap? = remember(content) {
    runCatching {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        )
        val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, 512, 512, hints)
        Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565).apply {
            for (x in 0 until 512) {
                for (y in 0 until 512) {
                    setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
        }
    }.getOrNull()
}
