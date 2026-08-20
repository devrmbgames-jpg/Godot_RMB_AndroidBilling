# Godot 3.6.2 + Google Play Billing 9.1.0 integration

This plugin keeps the existing Godot-facing API (`build`, `purchase`, `subscribe`, `unsubscribe` and existing signals) while migrating the Android implementation to Google Play Billing Library 9.1.0.

## 1. Build the plugin AAR

The plugin project uses Android Gradle Plugin 8.2 and JDK 17. The produced plugin bytecode also targets Java 17, matching the Godot Android build configuration used by this project.

Because the game uses a modified Godot engine, the plugin must be compiled against the exact Android AAR/JAR produced by that custom engine build. Do not use the public Maven Godot artifact for this plugin.

Put the custom Godot release Android library into:

```text
app/libs/release/
```

For example:

```text
app/libs/release/godot-lib.custom.release.aar
```

For a debug plugin build, put the corresponding custom debug AAR/JAR into:

```text
app/libs/debug/
```

`app/build.gradle` loads these files as compile-only dependencies:

```gradle
releaseCompileOnly fileTree(dir: 'libs/release', include: ['*.jar', '*.aar'])
debugCompileOnly fileTree(dir: 'libs/debug', include: ['*.jar', '*.aar'])
```

Godot itself is therefore available while compiling the plugin, but is not packaged into `GodotGoogleBilling.release.aar`.

Build the release AAR:

```bash
./gradlew :app:assembleRelease
```

Output:

```text
app/build/outputs/aar/GodotGoogleBilling.release.aar
```

## 2. Install into the Godot 3.6.2 project

Godot 3.6.2 uses the v1 Android plugin format. Copy these files into your game project:

```text
res://android/plugins/GodotGoogleBilling.release.aar
res://android/plugins/GodotGoogleBilling.gdap
```

The included `GodotGoogleBilling.gdap` declares:

```text
com.android.billingclient:billing-ktx:9.1.0
```

as a remote dependency, so Godot's Gradle custom build can resolve the Billing library instead of trying to bundle it inside the plugin AAR.

In Godot, open the Android export preset and enable `GodotGoogleBilling` in the **Plugins** section.

## 3. Required `android/build/*` checks

Use the Android **Custom Build** generated for the modified Godot 3.6.2 engine.

### `android/build/build.gradle`

The generated build must have Google's Maven repository available. Verify that the project repositories include `google()` and `mavenCentral()`:

```gradle
allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
```

When the `.gdap` plugin descriptor is used, do **not** manually add a second Billing dependency. Godot injects the `.gdap` remote dependency into the generated build.

If your project does not use the `.gdap` dependency mechanism and you integrate the AAR manually, add exactly one Billing dependency to the app dependencies:

```gradle
dependencies {
    implementation 'com.android.billingclient:billing-ktx:9.1.0'
}
```

Do not keep older `billing`, `billing-ktx`, or legacy Play Billing artifacts at the same time.

### Java compatibility in `android/build`

The plugin is compiled for Java 17. The Godot Android build must therefore use Java 17 as well. Keep the project configuration aligned with:

```gradle
compileOptions {
    sourceCompatibility JavaVersion.VERSION_17
    targetCompatibility JavaVersion.VERSION_17
}
```

If Kotlin compilation is configured in the Godot Android project, keep it aligned too:

```gradle
kotlinOptions {
    jvmTarget = JavaVersion.VERSION_17
}
```

The JDK used to run Gradle should be JDK 17.

### `android/build/gradle.properties`

No Billing-specific property is required. Keep AndroidX enabled if your generated template uses it:

```properties
android.useAndroidX=true
```

### `android/build/settings.gradle`

No Billing-specific change is required. If your customized template replaces repository management, make sure Google's Maven repository is not removed.

### Gradle / Android Gradle Plugin

You do not need to upgrade the Godot game's own Android Gradle Plugin merely because Billing 9.1.0 is used. The important integration requirements are that the modified Godot custom build can resolve the Billing 9.1.0 dependency and that its Java toolchain is Java 17.

## 4. Godot API compatibility

Existing Godot code can remain unchanged:

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

No new required arguments were added to the Godot API.

## 5. Billing 9 behavior changes handled internally

The Android implementation now:

- uses `PendingPurchasesParams` instead of the removed parameterless `enablePendingPurchases()` API;
- reads the Billing 8/9 `QueryProductDetailsResult` object and records unfetched products;
- queries fresh `ProductDetails` before opening a purchase flow to avoid stale offer tokens;
- passes offer tokens for subscriptions and one-time-product offers when required;
- does not grant an entitlement while a purchase is `PENDING`;
- restores only active purchases returned by `queryPurchasesAsync`;
- acknowledges non-consumables/subscriptions and consumes consumables;
- preserves the existing dictionaries/signals exposed to Godot.

## 6. Play Console / test requirements

For real purchase tests:

1. The package name must match the Play Console application.
2. Product IDs used in Godot must exactly match Play Console product IDs.
3. The build must be signed with the expected key and uploaded to a Play track (internal testing is sufficient).
4. Add tester accounts / license testers as appropriate.
5. Test pending transactions as well as completed and cancelled purchases.
6. Test subscription base plans/offers. The current public Godot API does not expose offer selection, so the plugin uses the first eligible offer returned by Google Play, matching the plugin's previous first-offer behavior.

## 7. Recommended validation matrix

- plugin compilation against the exact custom Godot release AAR;
- non-consumable purchase;
- consumable purchase and re-purchase after consume;
- subscription purchase;
- restore after app restart;
- already-owned item;
- user-cancelled flow;
- pending purchase transitioning to purchased;
- unavailable / unfetched product;
- country code query;
- release export from the modified Godot 3.6.2 custom build.
