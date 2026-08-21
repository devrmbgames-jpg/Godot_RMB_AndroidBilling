package com.limurse.iap

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.android.billingclient.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BillingService(
    private val context: Context,
    private val nonConsumableKeys: List<String>,
    private val consumableKeys: List<String>,
    private val subscriptionSkuKeys: List<String>,
) : IBillingService(), PurchasesUpdatedListener, AcknowledgePurchaseResponseListener {

    private lateinit var mBillingClient: BillingClient
    private var decodedKey: String? = null
    private var enableDebug: Boolean = false

    /**
     * ProductDetails are cached only to preserve the plugin's existing public API and readiness model.
     * A fresh query is performed before launchBillingFlow so an outdated offer token is not used.
     */
    private val productDetails = mutableMapOf<String, ProductDetails?>()

    override fun init(key: String?) {
        decodedKey = key

        val pendingPurchasesParams = PendingPurchasesParams.newBuilder()
            .enableOneTimeProducts()
            .build()

        mBillingClient = BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases(pendingPurchasesParams)
            .enableAutoServiceReconnection()
            .build()

        mBillingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingServiceDisconnected() {
                log("onBillingServiceDisconnected")
            }

            override fun onBillingSetupFinished(billingResult: BillingResult) {
                log("onBillingSetupFinished: billingResult: $billingResult")

                if (billingResult.isOk()) {
                    isBillingClientConnected(true, billingResult.responseCode)
                    nonConsumableKeys.queryProductDetails(BillingClient.ProductType.INAPP) {
                        consumableKeys.queryProductDetails(BillingClient.ProductType.INAPP) {
                            subscriptionSkuKeys.queryProductDetails(BillingClient.ProductType.SUBS) {
                                CoroutineScope(Dispatchers.IO).launch {
                                    queryPurchases()
                                }
                            }
                        }
                    }
                } else {
                    isBillingClientConnected(false, billingResult.responseCode)
                }
            }
        })
    }

    private suspend fun queryPurchases() {
        try {
            val inAppResult: PurchasesResult = mBillingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            )
            if (inAppResult.billingResult.isOk()) {
                processPurchases(
                    inAppResult.purchasesList,
                    isRestore = true,
                    sourceProductType = BillingClient.ProductType.INAPP
                )
            } else {
                log("queryPurchases INAPP failed: ${inAppResult.billingResult.debugMessage}")
            }

            val subsResult: PurchasesResult = mBillingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            )
            if (subsResult.billingResult.isOk()) {
                processPurchases(
                    subsResult.purchasesList,
                    isRestore = true,
                    sourceProductType = BillingClient.ProductType.SUBS
                )
            } else {
                log("queryPurchases SUBS failed: ${subsResult.billingResult.debugMessage}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore active purchases", e)
            updateFailedPurchase(billingResponseCode = BillingClient.BillingResponseCode.ERROR)
        }
    }

    override fun buy(activity: Activity, sku: String, obfuscatedAccountId: String?, obfuscatedProfileId: String?) {
        if (!sku.isProductReady()) {
            log("buy. Google billing service is not ready yet. SKU is not ready: $sku")
            return
        }
        launchBillingFlow(activity, sku, BillingClient.ProductType.INAPP, obfuscatedAccountId, obfuscatedProfileId)
    }

    override fun subscribe(activity: Activity, sku: String, obfuscatedAccountId: String?, obfuscatedProfileId: String?) {
        if (!sku.isProductReady()) {
            log("subscribe. Google billing service is not ready yet. SKU is not ready: $sku")
            return
        }
        launchBillingFlow(activity, sku, BillingClient.ProductType.SUBS, obfuscatedAccountId, obfuscatedProfileId)
    }

    private fun launchBillingFlow(
        activity: Activity,
        sku: String,
        type: String,
        obfuscatedAccountId: String?,
        obfuscatedProfileId: String?
    ) {
        sku.toProductDetails(type, forceRefresh = true) { details ->
            if (details == null) {
                log("launchBillingFlow. Product details not available for sku: $sku")
                return@toProductDetails
            }

            val productDetailsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)

            val offerToken = when (type) {
                BillingClient.ProductType.SUBS -> details.subscriptionOfferDetails?.firstOrNull()?.offerToken
                BillingClient.ProductType.INAPP -> details.oneTimePurchaseOfferDetailsList?.firstOrNull()?.offerToken
                else -> null
            }

            if (!offerToken.isNullOrEmpty()) {
                productDetailsBuilder.setOfferToken(offerToken)
            }

            val billingFlowParamsBuilder = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(productDetailsBuilder.build()))

            if (obfuscatedAccountId != null) billingFlowParamsBuilder.setObfuscatedAccountId(obfuscatedAccountId)
            if (obfuscatedProfileId != null) billingFlowParamsBuilder.setObfuscatedProfileId(obfuscatedProfileId)

            val launchResult = mBillingClient.launchBillingFlow(activity, billingFlowParamsBuilder.build())
            if (!launchResult.isOk()) {
                log("launchBillingFlow failed for $sku: ${launchResult.responseCode} ${launchResult.debugMessage}")
                updateFailedPurchase(billingResponseCode = launchResult.responseCode)
            }
        }
    }

    override fun unsubscribe(activity: Activity, sku: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://play.google.com/store/account/subscriptions?package=${activity.packageName}&sku=$sku")
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Unsubscribing failed.", e)
        }
    }

    override fun enableDebugLogging(enable: Boolean) {
        enableDebug = enable
    }

    override fun getCountryCode(listener: BillingClientGetCountryListener) {
        if (!::mBillingClient.isInitialized || !mBillingClient.isReady) {
            Log.e(TAG, "getCountryCode failed: billing client is not ready")
            return
        }

        val getBillingConfigParams = GetBillingConfigParams.newBuilder().build()
        mBillingClient.getBillingConfigAsync(getBillingConfigParams) { billingResult, billingConfig ->
            if (billingResult.isOk() && billingConfig != null) {
                listener.onResult(billingConfig.countryCode)
            } else {
                Log.e(TAG, "getCountryCode failed: ${billingResult.responseCode} ${billingResult.debugMessage}")
            }
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        val responseCode = billingResult.responseCode
        log("onPurchasesUpdated: responseCode:$responseCode debugMessage:${billingResult.debugMessage}")

        if (!billingResult.isOk()) {
            updateFailedPurchases(purchases?.mapNotNull { safePurchaseInfo(it) }, responseCode)
        }

        when (responseCode) {
            BillingClient.BillingResponseCode.OK -> processPurchases(purchases)
            BillingClient.BillingResponseCode.USER_CANCELED -> log("onPurchasesUpdated: user canceled the purchase")
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                log("onPurchasesUpdated: item already owned; refreshing active purchases")
                CoroutineScope(Dispatchers.IO).launch { queryPurchases() }
            }
            BillingClient.BillingResponseCode.DEVELOPER_ERROR -> Log.e(
                TAG,
                "Google Play Billing developer error. Verify Play Console product IDs, package name, release signing, and eligibility."
            )
        }
    }

    private fun processPurchases(
        purchasesList: List<Purchase>?,
        isRestore: Boolean = false,
        sourceProductType: String? = null
    ) {
        if (purchasesList.isNullOrEmpty()) {
            log("processPurchases: no purchases")
            return
        }

        purchases@ for (purchase in purchasesList) {
            try {
                val sku = purchase.products.firstOrNull()
                if (sku.isNullOrEmpty()) {
                    updateFailedPurchase(safePurchaseInfo(purchase))
                    continue@purchases
                }

                if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
                    log("Purchase is pending; entitlement is deferred for sku: $sku")
                    continue@purchases
                }

                if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
                    updateFailedPurchase(safePurchaseInfo(purchase))
                    continue@purchases
                }

                if (!isSignatureValid(purchase)) {
                    updateFailedPurchase(safePurchaseInfo(purchase))
                    continue@purchases
                }

                val resolvedProductType = sourceProductType ?: resolveProductType(sku)

                // Kidduca's Godot transaction container asserts that every transaction references a
                // product already present in its configured catalog. Billing can still return legacy
                // owned SKUs that are no longer configured by the game. Restore configured products
                // even when ProductDetails is unfetched, but do not publish unknown legacy SKUs to Godot.
                if (isRestore && !isConfiguredProduct(sku, resolvedProductType)) {
                    Log.w(TAG, "Skipping restore for unconfigured sku=$sku type=$resolvedProductType")
                    continue@purchases
                }

                val isProductConsumable = consumableKeys.contains(sku)
                val purchaseInfo = getPurchaseInfo(purchase)

                when (resolvedProductType) {
                    BillingClient.ProductType.INAPP -> {
                        if (isProductConsumable) {
                            mBillingClient.consumeAsync(
                                ConsumeParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
                            ) { billingResult, _ ->
                                if (billingResult.isOk()) {
                                    productOwned(purchaseInfo, false)
                                } else {
                                    updateFailedPurchase(purchaseInfo, billingResult.responseCode)
                                }
                            }
                        } else {
                            productOwned(purchaseInfo, isRestore)
                        }
                    }
                    BillingClient.ProductType.SUBS -> subscriptionOwned(purchaseInfo, isRestore)
                    else -> {
                        updateFailedPurchase(purchaseInfo)
                        continue@purchases
                    }
                }

                if (!purchase.isAcknowledged && !isProductConsumable) {
                    mBillingClient.acknowledgePurchase(
                        AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build(),
                        this
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process purchase safely", e)
                updateFailedPurchase(safePurchaseInfo(purchase))
            }
        }
    }

    private fun resolveProductType(sku: String): String? = when {
        subscriptionSkuKeys.contains(sku) -> BillingClient.ProductType.SUBS
        nonConsumableKeys.contains(sku) || consumableKeys.contains(sku) -> BillingClient.ProductType.INAPP
        else -> productDetails[sku]?.productType
    }

    private fun isConfiguredProduct(sku: String, productType: String?): Boolean = when (productType) {
        BillingClient.ProductType.INAPP -> nonConsumableKeys.contains(sku) || consumableKeys.contains(sku)
        BillingClient.ProductType.SUBS -> subscriptionSkuKeys.contains(sku)
        else -> nonConsumableKeys.contains(sku) || consumableKeys.contains(sku) || subscriptionSkuKeys.contains(sku)
    }

    private fun getPurchaseInfo(purchase: Purchase): DataWrappers.PurchaseInfo = DataWrappers.PurchaseInfo(
        purchase.purchaseState,
        purchase.developerPayload,
        purchase.isAcknowledged,
        purchase.isAutoRenewing,
        purchase.orderId,
        purchase.originalJson,
        purchase.packageName,
        purchase.purchaseTime,
        purchase.purchaseToken,
        purchase.signature,
        purchase.products.firstOrNull().orEmpty(),
        purchase.accountIdentifiers
    )

    private fun safePurchaseInfo(purchase: Purchase): DataWrappers.PurchaseInfo? = try {
        getPurchaseInfo(purchase)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to convert Purchase to PurchaseInfo", e)
        null
    }

    private fun isSignatureValid(purchase: Purchase): Boolean {
        val key = decodedKey ?: return true
        return try {
            Security.verifyPurchase(key, purchase.originalJson, purchase.signature)
        } catch (e: Exception) {
            Log.e(TAG, "Purchase signature verification failed with an exception", e)
            false
        }
    }

    private fun List<String>.queryProductDetails(type: String, done: () -> Unit) {
        if (!::mBillingClient.isInitialized || !mBillingClient.isReady) {
            done()
            return
        }
        if (isEmpty()) {
            done()
            return
        }

        val productList = map { sku ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(sku)
                .setProductType(type)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder().setProductList(productList).build()

        mBillingClient.queryProductDetailsAsync(params) { billingResult, queryResult ->
            try {
                if (billingResult.isOk()) {
                    isBillingClientConnected(true, billingResult.responseCode)

                    queryResult.productDetailsList.forEach { details ->
                        productDetails[details.productId] = details
                    }
                    queryResult.unfetchedProductList.forEach { unfetched ->
                        productDetails[unfetched.productId] = null
                        log("Product not fetched: id=${unfetched.productId}, type=${unfetched.productType}, status=${unfetched.statusCode}")
                    }

                    val prices = queryResult.productDetailsList.associate { details ->
                        details.productId to details.toPriceDetails()
                    }
                    updatePrices(prices, type)
                } else {
                    log("queryProductDetails failed: ${billingResult.responseCode} ${billingResult.debugMessage}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process ProductDetails response for type=$type", e)
                updateFailedPurchase(billingResponseCode = BillingClient.BillingResponseCode.ERROR)
            } finally {
                done()
            }
        }
    }

    private fun String.toProductDetails(
        type: String,
        forceRefresh: Boolean = false,
        done: (productDetails: ProductDetails?) -> Unit = {}
    ) {
        if (!::mBillingClient.isInitialized || !mBillingClient.isReady) {
            done(null)
            return
        }

        if (!forceRefresh) {
            productDetails[this]?.let {
                done(it)
                return
            }
        }

        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(this)
            .setProductType(type)
            .build()
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()

        mBillingClient.queryProductDetailsAsync(params) { billingResult, queryResult ->
            try {
                if (billingResult.isOk()) {
                    isBillingClientConnected(true, billingResult.responseCode)
                    val details = queryResult.productDetailsList.find { it.productId == this }
                    productDetails[this] = details
                    done(details)
                } else {
                    done(null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process ProductDetails for sku=$this", e)
                done(null)
            }
        }
    }

    private fun ProductDetails.toPriceDetails(): List<DataWrappers.ProductDetails> = when (productType) {
        BillingClient.ProductType.SUBS -> subscriptionOfferDetails
            ?.firstOrNull()
            ?.pricingPhases
            ?.pricingPhaseList
            ?.map { pricingPhase ->
                DataWrappers.ProductDetails(
                    title = title,
                    description = description,
                    priceCurrencyCode = pricingPhase.priceCurrencyCode,
                    price = pricingPhase.formattedPrice,
                    priceAmount = pricingPhase.priceAmountMicros / 1_000_000.0,
                    billingCycleCount = pricingPhase.billingCycleCount,
                    billingPeriod = pricingPhase.billingPeriod,
                    recurrenceMode = pricingPhase.recurrenceMode
                )
            } ?: emptyList()

        BillingClient.ProductType.INAPP -> {
            val offer = oneTimePurchaseOfferDetailsList?.firstOrNull() ?: oneTimePurchaseOfferDetails
            if (offer == null) {
                emptyList()
            } else {
                listOf(
                    DataWrappers.ProductDetails(
                        title = title,
                        description = description,
                        priceCurrencyCode = offer.priceCurrencyCode,
                        price = offer.formattedPrice,
                        priceAmount = offer.priceAmountMicros / 1_000_000.0,
                        billingCycleCount = null,
                        billingPeriod = null,
                        recurrenceMode = ProductDetails.RecurrenceMode.NON_RECURRING
                    )
                )
            }
        }
        else -> emptyList()
    }

    private fun String.isProductReady(): Boolean = productDetails.containsKey(this) && productDetails[this] != null

    override fun onAcknowledgePurchaseResponse(billingResult: BillingResult) {
        if (!billingResult.isOk()) {
            updateFailedPurchase(billingResponseCode = billingResult.responseCode)
        }
    }

    override fun close() {
        if (::mBillingClient.isInitialized) {
            mBillingClient.endConnection()
        }
        super.close()
    }

    private fun BillingResult.isOk(): Boolean = responseCode == BillingClient.BillingResponseCode.OK

    private fun log(message: String) {
        if (enableDebug) Log.d(TAG, message)
    }

    companion object {
        const val TAG = "GoogleBillingService"
    }
}
