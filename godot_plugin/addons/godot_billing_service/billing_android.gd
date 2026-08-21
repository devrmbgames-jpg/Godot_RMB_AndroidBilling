extends "res://addons/godot_billing_service/billing_private.gd"

# Values exposed by GodotGoogleBilling.kt. Keep these separate from ProductType values in product.gd.
const GOOGLE_TYPE_IN_APP := 0
const GOOGLE_TYPE_SUBS := 3

const GODOT_GOOGLE_BILLING := "GodotGoogleBilling"

var _google_billing = null
var _inited := false
var _license_key: String = ""

func _to_string() -> String:
	return "[GodotBillingPrivateAndroid]"

func _init(param: Dictionary = {}).(param) -> void :
	print(self, " GodotBillingPrivateAndroid init from param - ", param)
	store = Store.GooglePlayMarket
	vendor = Vendor.Google
	is_auto_update_product = false
	if param :
		_license_key = param.get("google_license_key", "")
	if Engine.has_singleton(GODOT_GOOGLE_BILLING) :
		_google_billing = Engine.get_singleton(GODOT_GOOGLE_BILLING)
		_google_billing.connect("prices_in_app_update", self, "_on_prices_in_app_update", [], CONNECT_DEFERRED)
		_google_billing.connect("product_purchased", self, "_on_product_purchased", [], CONNECT_DEFERRED)
		_google_billing.connect("product_restored", self, "_on_product_restored", [], CONNECT_DEFERRED)
		_google_billing.connect("product_failed", self, "_on_product_failed", [], CONNECT_DEFERRED)

	if not _google_billing :
		push_error("%s google is null!" % str(self))
		return

###############################################################################
##### CALLBACKS ###############################################################
###############################################################################

func _get_or_create_product(sku: String):
	var product = get_products().get_product_from_sku(sku)
	if not product :
		product = create_product(sku)
	return product

func _apply_transaction_payload(transaction, transaction_dict: Dictionary) -> void:
	transaction.transaction_id = str(transaction_dict.get("order_id", ""))
	var json_str := str(transaction_dict.get("json", ""))
	if json_str.length() > 2:
		var parsed := JSON.parse(json_str)
		if parsed.error == OK and parsed.result is Dictionary:
			var json_dict := parsed.result as Dictionary
			transaction.optional["json"] = json_dict
			transaction.purchased_date = int(json_dict.get("purchaseTime", 0))

	transaction.optional["is_acknowledged"] = bool(transaction_dict.get("is_acknowledged", false))
	transaction.optional["purchase_state"] = int(transaction_dict.get("purchase_state", 0))
	transaction.optional["purchase_token"] = str(transaction_dict.get("purchase_token", ""))
	transaction.optional["signature"] = str(transaction_dict.get("signature", ""))
	transaction.optional["package_name"] = str(transaction_dict.get("package_name", ""))
	transaction.optional["response_code"] = int(transaction_dict.get("response_code", -1))

func _on_prices_in_app_update(data) -> void :
	if not data:
		push_error("%s price update data is null!" % str(self))
		return

	print(self, " data prices update: ", data)
	var data_dict := data as Dictionary
	if not data_dict:
		push_error("%s price update data is not a Dictionary" % str(self))
		return

	var sku := str(data_dict.get("sku", ""))
	if sku.empty():
		push_error("%s price update has empty sku" % str(self))
		return

	var product = _get_or_create_product(sku)
	var type_product := int(data_dict.get("type_product", -1))
	if type_product == GOOGLE_TYPE_IN_APP :
		product.product_type = ProductType.InApp
	elif type_product == GOOGLE_TYPE_SUBS :
		product.product_type = ProductType.Subs

	product.display_name = str(data_dict.get("title", ""))
	# Native bridge historically exposed `details`; documentation/helper code used `description`.
	# Support both without changing either public contract.
	product.description = str(data_dict.get("description", data_dict.get("details", "")))
	product.display_price = str(data_dict.get("price", ""))
	product.price = float(data_dict.get("price_amount", 0.0))
	product.currency_code = str(data_dict.get("currency_code", ""))
	product.optional["billing_cycle_count"] = int(data_dict.get("billing_cycle_count", 0))
	product.optional["billing_period"] = str(data_dict.get("billing_period", ""))
	product.optional["recurrence_mode"] = int(data_dict.get("recurrence_mode", 0))
	product.is_valid = true

	emit_signal("update_product", product)
	emit_signal("update_products")

