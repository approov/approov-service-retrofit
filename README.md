# Approov Package for Retrofit

![Java](https://img.shields.io/badge/Java-8%2B-007396?logo=openjdk&logoColor=white)
![Android](https://img.shields.io/badge/Android-minSdk%2023-3DDC84?logo=android&logoColor=white)
![Maven Central](https://img.shields.io/maven-central/v/io.approov/service.retrofit?logo=apachemaven&logoColor=white&label=Maven%20Central)
![Message Signing](https://img.shields.io/badge/Message%20Signing-RFC%209421-1f6feb)
![Build](https://github.com/approov/approov-service-retrofit/actions/workflows/build_and_test.yml/badge.svg)

This package adds [Approov](https://www.approov.io) to an Android app that uses Retrofit. After you initialize it, the package adds an Approov token to each request that your app sends to your API domains through its `Retrofit` instance. It does the same for requests through its `OkHttpClient`, if you use OkHttp directly. The token, a signed JWT, provides proof of attestation. Your backend checks it to decide whether to accept or reject the request. A valid token shows that the request came from a genuine, unmodified copy of your app on a device that passed Approov's checks.

The package also checks the TLS connection to each of your API domains. A request goes ahead only if both the operating system and the [Managed Trust Roots](https://docs.approov.io/security-configuration/dynamic-pinning/#managed-trust-roots) configuration in your Approov account trust the connection. [Connection validation](ADVANCED.md#connection-validation) has the details.

## BEFORE YOU START

You need:

* an Approov account, trial or paid, and its [account ID](#your-account-id)
* your API domains [added to Approov](https://docs.approov.io/getting-started/add-api-domains/). The package adds a token only to requests to these domains.

Then follow the steps below. Each step makes the next one possible:

1. [Add the dependency](#adding-the-dependency). This gives your app `ApproovService`.
2. [Initialize](#initializing) `ApproovService` with your account ID. This turns on Approov protection.
3. [Make your requests](#making-requests) through the `Retrofit` instance from `ApproovService`. Your requests to your API domains then carry a token.
4. [Look in the debug log](ADVANCED.md#diagnostics) to see that each request gets a token.
5. [Register your app and check tokens on your backend](#next-steps). Until your backend checks the token, your API is not protected.

The [Approov documentation for this package](https://docs.approov.io/app-integration/packages/android-retrofit/) has more background. Optional features are in [ADVANCED.md](ADVANCED.md). Every method is in [REFERENCE.md](REFERENCE.md).

## ADDING THE DEPENDENCY

Make sure `mavenCentral()` is in your repositories. Then add the package to your app's Gradle dependencies:

```groovy
implementation("io.approov:service.retrofit:3.7.0")
implementation("com.squareup.retrofit2:retrofit:2.11.0")
implementation("com.squareup.okhttp3:okhttp:5.3.2")
```

Your app uses Retrofit and OkHttp types, such as `Retrofit.Builder` and `OkHttpClient`, so declare both in your app too. The package uses Retrofit 2.11.0. Gradle uses the highest version of each library that anything in your app asks for.

The package supports Android 6.0 (API level 23) and later. It works with OkHttp 5.3.2 or later, and also if your app stays on OkHttp 4.12 or later. OkHttp 3.x is not supported. If your app compiles Kotlin, it needs Kotlin 2.1 or later. See [OkHttp version](ADVANCED.md#okhttp-version).

Add these permissions to your app manifest:

```xml
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.INTERNET" />
```

If the manifest merge reports a conflict on `application@label`, add `tools:replace="android:label"` to your `<application>` element. The `tools` prefix needs `xmlns:tools="http://schemas.android.com/tools"` on the `<manifest>` element.

Next, [initialize the package](#initializing).

## INITIALIZING

Initialize `ApproovService` once, when your app starts, with your Approov account ID. Do this in the `onCreate` method of your `Application` class. If your app sends a request through a `Retrofit` instance or client from `ApproovService` before `initialize`, the request goes out without Approov protection.

```kotlin
import android.util.Log
import io.approov.service.retrofit.ApproovService

class YourApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            // your Approov account ID, from your onboarding email or "approov sdk -getConfigString"
            ApproovService.initialize(applicationContext, "<your-approov-account-id>")
            if (ApproovService.isApproovServiceEnabled() && ApproovService.isApproovProtectionEnabled())
                Log.i("YourApp", "Approov initialized; deviceID=${ApproovService.getDeviceID()}")
        } catch (e: Exception) {
            // the value passed was not a valid account ID; run without Approov rather than not at all
            Log.e("YourApp", "Approov account ID rejected; starting in bypass mode", e)
            ApproovService.initialize(applicationContext, "")
        }
        // then configure, after initialize (which resets the configuration) and before the
        // app issues protected requests; only the settings your app uses, for example:
        // ApproovService.setTokenHeader("Authorization", "Bearer ")
        // ApproovService.addSubstitutionHeader("Api-Key", null)
    }
}
```

<details>
<summary>Java</summary>

```java
import android.util.Log;
import io.approov.service.retrofit.ApproovService;

public class YourApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        try {
            // your Approov account ID, from your onboarding email or "approov sdk -getConfigString"
            ApproovService.initialize(getApplicationContext(), "<your-approov-account-id>");
            if (ApproovService.isApproovServiceEnabled() && ApproovService.isApproovProtectionEnabled())
                Log.i("YourApp", "Approov initialized; deviceID=" + ApproovService.getDeviceID());
        } catch (Exception e) {
            // the value passed was not a valid account ID; run without Approov rather than not at all
            Log.e("YourApp", "Approov account ID rejected; starting in bypass mode", e);
            ApproovService.initialize(getApplicationContext(), "");
        }
        // then configure, after initialize (which resets the configuration) and before the
        // app issues protected requests; only the settings your app uses, for example:
        // ApproovService.setTokenHeader("Authorization", "Bearer ");
        // ApproovService.addSubstitutionHeader("Api-Key", null);
    }
}
```
</details>

About this example:

* `initialize` throws if the account ID is incomplete or changed. The `catch` block then starts the package in **bypass mode**, by initializing with `""`. In bypass mode the package sends requests without Approov protection. You can also use bypass mode as a feature flag: initialize with `""` to start your app without Approov.
* Logging the installation device ID is optional. Approov identifies an installation only by its device ID. Log it next to your own user or session ID for correlation.
* Make your configuration calls after `initialize`, before the app sends protected requests. Each `initialize` resets the configuration to its defaults, so the package loses a setting made before it. A setting applies only to requests sent after you make it. See [initialize, then configure](ADVANCED.md#initialize-then-configure).

### Your account ID

Your account ID is in your onboarding email. You can also get it at any time from the Approov CLI, which calls it the SDK config string:

```sh
approov sdk -getConfigString
```

The account ID is the same for every app in your account, and it is not a secret. You can keep it in your source code or deliver it with your app's configuration. The whole value must reach the app unchanged, including its punctuation.

If the account ID is not available when the app starts, initialize with `""` first. Call `initialize` again when you have the account ID. See [bypass initialization](ADVANCED.md#bypass-initialization).

When the package is initialized, [make your requests](#making-requests) through its `Retrofit` instance.

## MAKING REQUESTS

Give `ApproovService` the `Retrofit.Builder` that you would normally use, and use the `Retrofit` instance that it returns for the API calls you want to protect:

```kotlin
val retrofitBuilder = Retrofit.Builder()
    .baseUrl("https://your.domain/")
    .addConverterFactory(GsonConverterFactory.create())
val retrofit = ApproovService.getRetrofit(retrofitBuilder)
```

<details>
<summary>Java</summary>

```java
Retrofit.Builder retrofitBuilder = new Retrofit.Builder()
        .baseUrl("https://your.domain/")
        .addConverterFactory(GsonConverterFactory.create());
Retrofit retrofit = ApproovService.getRetrofit(retrofitBuilder);
```
</details>

`getRetrofit` sets the Approov `OkHttpClient` on the builder and builds the `Retrofit` instance. It keeps the instance for the builder you pass. If you call it again with the same builder, you get the same `Retrofit` instance, so keep one builder for each base URL. Do not change a builder after you pass it. Make a new builder instead. `initialize` and `setOkHttpClientBuilder` discard the kept instances, so call `getRetrofit` again after them.

If you use OkHttp directly, get the client with `ApproovService.getOkHttpClient()`. It is the client that `getRetrofit` uses.

If your app builds its own OkHttp client, for example to set timeouts or add interceptors, give its builder to `ApproovService`. Do this after `initialize` and before you call `getRetrofit`. `initialize` resets the builders, so set yours again after each `initialize`. Approov's network interceptors run last, after yours, just before each request is sent.

```kotlin
ApproovService.setOkHttpClientBuilder(
    OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
)
val retrofit = ApproovService.getRetrofit(retrofitBuilder)
```

## CUSTOMIZING THE BEHAVIOUR

[ADVANCED.md](ADVANCED.md) explains how the package behaves and how to customize it:

* [What each request carries](ADVANCED.md#what-each-request-carries), and [what happens when a token fetch fails](ADVANCED.md#what-happens-by-default). By default, a request fails if the device has a network problem. It goes ahead with an empty token header if the app cannot reach the Approov service.
* [Service mutators](ADVANCED.md#service-mutators), which change those decisions. For example, a mutator can send requests without a valid token. It can also fail a request on chosen errors. Your app then receives an `ApproovException` and decides what to do, for example retry or show a message.
* [Message signing](ADVANCED.md#message-signing), which you install with one call, for example `ApproovDefaultMessageSigning.combined(ApproovServiceMutator.ALWAYS_PROCEED)`
* [Header names](ADVANCED.md#header-names), [secure strings](ADVANCED.md#secure-strings), [token binding](ADVANCED.md#token-binding) and [excluded URLs](ADVANCED.md#excluding-urls)
* [Diagnostics](ADVANCED.md#diagnostics), which show the result of each token fetch in the log

Then continue with the [next steps](#next-steps).

## NEXT STEPS

1. [Register your app's signing certificate](https://docs.approov.io/getting-started/add-app-signing-certificates/), so that your release builds pass attestation.
2. [Check tokens on your backend](https://docs.approov.io/getting-started/integrate-backend/), and signatures if you installed message signing. This is the step that protects your API.
3. [Go live](https://docs.approov.io/getting-started/go-live/). Monitor first, then let your backend reject requests without a valid token.

## UPGRADING FROM 3.5.x

Read the [changelog](CHANGELOG.md) for the full list of changes. Test your app and backend together.

### Code changes

* The message signing helper classes moved. Change imports of `io.approov.util.sig.*` to `io.approov.util.retrofit.sig.*`, and `io.approov.util.http.sfv.*` to `io.approov.util.retrofit.http.sfv.*`.
* A `SignatureParametersFactory` subclass that sets `useAccountMessageSigning = true` directly now produces both the install and the account signature. To keep the account signature only, call `setUseAccountMessageSigning()` instead.
* The package now brings in OkHttp 5.3.2 instead of 4.12.0. It still uses Retrofit 2.11.0. Keep your other OkHttp libraries on the same version. If your app compiles Kotlin, use Kotlin 2.1 or later. You can also stay on OkHttp 4.12 or later. See [OkHttp version](ADVANCED.md#okhttp-version).
* A `Retrofit` instance that you get before `initialize` now sends its requests without Approov protection until `initialize` runs, and protects them from then on. In 3.5.x it stayed unprotected. See [bypass initialization](ADVANCED.md#bypass-initialization).
* `setProceedOnNetworkFail`, `getProceedOnNetworkFail` and `prefetch` are removed. To go ahead on network failures, install `ApproovServiceMutator.ALWAYS_PROCEED` or turn on `setUseApproovStatusIfNoToken(true)`. The SDK does the prefetch itself.
* `setApproovInterceptorExtensions`, `getApproovInterceptorExtensions` and the `ApproovInterceptorExtensions` interface are removed. Use `setServiceMutator` and `getServiceMutator`.


## INCLUDED 3RD PARTY SOURCE

To support message signing, this repo has adapted code released by two 3rd party developers. The LICENSE files have been copied from the repos into the associated directories listed below:

* `approov-service/src/main/java/io/approov/util/retrofit/http/sfv`
    * Repo: https://github.com/reschke/structured-fields
    * Commit hash: d43f2ad6c655b92a7ef52aafa763418e1c6fed78
    * License: Apache V2
* `approov-service/src/main/java/io/approov/util/retrofit/sig`
    * Repo: https://github.com/bspk/httpsig-java
    * Commit hash: ffe86ae1d07425f13b018329f51c7a7c0833d71f
    * License: MIT
