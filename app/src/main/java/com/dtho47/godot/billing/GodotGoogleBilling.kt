package com.dtho47.godot.billing

import android.util.Log
import com.limurse.iap.BillingClientConnectionListener
import com.limurse.iap.BillingClientGetCountryListener
import com.limurse.iap.DataWrappers
import com.limurse.iap.IapConnector
import com.limurse.iap.PurchaseServiceListener
import com.limurse.iap.SubscriptionServiceListener
import org.godotengine.godot.Dictionary
import org.godotengine.godot.Godot
import org.godotengine.godot.plugin.GodotPlugin
import org.godotengine.godot.plugin.SignalInfo
import org.godotengine.godot.plugin.UsedByGodot

class GodotGoogleBilling(godot: Godot) : GodotPlugin(godot) {
    private val tag: String = "GodotGoogleBilling"

    private val signalPricesUpdate = SignalInfo("prices_in_app_update", Object::class.java)
    private val signalProductPurchased = SignalInfo("product_purchased", Object::class.java)
    private val signalProductRestored = SignalInfo("product_restored", Object::class.java)
    private val signalProductFailed = SignalInfo("product_failed", Object::class.java)
    private val signalCountryCodeUpdate = SignalInfo("country_code_update", String::class.java)

    private lateinit var iapConnector: IapConnector
    private var countryCode: String = "US"

    companion object {
        const val TYPE_IN_APP = 0
        const val TYPE_SUBS = 3
        const val ERR_OK = 0
    }

    override fun getPluginName(): String = tag

    override fun getPluginSignals(): Set<SignalInfo> = setOf(
        signalPricesUpdate,
        signalProductPurchased,
        signalProductRestored,
        signalProductFailed,
        signalCountryCodeUpdate
    )

    @UsedByGodot
    fun get_country(): String {
        if (!::iapConnector.isInitialized) {
            Log.w(tag, "get_country called before build; returning cached country code")
            return countryCode
        }

        iapConnector.getCountryCode(object : BillingClientGetCountryListener {
            override fun onResult(countryCode: String) {
                this@GodotGoogleBilling.countryCode = countryCode
                emitSignal(godot, tag, signalCountryCodeUpdate, countryCode)
            }
        })
        return countryCode
    }

    @UsedByGodot
    fun build(
        nonConsumablesList: Array<String>,
        consumablesList: Array<String>,
        subsList: Array<String>,
        licenseKey: String
    ) {
        Log.i(tag, "build from sku nonConsumablesList: ${nonConsumablesList.toList()}")
        Log.i(tag, "build from sku consumablesList: ${consumablesList.toList()}")
        Log.i(tag, "build from sku subsList: ${subsList.toList()}")

        // BillingClient recommends a single active client. Rebuilding the Godot singleton should not
        // leave an older connector bound to Google Play and producing duplicate callbacks.
        if (::iapConnector.isInitialized) {
            iapConnector.destroy()
        }

        iapConnector = IapConnector(
            context = godot.requireActivity(),
            nonConsumableKeys = nonConsumablesList.toList(),
            consumableKeys = consumablesList.toList(),
            subscriptionKeys = subsList.toList(),
            key = licenseKey.ifEmpty { null },
            enableLogging = true,
            autoStart = false
        )

        // Register every Godot-facing listener before starting BillingClient. A fast Play Store
        // connection must not be able to publish product/restore callbacks before listeners exist.
        iapConnector.addBillingClientConnectionListener(object : BillingClientConnectionListener {
            override fun onConnected(status: Boolean, billingResponseCode: Int) {
                Log.i(tag, "billing connected - $status. Code - $billingResponseCode")
            }
        })

        iapConnector.addPurchaseListener(object : PurchaseServiceListener {
            override fun onPricesUpdated(iapKeyPrices: Map<String, List<DataWrappers.ProductDetails>>) {
                for ((sku, priceDetails) in iapKeyPrices) {
                    for (details in priceDetails) {
                        emitSignal(godot, tag, signalPricesUpdate, createPriceDictionary(sku, TYPE_IN_APP, details))
                    }
                }
            }

            override fun onProductPurchased(purchaseInfo: DataWrappers.PurchaseInfo) {
                emitSignal(godot, tag, signalProductPurchased, createTransactionDictionary(purchaseInfo, TYPE_IN_APP, ERR_OK))
            }

            override fun onProductRestored(purchaseInfo: DataWrappers.PurchaseInfo) {
                emitSignal(godot, tag, signalProductRestored, createTransactionDictionary(purchaseInfo, TYPE_IN_APP, ERR_OK))
            }

            override fun onPurchaseFailed(purchaseInfo: DataWrappers.PurchaseInfo?, billingResponseCode: Int?) {
                if (purchaseInfo != null) {
                    emitSignal(
                        godot,
                        tag,
                        signalProductFailed,
                        createTransactionDictionary(purchaseInfo, TYPE_IN_APP, billingResponseCode ?: -1)
                    )
                }
            }
        })

        iapConnector.addSubscriptionListener(object : SubscriptionServiceListener {
            override fun onSubscriptionRestored(purchaseInfo: DataWrappers.PurchaseInfo) {
                emitSignal(godot, tag, signalProductRestored, createTransactionDictionary(purchaseInfo, TYPE_SUBS, ERR_OK))
            }

            override fun onSubscriptionPurchased(purchaseInfo: DataWrappers.PurchaseInfo) {
                emitSignal(godot, tag, signalProductPurchased, createTransactionDictionary(purchaseInfo, TYPE_SUBS, ERR_OK))
            }

            override fun onPricesUpdated(iapKeyPrices: Map<String, List<DataWrappers.ProductDetails>>) {
                for ((sku, priceDetails) in iapKeyPrices) {
                    for (details in priceDetails) {
                        emitSignal(godot, tag, signalPricesUpdate, createPriceDictionary(sku, TYPE_SUBS, details))
                    }
                }
            }

            override fun onPurchaseFailed(purchaseInfo: DataWrappers.PurchaseInfo?, billingResponseCode: Int?) {
                if (purchaseInfo != null) {
                    emitSignal(
                        godot,
                        tag,
                        signalProductFailed,
                        createTransactionDictionary(purchaseInfo, TYPE_SUBS, billingResponseCode ?: -1)
                    )
                }
            }
        })

        iapConnector.start()
    }

