package com.remnant.dreams.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntitlementEvaluatorTest {

    private fun purchase(
        products: List<String> = listOf(BillingManager.PRO_PRODUCT_ID),
        state: Int = PurchaseStates.PURCHASED,
        acknowledged: Boolean = true,
        token: String = "token-1"
    ) = PurchaseInfo(products, state, acknowledged, token)

    // --- evaluate ---

    @Test
    fun `no purchases means not entitled`() {
        val result = EntitlementEvaluator.evaluate(emptyList())
        assertFalse(result.isEntitled)
        assertFalse(result.hasPendingPurchase)
        assertTrue(result.tokensToAcknowledge.isEmpty())
    }

    @Test
    fun `acknowledged purchased pro grants entitlement`() {
        val result = EntitlementEvaluator.evaluate(listOf(purchase()))
        assertTrue(result.isEntitled)
        assertTrue(result.tokensToAcknowledge.isEmpty())
    }

    @Test
    fun `unacknowledged purchase grants entitlement and surfaces token for acknowledgement`() {
        val result = EntitlementEvaluator.evaluate(
            listOf(purchase(acknowledged = false, token = "ack-me"))
        )
        assertTrue(result.isEntitled)
        assertEquals(listOf("ack-me"), result.tokensToAcknowledge)
    }

    @Test
    fun `pending purchase does not grant entitlement but is flagged`() {
        val result = EntitlementEvaluator.evaluate(
            listOf(purchase(state = PurchaseStates.PENDING, acknowledged = false))
        )
        assertFalse(result.isEntitled)
        assertTrue(result.hasPendingPurchase)
        assertTrue(result.tokensToAcknowledge.isEmpty())
    }

    @Test
    fun `unspecified state does not grant entitlement`() {
        val result = EntitlementEvaluator.evaluate(
            listOf(purchase(state = PurchaseStates.UNSPECIFIED))
        )
        assertFalse(result.isEntitled)
        assertFalse(result.hasPendingPurchase)
    }

    @Test
    fun `purchases of other products are ignored`() {
        val result = EntitlementEvaluator.evaluate(
            listOf(purchase(products = listOf("some_other_product")))
        )
        assertFalse(result.isEntitled)
    }

    @Test
    fun `multi-product purchase containing pro counts`() {
        val result = EntitlementEvaluator.evaluate(
            listOf(purchase(products = listOf("bundle", BillingManager.PRO_PRODUCT_ID)))
        )
        assertTrue(result.isEntitled)
    }

    @Test
    fun `mixed purchases acknowledge only completed unacknowledged pro tokens`() {
        val result = EntitlementEvaluator.evaluate(
            listOf(
                purchase(acknowledged = true, token = "old"),
                purchase(acknowledged = false, token = "new"),
                purchase(state = PurchaseStates.PENDING, acknowledged = false, token = "pending"),
                purchase(products = listOf("other"), acknowledged = false, token = "other")
            )
        )
        assertTrue(result.isEntitled)
        assertEquals(listOf("new"), result.tokensToAcknowledge)
    }

    @Test
    fun `custom product id is respected`() {
        val result = EntitlementEvaluator.evaluate(
            listOf(purchase(products = listOf("custom_pro"))),
            productId = "custom_pro"
        )
        assertTrue(result.isEntitled)
    }

    // --- resolveCachedEntitlement (offline-first behaviour) ---

    @Test
    fun `failed query keeps cached pro`() {
        assertTrue(
            EntitlementEvaluator.resolveCachedEntitlement(
                querySucceeded = false, queryResult = null, cachedIsPro = true
            )
        )
    }

    @Test
    fun `failed query keeps cached free`() {
        assertFalse(
            EntitlementEvaluator.resolveCachedEntitlement(
                querySucceeded = false, queryResult = null, cachedIsPro = false
            )
        )
    }

    @Test
    fun `successful query grants pro over stale free cache`() {
        val entitled = EntitlementEvaluator.evaluate(listOf(purchase()))
        assertTrue(
            EntitlementEvaluator.resolveCachedEntitlement(
                querySucceeded = true, queryResult = entitled, cachedIsPro = false
            )
        )
    }

    @Test
    fun `successful empty query revokes stale pro cache (refund case)`() {
        val empty = EntitlementEvaluator.evaluate(emptyList())
        assertFalse(
            EntitlementEvaluator.resolveCachedEntitlement(
                querySucceeded = true, queryResult = empty, cachedIsPro = true
            )
        )
    }

    @Test
    fun `success flag without result keeps cache`() {
        assertTrue(
            EntitlementEvaluator.resolveCachedEntitlement(
                querySucceeded = true, queryResult = null, cachedIsPro = true
            )
        )
    }
}
