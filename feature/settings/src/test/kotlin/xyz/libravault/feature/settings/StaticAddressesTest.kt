package xyz.libravault.feature.settings

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.libravault.core.domain.usecase.AddVaultFolderUseCase
import xyz.libravault.core.domain.usecase.ObserveVaultsUseCase
import xyz.libravault.core.domain.usecase.RemoveVaultFolderUseCase
import xyz.libravault.core.domain.usecase.ScanVaultUseCase
import xyz.libravault.core.storage.CoverArtCache
import xyz.libravault.core.storage.SupporterRepository
import xyz.libravault.core.storage.VaultManager
import xyz.libravault.core.domain.repository.LibraryRepository
import xyz.libravault.core.logger.LibravaultLogger

/**
 * Focused tests for the [StaticDonationAddresses] integration in
 * [SettingsViewModel.createDonationInvoice] — the path that routes to a
 * fallback BTC/XMR address when BTCPay returns no payment method.
 *
 * These tests use Turbine to assert on the actual [donationState] StateFlow,
 * verifying that the correct [DonationState] transitions occur when BTCPay
 * has no payment method (static address fallback) or the addresses are empty
 * (error fallback).
 */
class StaticAddressesTest {

    @BeforeEach
    fun setUp() {
        // viewModelScope uses Dispatchers.Main.immediate by default; replace
        // with a test dispatcher so the VM's launches don't call
        // Looper.getMainLooper() (which throws "not mocked" on the JVM).
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `NoMethod state populated from static addresses when BTCPay has no method`() = runTest {
        val donationClient = mockk<DonationClient>()
        val staticAddresses = mockk<StaticDonationAddresses>()
        val btcAddress = "bc1qtest_static_btc_address"
        val checkoutLink = "https://btcpay.example/checkout"

        // coAnswers { yield() } forces a real suspension point before returning the
        // stubbed value. Without it, the mocked calls resolve synchronously and
        // MutableStateFlow's conflation collapses `Creating` straight into the next
        // state before Turbine's collector ever observes it as a distinct item —
        // real network calls always suspend for real, so this is a test-only gap.
        coEvery { donationClient.createInvoice(any()) } coAnswers { yield(); NewInvoice("inv-1", checkoutLink) }
        coEvery { donationClient.getPaymentInfo("inv-1", "BTC") } coAnswers { yield(); null }
        coEvery { staticAddresses.btc } returns btcAddress
        coEvery { staticAddresses.xmr } returns "48test_static_xmr_address"

        val vm = buildVm(donationClient, staticAddresses)

        vm.donationState.test {
            val initial = awaitItem()
            assertEquals(DonationState.Idle, initial)

            vm.createDonationInvoice(amountUsd = 5, coin = "BTC")

            val creating = awaitItem()
            assertEquals(DonationState.Creating, creating)

            val noMethod = awaitItem()
            assertTrue(
                noMethod is DonationState.NoMethod,
                "expected NoMethod state, got $noMethod"
            )
            val noMethodState = noMethod as DonationState.NoMethod
            assertEquals("BTC", noMethodState.coin)
            assertEquals(btcAddress, noMethodState.fallbackAddress)
            assertEquals(checkoutLink, noMethodState.checkoutLink)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Error state when static addresses are empty and BTCPay has no method`() = runTest {
        val donationClient = mockk<DonationClient>()
        val staticAddresses = mockk<StaticDonationAddresses>()

        coEvery { donationClient.createInvoice(any()) } coAnswers { yield(); NewInvoice("inv-2", "https://btcpay.example/checkout") }
        coEvery { donationClient.getPaymentInfo("inv-2", "XMR") } coAnswers { yield(); null }
        coEvery { staticAddresses.xmr } returns "" // Empty — Play-flavor fallback

        val vm = buildVm(donationClient, staticAddresses)

        vm.donationState.test {
            val initial = awaitItem()
            assertEquals(DonationState.Idle, initial)

            vm.createDonationInvoice(amountUsd = 5, coin = "XMR")

            val creating = awaitItem()
            assertEquals(DonationState.Creating, creating)

            val error = awaitItem()
            assertTrue(
                error is DonationState.Error,
                "expected Error state when static address is empty, got $error"
            )
            val errorState = error as DonationState.Error
            assertTrue(errorState.message.isNotEmpty())

            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun buildVm(
        donationClient: DonationClient,
        staticAddresses: StaticDonationAddresses,
    ): SettingsViewModel {
        // Explicitly "no pending invoice" — an unstubbed relaxed mock still
        // returns a non-null default here, which would make init{} launch
        // pollUntilPaid()'s `while (true)` loop against a donationClient
        // that only stubs createInvoice/getPaymentInfo. This test currently
        // survives that by accident (Main isn't tied to runTest's own
        // scheduler here, so the orphaned loop just freezes at its first
        // delay() instead of spinning) — don't rely on that; see the same
        // issue tracked down properly in SettingsViewModelTest.
        val supporterRepository = mockk<SupporterRepository>(relaxed = true)
        every { supporterRepository.getPendingInvoiceId() } returns null

        return SettingsViewModel(
            prefsRepo          = mockk(relaxed = true),
            coverArtCache      = mockk<CoverArtCache>(relaxed = true),
            libraryRepository  = mockk<LibraryRepository>(relaxed = true),
            vaultManager       = mockk<VaultManager>(relaxed = true),
            addVaultFolder     = mockk<AddVaultFolderUseCase>(relaxed = true),
            removeVaultFolder  = mockk<RemoveVaultFolderUseCase>(relaxed = true),
            observeVaults      = mockk<ObserveVaultsUseCase>(relaxed = true),
            scanVaultsUseCase  = mockk<ScanVaultUseCase>(relaxed = true),
            logger             = mockk<LibravaultLogger>(relaxed = true),
            supporterRepository = supporterRepository,
            donationClient     = donationClient,
            staticAddresses    = staticAddresses,
        )
    }
}