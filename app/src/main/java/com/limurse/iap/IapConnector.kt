package com.limurse.iap

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.DelicateCoroutinesApi

/**
 * Initialize billing service.
 *
 * @param context Application context.
 * @param nonConsumableKeys SKU list for non-consumable one-time products.
 * @param consumableKeys SKU list for consumable one-time products.
 * @param subscriptionKeys SKU list for subscriptions.
 * @param key Key to verify purchase messages. Leave it empty if you want to skip verification.
 * @param enableLogging Log operations/errors to the logcat for debugging purposes.
 * @param autoStart Start the BillingClient from the constructor. Set false when listeners must be
 * registered before the initial product/restore callbacks are allowed to run.
 */
@OptIn(DelicateCoroutinesApi::class)
class IapConnector @JvmOverloads constructor(
    context: Context,
    nonConsumableKeys: List<String> = emptyList(),
    consumableKeys: List<String> = emptyList(),
    subscriptionKeys: List<String> = emptyList(),
    private val key: String? = null,
    private val enableLogging: Boolean = false,
    autoStart: Boolean = true
) {

    private var mBillingService: IBillingService? = null
    private var started: Boolean = false

    init {
        val contextLocal = context.applicationContext ?: context
        mBillingService = BillingService(contextLocal, nonConsumableKeys, consumableKeys, subscriptionKeys)
        if (autoStart) {
            start()
        }
    }

    /**
     * Start Google Play Billing once. This is intentionally separate from listener registration so
     * callers such as the Godot bridge can subscribe before the first product/restore callbacks.
     */
    fun start() {
        if (started) {
            return
        }
        started = true
        getBillingService().enableDebugLogging(enableLogging)
        getBillingService().init(key)
    }

    fun addBillingClientConnectionListener(billingClientConnectionListener: BillingClientConnectionListener) {
        getBillingService().addBillingClientConnectionListener(billingClientConnectionListener)
    }

    fun removeBillingClientConnectionListener(billingClientConnectionListener: BillingClientConnectionListener) {
        getBillingService().removeBillingClientConnectionListener(billingClientConnectionListener)
    }

    fun addPurchaseListener(purchaseServiceListener: PurchaseServiceListener) {
        getBillingService().addPurchaseListener(purchaseServiceListener)
    }

    fun removePurchaseListener(purchaseServiceListener: PurchaseServiceListener) {
        getBillingService().removePurchaseListener(purchaseServiceListener)
    }

    fun addSubscriptionListener(subscriptionServiceListener: SubscriptionServiceListener) {
        getBillingService().addSubscriptionListener(subscriptionServiceListener)
    }

    fun removeSubscriptionListener(subscriptionServiceListener: SubscriptionServiceListener) {
        getBillingService().removeSubscriptionListener(subscriptionServiceListener)
    }

    fun purchase(activity: Activity, sku: String, obfuscatedAccountId: String? = null, obfuscatedProfileId: String? = null) {
        getBillingService().buy(activity, sku, obfuscatedAccountId, obfuscatedProfileId)
    }

    fun subscribe(activity: Activity, sku: String, obfuscatedAccountId: String? = null, obfuscatedProfileId: String? = null) {
        getBillingService().subscribe(activity, sku, obfuscatedAccountId, obfuscatedProfileId)
    }

    fun unsubscribe(activity: Activity, sku: String) {
        getBillingService().unsubscribe(activity, sku)
    }

    fun destroy() {
        getBillingService().close()
        started = false
    }

    fun getCountryCode(listener: BillingClientGetCountryListener) {
        getBillingService().getCountryCode(listener)
    }

    private fun getBillingService(): IBillingService {
        return mBillingService ?: throw RuntimeException("Call IapConnector to initialize billing service")
    }
}
