# Godot 3.6.2 + Google Play Billing 9.1.0 integration

This plugin preserves the existing Godot-facing API (`build`, `purchase`, `subscribe`, `unsubscribe` and existing signals) while migrating the Android implementation to Google Play Billing Library 9.1.0.

## 1. Plugin build toolchain

Billing 9.1.0 KTX contains Kotlin 2.3 metadata. Do not build this plugin with the old Kotlin 1.9 / AGP 8.2 toolchain and do not suppress metadata validation with `-Xskip-metadata-version-check`.

The plugin project is aligned to:

```text
Google Play Billing: 9.1.0
Kotlin:              2.3.21
Android Gradle Plugin: 8.13.2
Gradle:              8.13
compileSdk:          36
minSdk:              23
targetSdk:           36
JDK / bytecode:      17
Build Tools:         35.0.0
```

The important compatibility points are:

- Billing 9.1.0 KTX must be compiled with a Kotlin compiler capable of reading Kotlin 2.3 metadata;
- Kotlin 2.3 requires an AGP/R8 generation that supports Kotlin 2.3; AGP 8.13.2 is used here;
- API 35+ AndroidX dependencies require `compileSdk >= 35`; the plugin uses API 36;
- Billing 9 requires `minSdk >= 23`;
- AGP 8.13 uses JDK 17 and Gradle 8.13.

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

The relevant Gradle logic is equivalent to:

```gradle
def publicGodotCoordinate = 'org.godotengine:godot:3.6.2.stable'
def releaseGodotLibraries = fileTree(dir: 'libs/release', include: ['godot-lib*.aar', 'godot-lib*.jar'])
def debugGodotLibraries = fileTree(dir: 'libs/debug', include: ['godot-lib*.aar', 'godot-lib*.jar'])

dependencies {
    if (!releaseGodotLibraries.files.isEmpty()) {
        releaseCompileOnly releaseGodotLibraries
    } else {
        releaseCompileOnly publicGodotCoordinate
    }

    if (!debugGodotLibraries.files.isEmpty()) {
        debugCompileOnly debugGodotLibraries
    } else {
        debugCompileOnly publicGodotCoordinate
    }
}
```

Both the custom engine library and the Maven fallback are `compileOnly`, so neither is embedded into `GodotGoogleBilling.*.aar`.

The Maven fallback only validates compatibility with the public Godot 3.6.2 Android plugin API. Before shipping, the plugin should still be compiled/tested against the actual modified Godot AAR if that engine changes Java/Kotlin-facing Android plugin APIs.

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

If Android Studio still reports Kotlin 1.9 after pulling these changes, stop old daemons and clear the project build cache before rebuilding:

```bash
./gradlew --stop
./gradlew clean
```

On Windows you can also remove the project's `.gradle/` and `app/build/` directories. Clearing the global Gradle cache is normally unnecessary.

## 3. Install into the Godot project

Copy the rebuilt plugin into:

```text
res://android/plugins/GodotGoogleBilling.release.aar
res://android/plugins/GodotGoogleBilling.gdap
```

The descriptor declares Billing 9.1.0 as a remote dependency. If your project instead declares Billing directly in `res://android/build/build.gradle`, keep exactly one Billing version:

```gradle
dependencies {
    implementation 'com.android.billingclient:billing-ktx:9.1.0'
}
```

Do not keep Billing 7/8 artifacts alongside Billing 9.1.0.

## 4. Required `res://android/build` baseline

The Godot custom-build project that consumes the AAR must also use a modern Android toolchain. The Kidduca project migration uses:

```text
Android Gradle Plugin: 8.13.2
Gradle:                8.13
Kotlin:                2.3.21
compileSdk:            36
targetSdk:             36
minSdk:                23
Java:                  17
```

At minimum, the consuming project must not resolve Billing 9.1.0 / Kotlin 2.3 libraries using an old Kotlin 1.9 compiler or compile against API 34.

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

and Kotlin should target JVM 17 as well.

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

## 6. Billing 9 behavior handled internally

The Android implementation:

- uses `PendingPurchasesParams`;
- handles `QueryProductDetailsResult` and unfetched products;
- refreshes `ProductDetails` before purchase flow launch;
- supplies subscription / one-time offer tokens when needed;
- does not grant entitlement for a `PENDING` purchase;
- restores active purchases through `queryPurchasesAsync`;
- consumes consumables and acknowledges non-consumables/subscriptions;
- preserves the dictionaries/signals exposed to Godot.

## 7. CI and validation

GitHub Actions runs a full release build. In CI there normally is no local custom engine AAR, so Gradle automatically compiles against `org.godotengine:godot:3.6.2.stable` from Maven Central and uploads the produced plugin AAR.

After the plugin compiles, validate at least:

- `:app:assembleDebug` and `:app:assembleRelease`;
- the dependency tree contains only Billing 9.1.0;
- local production build uses the actual custom Godot AAR;
- non-consumable purchase;
- consumable purchase and re-purchase after consume;
- subscription purchase;
- restore after restart;
- already-owned and cancelled flows;
- pending purchase transitioning to purchased;
- subscription base plans/offers;
- final release export from the modified Godot 3.6.2 custom build.