func _on_product_purchased(data) -> void :
	if not data:
		push_error("%s purchased data is null!" % str(self))
		return

	print(self, " data purchased: ", data)
	var transaction_dict := data as Dictionary
	if not transaction_dict:
		return

	var sku := str(transaction_dict.get("sku", ""))
	var product = _get_or_create_product(sku)
	var transaction := GodotBillingTransaction.new()
	transaction.product = product
	transaction.status = StatusCode.Purchased
	_apply_transaction_payload(transaction, transaction_dict)

	transactions.add_transaction(transaction)
	emit_signal("update_transaction", transaction)
	emit_signal("update_transactions")
	emit_signal("purchase_success", transaction)

func _on_product_restored(data) -> void :
	if not data:
		push_error("%s restored data is null!" % str(self))
		return

	print(self, " data restored: ", data)
	var transaction_dict := data as Dictionary
	if not transaction_dict:
		return

	var sku := str(transaction_dict.get("sku", ""))
	# Restore can legitimately arrive for an owned item whose ProductDetails is currently unfetched.
	# Never attach a null product to the restored transaction.
	var product = _get_or_create_product(sku)
	var transaction := GodotBillingTransaction.new()
	transaction.product = product
	transaction.status = StatusCode.Purchased
	_apply_transaction_payload(transaction, transaction_dict)

	transactions.add_transaction(transaction)
	emit_signal("update_transaction", transaction)
	emit_signal("update_transactions")

func _on_product_failed(data) -> void :
	if not data:
		push_error("%s failed purchase data is null!" % str(self))
		return

	push_warning("%s data failed: %s" % [str(self), str(data)])
	var transaction_dict := data as Dictionary
	if not transaction_dict:
		return

	var sku := str(transaction_dict.get("sku", ""))
	var product = _get_or_create_product(sku)
	var transaction := GodotBillingTransaction.new()
	transaction.product = product
	transaction.status = StatusCode.Error
	_apply_transaction_payload(transaction, transaction_dict)

	transactions.add_transaction(transaction)
	emit_signal("update_transaction", transaction)
	emit_signal("update_transactions")
	emit_signal("purchase_failed", transaction)

###############################################################################
#### OVERRIDE #################################################################
###############################################################################

func _request_products() -> void :
	print(self, " request products!")
	if not _google_billing :
		push_error("%s google is null!" % str(self))
		return

	if _inited :
		push_warning("this billing is inited!")
		return
	_inited = true

	var consumables := PoolStringArray()
	var non_consumables := PoolStringArray()
	var subs := PoolStringArray()
	for obj in products.get_products() :
		var product := obj as __Product
		assert(product, "obj is not Product type")
		if product.product_type == ProductType.Subs :
			subs.append(product.sku)
		else :
			if product.is_consumable :
				consumables.append(product.sku)
			else :
				non_consumables.append(product.sku)

	print(self, " build from non_consumables", non_consumables)
	print(self, " build from consumables", consumables)
	print(self, " build from subs", subs)
	_google_billing.build(non_consumables, consumables, subs, _license_key)

func _request_products_from_list(list: Array) -> void :
	print(self, " request products from list!")
	if list.empty() :
		push_warning("%s Request product failed. Poduct list is empty" % str(self))
		return

	if not _google_billing :
		push_error("%s google is null" % str(self))
		return

	if _inited :
		push_warning("this billing is inited!")
		return
	_inited = true

	var consumables := []
	var non_consumables := []
	var subs := []
	for obj in list :
		var product := obj as __Product
		assert(product, "obj is not Product type")
		if product.product_type == ProductType.Subs :
			subs.append(product.sku)
		else :
			if product.is_consumable :
				consumables.append(product.sku)
			else :
				non_consumables.append(product.sku)

	print(self, " build from non_consumables", non_consumables)
	print(self, " build from consumables", consumables)
	print(self, " build from subs", subs)
	_google_billing.build(non_consumables, consumables, subs, _license_key)

func _request_product(product: __Product) -> void :
	print(self, " request product from product!", product)
	if not _google_billing :
		push_error("%s google is null!" % str(self))
		return
	_request_products_from_list([product])

func _request_purchase(product: __Product, _quantity: int) -> void :
	print(self, " request purchase from product!")
	if not _google_billing :
		push_error("%s google is null!" % str(self))
		return

	if product.product_type == ProductType.InApp :
		_google_billing.purchase(product.sku)
	else :
		_google_billing.subscribe(product.sku)

func _restore_purchase() -> void :
	print(self, " restore purchase from product!")
	if not _google_billing :
		push_error("%s google is null!" % str(self))
		return
	push_warning("Google restore is performed automatically during build()")

func _consume_transaction(_transaction: __ProductTransaction) -> void :
	if not _google_billing :
		push_error("%s google is null!" % str(self))
		return
	assert(false, "is not impl")
