import Foundation
import StoreKit

/// Wraps Apple StoreKit 2 for LibraVault's two donation/subscription products, and is
/// the entire native purchase path on iOS — it replaces the external `Link` to
/// libravault.xyz/support.html that `SettingsView.supportSection` used to render.
/// Unlike Android (which still has F-Droid as an alternative distribution channel with
/// no billing API to hang an in-app purchase off), iOS has no such alternative: the
/// App Store is the only distribution channel, so the native path is used
/// unconditionally here.
///
/// This is donation/subscription only — nothing in LibraVault is feature-gated behind
/// either product, matching the app's standing "no Pro tier, donation-only" product
/// decision (see `project_license_activation_placeholder` — core:licensing was deleted
/// entirely for the same reason).
///
/// Product identifiers are the exact same two strings Android's concurrent, separate
/// billing effort uses — kept in sync by hand (same reasoning as
/// `SettingsView.supportURL`'s doc comment about Android's `SUPPORT_URL`), since Kotlin
/// and Swift can't share a constant here.
///
/// Real purchases can't be tested against App Store Connect yet — no product is
/// configured there (no banking/tax agreement signed at the time this was written).
/// `productsAvailable` settles to `false` without crashing in that case; see
/// `loadProducts()`. Locally testable now via `LibraVaultTests/StoreKitTestConfiguration.storekit`
/// and `StoreKitBillingManagerTests` (see that test file's header comment for how to
/// also select the same configuration for interactive Simulator runs in Xcode, not
/// just automated tests).
@MainActor
final class StoreKitBillingManager: ObservableObject {
    static let subscriptionProductID = "xyz.libravault.subscription.monthly"
    static let oneTimeTipProductID = "xyz.libravault.tip.onetime"

    /// True once both products have successfully loaded from the App Store (or the
    /// local `.storekit` test configuration in the Simulator). Settles to `false`,
    /// never crashes, if the products don't exist yet in App Store Connect — the
    /// expected state today (see the type's doc comment). `SettingsView.supportSection`
    /// gates its native purchase UI on this, falling back to a plain "coming soon" text
    /// rather than showing buttons that would only fail.
    @Published private(set) var productsAvailable = false

    /// Derived from `Transaction.currentEntitlements` for the subscription product —
    /// recomputed on init, after every purchase, and on every `Transaction.updates`
    /// event, so a renewal/cancellation/refund that completes outside an immediate
    /// `purchaseSubscription()` call (e.g. while the app wasn't running) still gets
    /// picked up the next time it launches or resumes.
    @Published private(set) var isSubscribed = false

    /// Broader than `isSubscribed`: true if the subscription is active OR the
    /// one-time tip has ever been purchased — matching Android's "any successful
    /// purchase sets the supporter flag" semantics. A tip doesn't lapse the way a
    /// subscription can, and (unlike a subscription) never appears in
    /// `Transaction.currentEntitlements` at all once its transaction is finished, so
    /// this is backed by `supporterStatusPersistence` rather than re-derived from
    /// StoreKit on every launch. `AppState.isSupporter` mirrors this directly.
    @Published private(set) var isSupporter: Bool

    private(set) var subscriptionProduct: Product?
    private(set) var oneTimeTipProduct: Product?

    /// Overridable only for `StoreKitBillingManagerTests`' "product not found" case —
    /// every real call site uses the two `static let` ids above.
    private let subscriptionProductID: String
    private let oneTimeTipProductID: String

    private let supporterStatusPersistence: SupporterStatusPersistence
    private let bridge = LibravaultDomainBridge.shared
    private var transactionUpdatesTask: Task<Void, Never>?

    init(
        subscriptionProductID: String = StoreKitBillingManager.subscriptionProductID,
        oneTimeTipProductID: String = StoreKitBillingManager.oneTimeTipProductID,
        supporterStatusPersistence: SupporterStatusPersistence = SupporterStatusPersistence()
    ) {
        self.subscriptionProductID = subscriptionProductID
        self.oneTimeTipProductID = oneTimeTipProductID
        self.supporterStatusPersistence = supporterStatusPersistence
        // Fast local cache read synchronously at init so SettingsView's "★ You're a
        // Supporter" line doesn't flicker to hidden for the instant between launch and
        // the real async entitlement check below finishing.
        self.isSupporter = supporterStatusPersistence.loadIsSupporter()

        // Every existing AppState test constructs `AppState()` with this manager's
        // default init (AppState.init's `billingManager` parameter), so firing real
        // StoreKit calls unconditionally here would run them — unnecessarily, and
        // possibly unreliably (mirrors this codebase's confirmed AVAudioSession
        // CI-Simulator-hang gotcha; see AudioPlaybackEngine.isRunningUnderXCTest) —
        // for every single one of those unrelated tests, not just the ones actually
        // exercising billing. `StoreKitBillingManagerTests` calls `loadProducts()`/
        // `refreshEntitlements()`/`purchase*()` directly against a real `SKTestSession`
        // instead of relying on this automatic init-time kickoff.
        guard !Self.isRunningUnderXCTest else { return }

        // Catches purchases/renewals that complete outside the immediate
        // purchase*() call below — a renewal while the app wasn't running, or (with
        // Family Sharing/Ask to Buy) a purchase approved on another device.
        transactionUpdatesTask = Task { [weak self] in
            for await update in Transaction.updates {
                await self?.handle(updateResult: update)
            }
        }

        Task { [weak self] in
            await self?.loadProducts()
            await self?.refreshEntitlements()
        }
    }

