package com.remnant.dreams.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.remnant.dreams.data.PrefsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Google Play Billing integration for the one-time Remnant Pro unlock.
 *
 * Offline-first design: the entitlement is cached locally (PrefsManager.isPro) and the
 * cache is only ever changed by a SUCCESSFUL queryPurchases response or a successful
 * purchase. Network failures never revoke Pro. See [EntitlementEvaluator].
 *
 * ============================================================================
 * TODO(Sean) -- PLAY CONSOLE SETUP REQUIRED BEFORE THIS WORKS IN PRODUCTION
 * ============================================================================
 * The in-app product must be created by Sean in the Play Console (his account,
 * his approval). Exact steps:
 *
 *   1. Play Console > Remnant > Monetise > Products > In-app products > Create product
 *   2. Product ID:      remnant_pro          (must match PRO_PRODUCT_ID below, EXACTLY,
 *                                             it can never be changed after creation)
 *   3. Name:            Remnant Pro
 *   4. Description:     One-time unlock: search across your dreams and journal export.
 *                       Future Pro features (pattern tracking, Wake Word) included.
 *   5. Price:           A$19.99 (launch price; raise to A$29.99 after the first 1,000
 *                       buyers -- price changes are allowed, product ID changes are not)
 *   6. Status:          Active
 *
 * Notes:
 * - In-app products can only be created AFTER an AAB with the com.android.vending.BILLING
 *   permission (added automatically by the billing library) has been uploaded to any
 *   track (internal testing is fine).
 * - Purchases can only be tested by licence-tester accounts (Play Console > Settings >
 *   Licence testing) on a build signed and distributed through Play (internal testing
 *   track). Sideloaded debug builds use SIMULATE_PRO instead.
 * ============================================================================
 */
class BillingManager private constructor(context: Context) : PurchasesUpdatedListener {

    private val appContext = context.applicationContext
    private val prefs = PrefsManager(appContext)

    enum class PurchaseOutcome { SUCCESS, PENDING, CANCELLED, ALREADY_OWNED, ERROR }

    /** Cached Pro entitlement as a reactive stream for UI. Seeded from the local cache. */
    private val _isProFlow = MutableStateFlow(prefs.isPro)
    val isProFlow: StateFlow<Boolean> = _isProFlow

    /** Set by the purchase UI (SettingsActivity) to receive purchase flow outcomes. */
    var purchaseOutcomeListener: ((PurchaseOutcome) -> Unit)? = null

