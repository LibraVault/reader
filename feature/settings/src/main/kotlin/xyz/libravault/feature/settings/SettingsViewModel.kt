package xyz.libravault.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.libravault.core.domain.model.AppReadingTheme
import xyz.libravault.core.domain.model.UserPreferences
import xyz.libravault.core.domain.model.VaultFolder
import xyz.libravault.core.domain.model.snapPlaybackSpeed
import xyz.libravault.core.domain.scanner.ScanProgress
import xyz.libravault.core.domain.usecase.AddVaultFolderUseCase
import xyz.libravault.core.domain.usecase.ObserveVaultsUseCase
import xyz.libravault.core.domain.usecase.RemoveVaultFolderUseCase
import xyz.libravault.core.domain.usecase.ScanVaultUseCase
import xyz.libravault.core.storage.CoverArtCache
import xyz.libravault.core.storage.SupporterRepository
import xyz.libravault.core.storage.VaultManager
import xyz.libravault.core.domain.repository.LibraryRepository
import xyz.libravault.core.logger.LibravaultLogger
import javax.inject.Inject

data class VaultManagementState(
    val vaults: List<VaultFolder> = emptyList(),
    val isScanning: Boolean = false,
    val scanMessage: String? = null,
)

sealed class DonationState {
    object Idle : DonationState()
    object Creating : DonationState()
    data class Pending(
        val invoiceId: String,
        val address: String,
        val paymentLink: String,
        val cryptoAmount: String,
        val checkoutLink: String,
    ) : DonationState()
    object Paid : DonationState()
    data class NoMethod(
        val coin: String,
        val fallbackAddress: String,
        val checkoutLink: String,
    ) : DonationState()
    data class Error(val message: String) : DonationState()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefsRepo: UserPreferencesRepository,
    private val coverArtCache: CoverArtCache,
    private val libraryRepository: LibraryRepository,
    private val vaultManager: VaultManager,
    private val addVaultFolder: AddVaultFolderUseCase,
    private val removeVaultFolder: RemoveVaultFolderUseCase,
    private val observeVaults: ObserveVaultsUseCase,
    private val scanVaultsUseCase: ScanVaultUseCase,
    private val logger: LibravaultLogger,
    private val supporterRepository: SupporterRepository,
    private val donationClient: DonationClient,
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> = prefsRepo.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), prefsRepo.read())

    val isSupporter: StateFlow<Boolean> = supporterRepository.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), supporterRepository.isSupporter())

    private val _vaultState = MutableStateFlow(VaultManagementState())
    val vaultState: StateFlow<VaultManagementState> = _vaultState.asStateFlow()

    private val _donationState = MutableStateFlow<DonationState>(DonationState.Idle)
    val donationState: StateFlow<DonationState> = _donationState.asStateFlow()

    private var donationJob: Job? = null

    init {
        viewModelScope.launch {
            observeVaults().collect { vaults ->
                _vaultState.value = _vaultState.value.copy(vaults = vaults)
            }
        }
        // Resume polling if the app was closed while waiting for a payment
        val pendingId = supporterRepository.getPendingInvoiceId()
        if (pendingId != null && !supporterRepository.isSupporter()) {
            donationJob = viewModelScope.launch { pollUntilPaid(pendingId) }
        }
        // Check BTCPay for any settled invoices in case badge was never flipped
        if (!supporterRepository.isSupporter()) {
            viewModelScope.launch {
                try {
                    if (donationClient.hasAnySettledInvoice()) {
                        supporterRepository.setSupporter(true)
                        logger.i("Donation", "Settled invoice found on startup — supporter activated")
                    }
                } catch (_: Exception) { }
            }
        }
    }

    fun onReadingThemeChanged(theme: AppReadingTheme) = update { it.copy(defaultReadingTheme = theme) }

    fun onPlaybackSpeedChanged(speed: Float) = update {
        it.copy(defaultPlaybackSpeed = snapPlaybackSpeed(speed))
    }

    fun onSkipDurationChanged(seconds: Int) = update {
        it.copy(defaultSkipDurationSec = seconds.coerceIn(5, 120))
    }

    fun onLoggingToggled(enabled: Boolean) {
        logger.isEnabled = enabled
        update { it.copy(loggingEnabled = enabled) }
    }

    fun onDynamicColorToggled(enabled: Boolean) = update {
        it.copy(dynamicColorEnabled = enabled)
    }

    /**
     * Wipes the on-disk cover cache AND nulls `coverArtPath` for every
     * library item. Both must move together: deleting the JPEG files
     * alone leaves the DB holding stale absolute paths, which makes the
     * scanner's enrichment gate (`coverArtPath == null`) refuse to
     * re-extract, so covers stay missing forever. See
     * `LibraryScannerImpl.enrichMetadata`.
     */
    fun clearCoverCache() {
        viewModelScope.launch {
            coverArtCache.clearAll()
            libraryRepository.clearCoverArtPaths()
            logger.i("Settings", "Cover art cache cleared")
        }
    }

    fun viewLogs() {
        viewModelScope.launch {
            val logs = logger.readLogs()
            _logsContent = logs
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            logger.clearLogs()
        }
    }

    // ── Vault Management ─────────────────────────────────────────────────────

    fun onVaultFolderPicked(uri: Uri, displayName: String) {
        viewModelScope.launch {
            vaultManager.persistPermission(uri)
            addVaultFolder(uri.toString(), displayName)
            logger.i("Settings", "Vault added from Settings: $displayName")
            scanVaults()
        }
    }

    fun removeVault(vault: VaultFolder) {
        viewModelScope.launch {
            val uri = Uri.parse(vault.uri)
            vaultManager.releasePermission(uri)
            removeVaultFolder(vault.id)
            logger.i("Settings", "Vault removed: ${vault.displayName}")
            scanVaults()
        }
    }

    fun scanVaults() {
        viewModelScope.launch {
            _vaultState.value = _vaultState.value.copy(isScanning = true, scanMessage = null)
            scanVaultsUseCase().collect { progress ->
                when (progress) {
                    is ScanProgress.Started -> {
                        _vaultState.value = _vaultState.value.copy(scanMessage = "Scanning vaults…")
                    }
                    is ScanProgress.ItemFound -> {
                        _vaultState.value = _vaultState.value.copy(
                            scanMessage = "Found ${progress.count} items…"
                        )
                    }
                    is ScanProgress.Completed -> {
                        val msg = if (progress.total > 0) {
                            val prefix = "Scan complete – ${progress.total} new items added"
                            val fc = progress.formatCounts
                            if (fc != null) {
                                "$prefix (${fc.epub} EPUB, ${fc.pdf} PDF, ${fc.audiobook} audiobooks)"
                            } else {
                                prefix
                            }
                        } else null
                        _vaultState.value = _vaultState.value.copy(
                            isScanning = false,
                            scanMessage = msg,
                        )
                        logger.i("Settings", "Scan complete: ${progress.total} new items")
                    }
                    is ScanProgress.Error -> {
                        _vaultState.value = _vaultState.value.copy(
                            isScanning = false,
                            scanMessage = "Error: ${progress.message}",
                        )
                    }
                }
            }
        }
    }

    // ── Donation ─────────────────────────────────────────────────────────────

    fun createDonationInvoice(amountUsd: Int, coin: String) {
        donationJob?.cancel()
        donationJob = viewModelScope.launch {
            _donationState.value = DonationState.Creating
            try {
                val invoice = donationClient.createInvoice(amountUsd)
                supporterRepository.setPendingInvoiceId(invoice.id)
                val paymentInfo = donationClient.getPaymentInfo(invoice.id, coin)
                if (paymentInfo == null) {
                    val fallback = if (coin == "XMR") XMR_ADDRESS else BTC_ADDRESS
                    _donationState.value = DonationState.NoMethod(coin, fallback, invoice.checkoutLink)
                    return@launch
                }
                _donationState.value = DonationState.Pending(
                    invoiceId = invoice.id,
                    address = paymentInfo.address,
                    paymentLink = paymentInfo.paymentLink,
                    cryptoAmount = paymentInfo.cryptoAmount,
                    checkoutLink = invoice.checkoutLink,
                )
                if (!invoice.isStatic) pollUntilPaid(invoice.id)
            } catch (e: Exception) {
                logger.e("Donation", "Invoice creation failed", e)
                _donationState.value = DonationState.Error(e.message ?: "Failed to create payment request")
            }
        }
    }

    fun cancelDonation() {
        donationJob?.cancel()
        supporterRepository.setPendingInvoiceId(null)
        _donationState.value = DonationState.Idle
    }

    private suspend fun pollUntilPaid(invoiceId: String) {
        while (true) {
            delay(15_000)
            val status = try {
                donationClient.getInvoiceStatus(invoiceId)
            } catch (e: Exception) {
                InvoiceStatus.Unknown
            }
            when (status) {
                InvoiceStatus.Processing, InvoiceStatus.Settled -> {
                    supporterRepository.setSupporter(true)
                    supporterRepository.setPendingInvoiceId(null)
                    logger.i("Donation", "Payment confirmed for invoice $invoiceId")
                    _donationState.value = DonationState.Paid
                    return
                }
                InvoiceStatus.Expired, InvoiceStatus.Invalid -> {
                    supporterRepository.setPendingInvoiceId(null)
                    _donationState.value = DonationState.Idle
                    return
                }
                else -> { /* keep polling */ }
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private var _logsContent: String = ""

    private fun update(transform: (UserPreferences) -> UserPreferences) {
        prefsRepo.update(transform(prefsRepo.read()))
    }
}
