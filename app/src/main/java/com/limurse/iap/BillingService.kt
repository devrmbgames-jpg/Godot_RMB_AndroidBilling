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

    /**
     * Query Google Play Billing for active purchases.
     * New purchases are delivered through PurchasesUpdatedListener.
     */
    private suspend fun queryPurchases() {
        val inAppResult: PurchasesResult = mBillingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )
        if (inAppResult.billingResult.isOk()) {
            processPurchases(inAppResult.purchasesList, isRestore = true)
        } else {
            log("queryPurchases INAPP failed: ${inAppResult.billingResult.debugMessage}")
        }

        val subsResult: PurchasesResult = mBillingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )
        if (subsResult.billingResult.isOk()) {
            processPurchases(subsResult.purchasesList, isRestore = true)
        } else {
            log("queryPurchases SUBS failed: ${subsResult.billingResult.debugMessage}")
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
        // Query immediately before launching. Google recommends against relying on stale ProductDetails.
        sku.toProductDetails(type, forceRefresh = true) { details ->
            if (details == null) {
                log("launchBillingFlow. Product details not available for sku: $sku")
                return@toProductDetails
            }

            val productDetailsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)

            val offerToken = when (type) {
                BillingClient.ProductType.SUBS -> details.subscriptionOfferDetails
                    ?.firstOrNull()
                    ?.offerToken
                BillingClient.ProductType.INAPP -> details.oneTimePurchaseOfferDetailsList
                    ?.firstOrNull()
                    ?.offerToken
                else -> null
            }

            if (!offerToken.isNullOrEmpty()) {
                productDetailsBuilder.setOfferToken(offerToken)
            }

            val billingFlowParamsBuilder = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(productDetailsBuilder.build()))

            if (obfuscatedAccountId != null) {
                billingFlowParamsBuilder.setObfuscatedAccountId(obfuscatedAccountId)
            }
            if (obfuscatedProfileId != null) {
                billingFlowParamsBuilder.setObfuscatedProfileId(obfuscatedProfileId)
            }

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
                data = Uri.parse(
                    "https://play.google.com/store/account/subscriptions" +
                        "?package=${activity.packageName}&sku=$sku"
                )
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

    /** Called by the Billing Library when new purchases are detected. */
    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        val responseCode = billingResult.responseCode
        log("onPurchasesUpdated: responseCode:$responseCode debugMessage:${billingResult.debugMessage}")

        if (!billingResult.isOk()) {
            updateFailedPurchases(purchases?.map { getPurchaseInfo(it) }, responseCode)
        }

        when (responseCode) {
            BillingClient.BillingResponseCode.OK -> processPurchases(purchases)
            BillingClient.BillingResponseCode.USER_CANCELED ->
                log("onPurchasesUpdated: user canceled the purchase")
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                log("onPurchasesUpdated: item already owned; refreshing active purchases")
                CoroutineScope(Dispatchers.IO).launch { queryPurchases() }
            }
            BillingClient.BillingResponseCode.DEVELOPER_ERROR ->
                Log.e(
                    TAG,
                    "Google Play Billing developer error. Verify Play Console product IDs, package name, " +
                        "release signing, and that the installed build is eligible for Billing."
                )
        }
    }

    private fun processPurchases(purchasesList: List<Purchase>?, isRestore: Boolean = false) {
        if (purchasesList.isNullOrEmpty()) {
            log("processPurchases: no purchases")
            return
        }

        log("processPurchases: ${purchasesList.size} purchase(s)")
        purchases@ for (purchase in purchasesList) {
            val sku = purchase.products.firstOrNull()
            if (sku == null) {
                Log.e(TAG, "processPurchases failed: purchase has no products: $purchase")
                updateFailedPurchase(getPurchaseInfo(purchase))
                continue@purchases
            }

            // Never grant entitlement while payment is pending. Google Play will deliver/query the
            // purchase again after it transitions to PURCHASED.
            if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
                log("Purchase is pending; entitlement is deferred for sku: $sku")
                continue@purchases
            }

            if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED || !sku.isProductReady()) {
                Log.e(
                    TAG,
                    "processPurchases failed. sku:$sku state:${purchase.purchaseState} isProductReady:${sku.isProductReady()}"
                )
                updateFailedPurchase(getPurchaseInfo(purchase))
                continue@purchases
            }

            if (!isSignatureValid(purchase)) {
                log("processPurchases. Signature is not valid for: $purchase")
                updateFailedPurchase(getPurchaseInfo(purchase))
                continue@purchases
            }

            val details = productDetails[sku]
            val isProductConsumable = consumableKeys.contains(sku)

            when (details?.productType) {
                BillingClient.ProductType.INAPP -> {
                    if (isProductConsumable) {
                        mBillingClient.consumeAsync(
                            ConsumeParams.newBuilder()
                                .setPurchaseToken(purchase.purchaseToken)
                                .build()
                        ) { billingResult, _ ->
                            if (billingResult.isOk()) {
                                productOwned(getPurchaseInfo(purchase), false)
                            } else {
                                Log.d(TAG, "Consumption failed: ${billingResult.debugMessage}")
                                updateFailedPurchase(getPurchaseInfo(purchase), billingResult.responseCode)
                            }
                        }
                    } else {
                        productOwned(getPurchaseInfo(purchase), isRestore)
                    }
                }
                BillingClient.ProductType.SUBS -> subscriptionOwned(getPurchaseInfo(purchase), isRestore)
                else -> {
                    Log.e(TAG, "No ProductDetails type found for purchased sku: $sku")
                    updateFailedPurchase(getPurchaseInfo(purchase))
                    continue@purchases
                }
            }

            // consumeAsync implicitly acknowledges consumables. Everything else must be acknowledged.
            if (!purchase.isAcknowledged && !isProductConsumable) {
                val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                mBillingClient.acknowledgePurchase(acknowledgePurchaseParams, this)
            }
        }
    }

    private fun getPurchaseInfo(purchase: Purchase): DataWrappers.PurchaseInfo {
        return DataWrappers.PurchaseInfo(
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
    }

    private fun isSignatureValid(purchase: Purchase): Boolean {
        val key = decodedKey ?: return true
        return Security.verifyPurchase(key, purchase.originalJson, purchase.signature)
    }

    /** Query ProductDetails and update the plugin's existing price callbacks. */
    private fun List<String>.queryProductDetails(type: String, done: () -> Unit) {
        if (!::mBillingClient.isInitialized || !mBillingClient.isReady) {
            log("queryProductDetails. Google billing service is not ready yet.")
            done()
            return
        }

        if (isEmpty()) {
            log("queryProductDetails. SKU list is empty.")
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
            if (billingResult.isOk()) {
                isBillingClientConnected(true, billingResult.responseCode)

                queryResult.productDetailsList.forEach { details ->
                    productDetails[details.productId] = details
                }

                queryResult.unfetchedProductList.forEach { unfetched ->
                    productDetails[unfetched.productId] = null
                    log(
                        "Product not fetched: id=${unfetched.productId}, type=${unfetched.productType}, " +
                            "status=${unfetched.statusCode}"
                    )
                }

                val prices = queryResult.productDetailsList.associate { details ->
                    details.productId to details.toPriceDetails()
                }
                updatePrices(prices)
            } else {
                log("queryProductDetails failed: ${billingResult.responseCode} ${billingResult.debugMessage}")
            }
            done()
        }
    }

    /** Fetch ProductDetails by SKU. A fresh fetch is used for purchase flows to avoid stale offers. */
    private fun String.toProductDetails(
        type: String,
        forceRefresh: Boolean = false,
        done: (productDetails: ProductDetails?) -> Unit = {}
    ) {
        if (!::mBillingClient.isInitialized || !mBillingClient.isReady) {
            log("toProductDetails. Google billing service is not ready yet.")
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
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(product))
            .build()

        mBillingClient.queryProductDetailsAsync(params) { billingResult, queryResult ->
            if (billingResult.isOk()) {
                isBillingClientConnected(true, billingResult.responseCode)
                val details = queryResult.productDetailsList.find { it.productId == this }
                productDetails[this] = details
                queryResult.unfetchedProductList.forEach { unfetched ->
                    log("Product not fetched before purchase: ${unfetched.productId}, status=${unfetched.statusCode}")
                }
                done(details)
            } else {
                log("Failed to get details for sku: $this (${billingResult.responseCode})")
                done(null)
            }
        }
    }

    private fun ProductDetails.toPriceDetails(): List<DataWrappers.ProductDetails> {
        return when (productType) {
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
                }
                ?: emptyList()

            BillingClient.ProductType.INAPP -> {
                val offer = oneTimePurchaseOfferDetailsList?.firstOrNull()
                    ?: oneTimePurchaseOfferDetails
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
    }

    private fun String.isProductReady(): Boolean {
        return productDetails.containsKey(this) && productDetails[this] != null
    }

    override fun onAcknowledgePurchaseResponse(billingResult: BillingResult) {
        log("onAcknowledgePurchaseResponse: billingResult: $billingResult")
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

    private fun BillingResult.isOk(): Boolean {
        return responseCode == BillingClient.BillingResponseCode.OK
    }

    private fun log(message: String) {
        if (enableDebug) {
            Log.d(TAG, message)
        }
    }

    companion object {
        const val TAG = "GoogleBillingService"
    }
}