    deinit {
        transactionUpdatesTask?.cancel()
    }

    private static var isRunningUnderXCTest: Bool {
        ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil
    }

    // MARK: - Loading

    /// Fetches both products from the App Store. `Product.products(for:)` throws (or
    /// returns an empty/partial array) when App Store Connect has no matching product
    /// configured — the expected state until a banking/tax agreement is signed and the
    /// two products are created there — so this fails soft: `productsAvailable` just
    /// stays `false` rather than surfacing an error anywhere.
    func loadProducts() async {
        do {
            let products = try await Product.products(for: [subscriptionProductID, oneTimeTipProductID])
            subscriptionProduct = products.first { $0.id == subscriptionProductID }
            oneTimeTipProduct = products.first { $0.id == oneTimeTipProductID }
            productsAvailable = subscriptionProduct != nil && oneTimeTipProduct != nil
        } catch {
            subscriptionProduct = nil
            oneTimeTipProduct = nil
            productsAvailable = false
            bridge.logError("Couldn't load StoreKit products", tag: "Billing", error: error)
        }
    }

    /// Recomputes `isSubscribed` from `Transaction.currentEntitlements` — the real
    /// source of truth StoreKit itself keeps, independent of whatever
    /// `supporterStatusPersistence` has cached.
    func refreshEntitlements() async {
        var subscribed = false
        for await result in Transaction.currentEntitlements {
            guard case .verified(let transaction) = result,
                  transaction.productID == subscriptionProductID,
                  transaction.revocationDate == nil else { continue }
            subscribed = true
        }
        isSubscribed = subscribed
        if subscribed {
            markSupporter()
        }
    }

    // MARK: - Purchasing

    func purchaseSubscription() async {
        guard let subscriptionProduct else { return }
        await purchase(subscriptionProduct)
    }

    func purchaseOneTimeTip() async {
        guard let oneTimeTipProduct else { return }
        await purchase(oneTimeTipProduct)
    }

    private func purchase(_ product: Product) async {
        do {
            let result = try await product.purchase()
            switch result {
            case .success(let verificationResult):
                await handle(updateResult: verificationResult)
            case .userCancelled, .pending:
                break
            @unknown default:
                break
            }
        } catch {
            bridge.logError("StoreKit purchase failed for \(product.id)", tag: "Billing", error: error)
        }
    }

    // MARK: - Transaction handling

    /// Shared by both the immediate `purchase()` call above and the long-running
    /// `Transaction.updates` listener from init — a renewal or a purchase approved
    /// elsewhere arrives the same way.
    private func handle(updateResult: VerificationResult<Transaction>) async {
        guard case .verified(let transaction) = updateResult else {
            // Unverified (failed StoreKit's JWS signature check) — deliberately not
            // finished and not treated as a real purchase.
            bridge.logError("StoreKit transaction failed verification", tag: "Billing")
            return
        }

        await transaction.finish()

        // Deliberately `if`/`else if` with explicit `==`, not a `switch` on
        // `transaction.productID` with `subscriptionProductID`/`oneTimeTipProductID`
        // as bare case labels — a bare identifier in a `case` pattern is parsed as an
        // irrefutable *identifier pattern* (a new binding), not an equality
        // comparison against the outer `let`, so it would match unconditionally on
        // the very first case regardless of the transaction's real product id. This
        // is exactly why Swift requires the leading dot (`case .foo`) to match
        // against an enum case by name instead.
        if transaction.productID == subscriptionProductID {
            if transaction.revocationDate == nil {
                isSubscribed = true
                markSupporter()
            } else {
                // A refund/revocation arriving via Transaction.updates for a
                // subscription that's no longer current — recheck the real
                // entitlement set rather than assuming this means "unsubscribed"
                // (a revoked past period doesn't necessarily mean the *current*
                // period isn't still entitled).
                await refreshEntitlements()
            }
        } else if transaction.productID == oneTimeTipProductID {
            markSupporter()
        }
    }

    private func markSupporter() {
        guard !isSupporter else { return }
        isSupporter = true
        supporterStatusPersistence.save(isSupporter: true)
    }
}