    private val billingClient: BillingClient = BillingClient.newBuilder(appContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .enableAutoServiceReconnection()
        .build()

    /**
     * Connect to Play and refresh the entitlement. Called on app start (RemnantApp) --
     * this is what restores Pro on a fresh install / new device. Safe to call repeatedly.
     */
    fun startConnection() {
        if (billingClient.isReady) {
            refreshEntitlement()
            return
        }
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    refreshEntitlement()
                } else {
                    // Play unavailable (no Play Services, blocked, etc). Keep cached value.
                    Log.w(TAG, "Billing setup failed: ${billingResult.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                // enableAutoServiceReconnection() handles reconnection.
            }
        })
    }

    /**
     * Query owned one-time products and update the cached entitlement.
     * "Restore purchase" in Settings calls this directly.
     */
    fun refreshEntitlement(onComplete: ((isPro: Boolean) -> Unit)? = null) {
        if (!billingClient.isReady) {
            // Offline / not connected: report the cached value, change nothing.
            onComplete?.invoke(isProUnlocked(appContext))
            startConnection()
            return
        }
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
            val succeeded = billingResult.responseCode == BillingClient.BillingResponseCode.OK
            val result = if (succeeded) EntitlementEvaluator.evaluate(purchases.toInfo()) else null
            applyEntitlement(succeeded, result)
            onComplete?.invoke(isProUnlocked(appContext))
        }
    }

    /** Fetch ProductDetails for the Pro product (price display + purchase flow). */
    fun queryProDetails(callback: (ProductDetails?) -> Unit) {
        if (!billingClient.isReady) {
            callback(null)
            startConnection()
            return
        }
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRO_PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()
        billingClient.queryProductDetailsAsync(params) { billingResult, detailsResult ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                callback(detailsResult.productDetailsList.firstOrNull())
            } else {
                Log.w(TAG, "queryProductDetails failed: ${billingResult.debugMessage}")
                callback(null)
            }
        }
    }

    /** Launch the Google Play purchase sheet for Remnant Pro. */
    fun launchPurchase(activity: Activity, productDetails: ProductDetails) {
        val detailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)
            .build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(detailsParams))
            .build()
        val result = billingClient.launchBillingFlow(activity, flowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.w(TAG, "launchBillingFlow failed: ${result.debugMessage}")
            purchaseOutcomeListener?.invoke(PurchaseOutcome.ERROR)
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val result = EntitlementEvaluator.evaluate(purchases.orEmpty().toInfo())
                // A purchase result only ever ADDS entitlement; never clears the cache here.
                if (result.isEntitled) applyEntitlement(succeeded = true, result = result)
                else if (result.tokensToAcknowledge.isNotEmpty()) acknowledge(result.tokensToAcknowledge)
                purchaseOutcomeListener?.invoke(
                    when {
                        result.isEntitled -> PurchaseOutcome.SUCCESS
                        result.hasPendingPurchase -> PurchaseOutcome.PENDING
                        else -> PurchaseOutcome.ERROR
                    }
                )
            }
            BillingClient.BillingResponseCode.USER_CANCELED ->
                purchaseOutcomeListener?.invoke(PurchaseOutcome.CANCELLED)
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                // Owned but our cache missed it -- restore.
                refreshEntitlement()
                purchaseOutcomeListener?.invoke(PurchaseOutcome.ALREADY_OWNED)
            }
            else -> {
                Log.w(TAG, "Purchase failed: ${billingResult.debugMessage}")
                purchaseOutcomeListener?.invoke(PurchaseOutcome.ERROR)
            }
        }
    }

    private fun applyEntitlement(succeeded: Boolean, result: EntitlementResult?) {
        val newValue = EntitlementEvaluator.resolveCachedEntitlement(
            querySucceeded = succeeded,
            queryResult = result,
            cachedIsPro = prefs.isPro
        )
        if (prefs.isPro != newValue) {
            prefs.isPro = newValue
            Log.i(TAG, "Pro entitlement cache updated: $newValue")
        }
        _isProFlow.value = newValue
        result?.tokensToAcknowledge?.takeIf { it.isNotEmpty() }?.let { acknowledge(it) }
    }

    /**
     * Acknowledge completed purchases. Google auto-refunds unacknowledged purchases
     * after 3 days, so this retries on every entitlement refresh until it succeeds.
     */
    private fun acknowledge(tokens: List<String>) {
        tokens.forEach { token ->
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(token)
                .build()
            billingClient.acknowledgePurchase(params) { billingResult ->
                if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    Log.w(TAG, "Acknowledge failed (will retry on next refresh): ${billingResult.debugMessage}")
                }
            }
        }
    }

    private fun List<Purchase>.toInfo(): List<PurchaseInfo> = map {
        PurchaseInfo(
            products = it.products,
            purchaseState = it.purchaseState,
            isAcknowledged = it.isAcknowledged,
            purchaseToken = it.purchaseToken
        )
    }

    companion object {
        private const val TAG = "BillingManager"

        /** One-time in-app product ID. Must match the Play Console product exactly. */
        const val PRO_PRODUCT_ID = "remnant_pro"

        @Volatile
        private var instance: BillingManager? = null

        fun getInstance(context: Context): BillingManager =
            instance ?: synchronized(this) {
                instance ?: BillingManager(context).also { instance = it }
            }

        /**
         * The single question the rest of the app asks: is Pro unlocked?
         * True when the cached Play entitlement says so, or when this is a debug build
         * with SIMULATE_PRO enabled (see app/build.gradle.kts) so the app is fully
         * testable without a Play account.
         */
        fun isProUnlocked(context: Context): Boolean =
            com.remnant.dreams.BuildConfig.SIMULATE_PRO || PrefsManager(context).isPro
    }
}
