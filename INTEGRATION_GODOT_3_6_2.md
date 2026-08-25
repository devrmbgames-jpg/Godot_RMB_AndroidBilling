# Godot 3.6.2 + Google Play Billing 8.3.0 integration

This plugin preserves the existing Godot-facing API (`build`, `purchase`, `subscribe`, `unsubscribe` and existing signals) while using the latest Google Play Billing Library release in the 8.x line: 8.3.0.

## 1. Plugin build toolchain

The plugin project keeps the modern Android build toolchain already used by the project:

```text
Google Play Billing:   8.3.0
Kotlin:               2.3.21
Android Gradle Plugin: 8.13.2
Gradle:               8.13
compileSdk:           36
minSdk:               23
targetSdk:            36
JDK / bytecode:       17
Build Tools:          35.0.0
```

Billing 8.1+ requires API 23+, so this plugin keeps `minSdk 23`.

## 2. Godot Android library selection

For local development and production builds, compile the plugin against the exact Android AAR/JAR produced by the modified Godot engine whenever possible.

Put the release engine library into:

```text
app/libs/release/
```

For example:

```text
app/libs/release/godot-lib.custom.release.aar
```

For debug builds, put the corresponding artifact into:

```text
app/libs/debug/
```

The Gradle build uses this selection order independently for debug and release:

1. If a local `godot-lib*.aar` or `godot-lib*.jar` exists in the variant folder, use it as `compileOnly`.
2. Otherwise fall back to the official Maven Central artifact:

```text
org.godotengine:godot:3.6.2.stable
```

This fallback exists primarily so clean clones and GitHub Actions can compile and validate the plugin without storing the private/custom Godot engine binary in the repository.

Both the custom engine library and the Maven fallback are `compileOnly`, so neither is embedded into `GodotGoogleBilling.*.aar`.

Before shipping, the plugin should still be compiled and tested against the actual modified Godot AAR if that engine changes Java/Kotlin-facing Android plugin APIs.

Build with JDK 17:

```bash
./gradlew --version
./gradlew clean :app:assembleDebug
./gradlew clean :app:assembleRelease
```

Expected release output:

```text
app/build/outputs/aar/GodotGoogleBilling.release.aar
```

## 3. Install into the Godot project

Copy the rebuilt plugin into:

```text
res://android/plugins/GodotGoogleBilling.release.aar
res://android/plugins/GodotGoogleBilling.gdap
```

The descriptor declares Billing 8.3.0 as a remote dependency. If the Godot Android project declares Billing directly in `res://android/build/build.gradle`, keep exactly one Billing version:

```gradle
dependencies {
    implementation 'com.android.billingclient:billing-ktx:8.3.0'
}
```

Do not resolve Billing 9.x alongside this plugin.

## 4. Required `res://android/build` baseline

The consuming Godot Android project can keep the current modern toolchain:

```text
Android Gradle Plugin: 8.13.2
Gradle:                8.13
Kotlin:                2.3.21
compileSdk:            36
targetSdk:             36
minSdk:                23
Java:                  17
```

Repositories must include:

```gradle
repositories {
    google()
    mavenCentral()
}
```

Java must remain aligned with the plugin:

```gradle
compileOptions {
    sourceCompatibility JavaVersion.VERSION_17
    targetCompatibility JavaVersion.VERSION_17
}
```

## 5. Godot API compatibility

Existing Godot calls stay unchanged:

```gdscript
var billing = Engine.get_singleton("GodotGoogleBilling")

billing.build(non_consumables, consumables, subscriptions, license_key)
billing.purchase("product.id")
billing.subscribe("subscription.id")
billing.unsubscribe("subscription.id")
```

Existing signals remain:

```text
prices_in_app_update
product_purchased
product_restored
product_failed
country_code_update
```

## 6. Billing 8 behavior handled internally

The Android implementation:

- uses `PendingPurchasesParams`;
- uses the Billing 8 `QueryProductDetailsResult` response and logs unfetched products;
- refreshes `ProductDetails` before purchase flow launch;
- supplies subscription / one-time offer tokens when needed;
- does not grant entitlement for a `PENDING` purchase;
- restores active purchases through `queryPurchasesAsync`;
- consumes consumables and acknowledges non-consumables/subscriptions;
- preserves the existing dictionaries and signals exposed to Godot.

The experimental callback/restore changes added after the original migration are intentionally not part of this Billing 8 branch.

## 7. CI and validation

GitHub Actions runs a full release build. In CI there normally is no local custom engine AAR, so Gradle automatically compiles against `org.godotengine:godot:3.6.2.stable` from Maven Central and uploads the produced plugin AAR.

After the plugin compiles, validate at least:

- `:app:assembleDebug` and `:app:assembleRelease`;
- the dependency tree contains only Billing 8.3.0;
- local production build uses the actual custom Godot AAR;
- non-consumable purchase;
- consumable purchase and re-purchase after consume;
- subscription purchase;
- restore after restart;
- already-owned and cancelled flows;
- pending purchase transitioning to purchased;
- subscription base plans/offers;
- final release export from the modified Godot 3.6.2 custom build.