    @UsedByGodot
    fun purchase(sku: String) {
        if (!::iapConnector.isInitialized) {
            Log.e(tag, "purchase called before build")
            return
        }
        iapConnector.purchase(godot.requireActivity(), sku)
    }

    @UsedByGodot
    fun subscribe(sku: String) {
        if (!::iapConnector.isInitialized) {
            Log.e(tag, "subscribe called before build")
            return
        }
        iapConnector.subscribe(godot.requireActivity(), sku)
    }

    @UsedByGodot
    fun unsubscribe(sku: String) {
        if (!::iapConnector.isInitialized) {
            Log.e(tag, "unsubscribe called before build")
            return
        }
        iapConnector.unsubscribe(godot.requireActivity(), sku)
    }

    private fun createPriceDictionary(
        sku: String,
        typeProduct: Int,
        details: DataWrappers.ProductDetails
    ): Dictionary {
        val description = details.description.orEmpty()
        val dict = Dictionary()
        dict["sku"] = sku
        dict["type_product"] = typeProduct
        dict["title"] = details.title.orEmpty()

        // `details` is the historical native key. `description` matches the documented/GDScript
        // wrapper name. Keep both so existing Godot code remains compatible.
        dict["details"] = description
        dict["description"] = description

        dict["price"] = details.price.orEmpty()
        dict["price_amount"] = details.priceAmount ?: 0.0
        dict["currency_code"] = details.priceCurrencyCode.orEmpty()
        dict["billing_cycle_count"] = details.billingCycleCount ?: 0
        dict["billing_period"] = details.billingPeriod.orEmpty()
        dict["recurrence_mode"] = details.recurrenceMode ?: 0
        return dict
    }

    private fun createTransactionDictionary(
        purchaseInfo: DataWrappers.PurchaseInfo,
        typeProduct: Int,
        responseCode: Int
    ): Dictionary {
        val dict = Dictionary()
        dict["sku"] = purchaseInfo.sku
        dict["type_product"] = typeProduct
        dict["is_acknowledged"] = purchaseInfo.isAcknowledged
        dict["is_auto_renewing"] = purchaseInfo.isAutoRenewing
        dict["purchase_state"] = purchaseInfo.purchaseState
        dict["purchase_token"] = purchaseInfo.purchaseToken
        dict["signature"] = purchaseInfo.signature
        dict["package_name"] = purchaseInfo.packageName
        dict["response_code"] = responseCode

        // Google explicitly allows purchases without an orderId (for example promo-code purchases).
        // Never pass a Kotlin null through Godot 3's Java/JNI Dictionary bridge.
        dict["order_id"] = purchaseInfo.orderId.orEmpty()
        dict["json"] = purchaseInfo.originalJson
        return dict
    }
}
