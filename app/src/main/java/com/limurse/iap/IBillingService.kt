package com.limurse.iap

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.annotation.CallSuper
import com.android.billingclient.api.BillingClient

abstract class IBillingService {

    private val purchaseServiceListeners: MutableList<PurchaseServiceListener> = mutableListOf()
    private val subscriptionServiceListeners: MutableList<SubscriptionServiceListener> = mutableListOf()
    private val billingClientConnectedListeners: MutableList<BillingClientConnectionListener> = mutableListOf()

    fun addBillingClientConnectionListener(billingClientConnectionListener: BillingClientConnectionListener) {
        billingClientConnectedListeners.add(billingClientConnectionListener)
    }

    fun removeBillingClientConnectionListener(billingClientConnectionListener: BillingClientConnectionListener) {
        billingClientConnectedListeners.remove(billingClientConnectionListener)
    }

    fun addPurchaseListener(purchaseServiceListener: PurchaseServiceListener) {
        purchaseServiceListeners.add(purchaseServiceListener)
    }

    fun removePurchaseListener(purchaseServiceListener: PurchaseServiceListener) {
        purchaseServiceListeners.remove(purchaseServiceListener)
    }

    fun addSubscriptionListener(subscriptionServiceListener: SubscriptionServiceListener) {
        subscriptionServiceListeners.add(subscriptionServiceListener)
    }

    fun removeSubscriptionListener(subscriptionServiceListener: SubscriptionServiceListener) {
        subscriptionServiceListeners.remove(subscriptionServiceListener)
    }

    /**
     * @param purchaseInfo Product specifier
     * @param isRestore Flag indicating whether it's a fresh purchase or restored product
     */
    fun productOwned(purchaseInfo: DataWrappers.PurchaseInfo, isRestore: Boolean) {
        runOnUiThread {
            for (purchaseServiceListener in purchaseServiceListeners.toList()) {
                if (isRestore) {
                    purchaseServiceListener.onProductRestored(purchaseInfo)
                } else {
                    purchaseServiceListener.onProductPurchased(purchaseInfo)
                }
            }
        }
    }

    /**
     * @param purchaseInfo Subscription specifier
     * @param isRestore Flag indicating whether it's a fresh purchase or restored subscription
     */
    fun subscriptionOwned(purchaseInfo: DataWrappers.PurchaseInfo, isRestore: Boolean) {
        runOnUiThread {
            for (subscriptionServiceListener in subscriptionServiceListeners.toList()) {
                if (isRestore) {
                    subscriptionServiceListener.onSubscriptionRestored(purchaseInfo)
                } else {
                    subscriptionServiceListener.onSubscriptionPurchased(purchaseInfo)
                }
            }
        }
    }

    fun isBillingClientConnected(status: Boolean, responseCode: Int) {
        runOnUiThread {
            for (billingServiceListener in billingClientConnectedListeners.toList()) {
                billingServiceListener.onConnected(status, responseCode)
            }
        }
    }

    /**
     * Product callbacks are dispatched immediately when Billing already called us on the main
     * thread. Routing by ProductType prevents a single SKU from being emitted twice to Godot with
     * conflicting type_product values.
     */
    fun updatePrices(
        iapKeyPrices: Map<String, List<DataWrappers.ProductDetails>>,
        productType: String? = null
    ) {
        runOnUiThread {
            when (productType) {
                BillingClient.ProductType.INAPP -> {
                    for (listener in purchaseServiceListeners.toList()) {
                        listener.onPricesUpdated(iapKeyPrices)
                    }
                }
                BillingClient.ProductType.SUBS -> {
                    for (listener in subscriptionServiceListeners.toList()) {
                        listener.onPricesUpdated(iapKeyPrices)
                    }
                }
                else -> {
                    // Preserve the previous internal API behavior for any caller that doesn't pass a type.
                    for (listener in purchaseServiceListeners.toList()) {
                        listener.onPricesUpdated(iapKeyPrices)
                    }
                    for (listener in subscriptionServiceListeners.toList()) {
                        listener.onPricesUpdated(iapKeyPrices)
                    }
                }
            }
        }
    }

    fun updateFailedPurchases(purchaseInfo: List<DataWrappers.PurchaseInfo>? = null, billingResponseCode: Int? = null) {
        purchaseInfo?.forEach {
            updateFailedPurchase(it, billingResponseCode)
        } ?: updateFailedPurchase()
    }

    fun updateFailedPurchase(purchaseInfo: DataWrappers.PurchaseInfo? = null, billingResponseCode: Int? = null) {
        runOnUiThread {
            for (billingServiceListener in purchaseServiceListeners.toList()) {
                billingServiceListener.onPurchaseFailed(purchaseInfo, billingResponseCode)
            }
            for (billingServiceListener in subscriptionServiceListeners.toList()) {
                billingServiceListener.onPurchaseFailed(purchaseInfo, billingResponseCode)
            }
        }
    }

    abstract fun init(key: String?)
    abstract fun buy(activity: Activity, sku: String, obfuscatedAccountId: String?, obfuscatedProfileId: String?)
    abstract fun subscribe(activity: Activity, sku: String, obfuscatedAccountId: String?, obfuscatedProfileId: String?)
    abstract fun unsubscribe(activity: Activity, sku: String)
    abstract fun enableDebugLogging(enable: Boolean)
    abstract fun getCountryCode(listener: BillingClientGetCountryListener)

    @CallSuper
    open fun close() {
        subscriptionServiceListeners.clear()
        purchaseServiceListeners.clear()
        billingClientConnectedListeners.clear()
    }
}

private val uiHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

fun findUiHandler(): Handler = uiHandler

fun runOnUiThread(action: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        action()
    } else {
        uiHandler.post(action)
    }
}
