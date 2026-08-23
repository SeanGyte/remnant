package com.remnant.dreams.billing

/**
 * Platform-free snapshot of a Play Billing purchase, so entitlement logic can be
 * unit-tested without the Play Billing library or an Android device.
 */
data class PurchaseInfo(
    val products: List<String>,
    val purchaseState: Int,
    val isAcknowledged: Boolean,
    val purchaseToken: String
)

/**
 * Mirror of com.android.billingclient.api.Purchase.PurchaseState values.
 * Kept separate so this file has no dependency on the billing library.
 */
object PurchaseStates {
    const val UNSPECIFIED = 0
    const val PURCHASED = 1
    const val PENDING = 2
}

data class EntitlementResult(
    /** True when a completed (PURCHASED) Pro purchase exists. */
    val isEntitled: Boolean,
    /** True when a Pro purchase is awaiting payment (e.g. cash top-up). Not yet entitled. */
    val hasPendingPurchase: Boolean,
    /** Purchase tokens that are PURCHASED but not yet acknowledged. */
    val tokensToAcknowledge: List<String>
)

/**
 * Pure entitlement logic for the one-time Remnant Pro unlock.
 *
 * Rules:
 * - Entitled only when a purchase of the Pro product is in the PURCHASED state.
 *   PENDING purchases (delayed payment methods) do not grant Pro until they complete.
 * - Every PURCHASED-but-unacknowledged purchase must be acknowledged within 3 days
 *   or Google refunds it, so those tokens are surfaced for acknowledgement.
 */
object EntitlementEvaluator {

    fun evaluate(
        purchases: List<PurchaseInfo>,
        productId: String = BillingManager.PRO_PRODUCT_ID
    ): EntitlementResult {
        val proPurchases = purchases.filter { productId in it.products }
        val completed = proPurchases.filter { it.purchaseState == PurchaseStates.PURCHASED }

        return EntitlementResult(
            isEntitled = completed.isNotEmpty(),
            hasPendingPurchase = proPurchases.any { it.purchaseState == PurchaseStates.PENDING },
            tokensToAcknowledge = completed
                .filter { !it.isAcknowledged }
                .map { it.purchaseToken }
        )
    }

    /**
     * Offline-first cache resolution. Decides the new locally cached entitlement after
     * a queryPurchases attempt:
     * - Query failed (no network, Play unavailable, service disconnected): KEEP the cached
     *   value. A paying user must never lose Pro because they are offline.
     * - Query succeeded: trust the store completely. This both restores entitlement on a
     *   fresh install and revokes it after a refund.
     */
    fun resolveCachedEntitlement(
        querySucceeded: Boolean,
        queryResult: EntitlementResult?,
        cachedIsPro: Boolean
    ): Boolean {
        return if (querySucceeded && queryResult != null) {
            queryResult.isEntitled
        } else {
            cachedIsPro
        }
    }
}
