# Approov Service for Retrofit

A wrapper for the [Approov SDK](https://github.com/approov/approov-android-sdk) to enable easy integration when using [`Retrofit`](https://square.github.io/retrofit/) for making the API calls that you wish to protect with Approov. In order to use this you will need a trial or paid [Approov](https://www.approov.io) account.

See [Java](https://github.com/approov/quickstart-android-java-retrofit) and [Kotlin](https://github.com/approov/quickstart-android-kotlin-retrofit) quickstarts for instructions on how to use this.

## Adding Approov Service Dependency
The Approov integration is available via [`maven`](https://mvnrepository.com/repos/central). This allows inclusion into the project by simply specifying a dependency in the `gradle` files for the app.
The `Maven` repository is already present in the gradle.build file so the only import you need to make is the actual service layer itself:

```
implementation("io.approov:service.retrofit:3.5.7")
```

Make sure you do a Gradle sync (by selecting `Sync Now` in the banner at the top of the modified `.gradle` file) after making these changes.

This package is actually an open source wrapper layer that allows you to easily use Approov with `Retrofit`. This has a further dependency to the closed source [Approov SDK](https://mvnrepository.com/artifact/io.approov/approov-android-sdk). In some cases you may need to also add this implementation to your dependencies list to avoid build errors:

```
implementation("io.approov:approov-android-sdk:3.5.3")
```

## Manifest Changes
The following app permissions need to be available in the manifest to use Approov:

```xml
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.INTERNET" />
```

Note that the minimum SDK version you can use with the Approov package is 23 (Android 6.0). 

Please [read this](https://approov.io/docs/latest/approov-usage-documentation/#targeting-android-11-and-above) section of the reference documentation if targeting Android 11 (API level 30) or above.

## Initializing Approov Service
In order to use the `ApproovService` you must initialize it when your app is created, usually in the `onCreate` method:

```kotlin
import io.approov.service.retrofit.ApproovService

class YourApp: Application() {
    override fun onCreate() {
        super.onCreate()
        ApproovService.initialize(applicationContext, "<enter-your-config-string-here>")
    }
}
```

The `<enter-your-config-string-here>` is a custom string that configures your Approov account access. This will have been provided in your Approov onboarding email.

## Using Approov Service
You can then modify your code that obtains a `RetrofitInstance` to make API calls as follows:

```kotlin
object ClientInstance {
    private const val BASE_URL = "https://your.domain"
    private var retrofitBuilder: Retrofit.Builder? = null
    val retrofitInstance: Retrofit
        get() {
            if (retrofitBuilder == null) {
                retrofitBuilder = Retrofit.Builder()
                        .baseUrl(BASE_URL)
                        .addConverterFactory(GsonConverterFactory.create())
            }
            return ApproovService.getRetrofit(retrofitBuilder!!)
        }
}
```

This obtains a retrofit instance includes an `OkHttp` interceptor that protects channel integrity (with either pinning or managed trust roots). The interceptor may also add `Approov-Token` or substitute app secret values, depending upon your integration choices. You should thus use this client for all API calls you may wish to protect.

Approov errors will generate an `ApproovException`, which is a type of `IOException`. This may be further specialized into an `ApproovNetworkException`, indicating an issue with networking that should provide an option for a user initiated retry (which must make the new request with a call to the `getRetrofit` to get the latest client).

## Custom OkHttp Builder
By default, the Retrofit instance gets a default client constructed with a default `OkHttpClient`. However, your existing code may use a customized `OkHttpClient` with, for instance, different timeouts or other interceptors. For example, if you have existing code:

```kotlin
val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build()
val retrofit = retrofit2.Retrofit.Builder().baseUrl("https://your.domain/").client(client).build()
```
Pass the modified `OkHttp.Builder` to the `ApproovService` as follows:

```kotlin
ApproovService.setOkHttpClientBuilder(OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS))
val retrofitBuilder = retrofit2.Retrofit.Builder().baseUrl("https://your.domain/")
val retrofit = ApproovService.getRetrofit(retrofitBuilder)
```

This call to `setOkHttpClientBuilder` only needs to be made once. Subsequent calls to `ApproovService.getRetrofit()` will then always a `OkHttpClient` with the builder values included.

## Checking it Works
Initially you won't have set which API domains to protect, so the interceptor will not add anything. It will have called Approov though and made contact with the Approov cloud service. You will see logging from Approov saying `UNKNOWN_URL`.

Your Approov onboarding email should contain a link allowing you to access [Live Metrics Graphs](https://approov.io/docs/latest/approov-usage-documentation/#metrics-graphs). After you've run your app with Approov integration you should be able to see the results in the live metrics within a minute or so. At this stage you could even release your app to get details of your app population and the attributes of the devices they are running upon.

## Next Steps
To actually protect your APIs and/or secrets there are some further steps. Approov provides two different options for protection:

**API Protection** You should use this if you control the backend API(s) being protected and are able to modify them to ensure that a valid Approov token is being passed by the app. An [Approov Token](https://approov.io/docs/latest/approov-usage-documentation/#approov-tokens) is short lived crytographically signed JWT proving the authenticity of the call.

**Secrets Protection** This allows app secrets, including API keys for 3rd party services, to be protected so that they no longer need to be included in the released app code. These secrets are only made available to valid apps at runtime.

Note that it is possible to use both approaches side-by-side in the same app.

# Changelog

Please see the [CHANGELOG.md](CHANGELOG.md) for more information on the changes in each version.

# Reference

Please see the [REFERENCE.md](REFERENCE.md) for more information on the Approov Service for Retrofit.

# Usage

Please see the [USAGE.md](USAGE.md) for more information on how to use this wrapper.

## Included 3rd party Source

To support message signing, this repo has adapted code released by two 3rd party developers. The LICENSE files have been copied from the repos into the associated directories listed below:

* `approov-service/src/main/java/io/approov/util/http/sfv`
    * Repo: https://github.com/reschke/structured-fields
    * Commit hash: d43f2ad6c655b92a7ef52aafa763418e1c6fed78
    * License: Apache V2
* `approov-service/src/main/java/io/approov/util/sig`
    * Repo: https://github.com/bspk/httpsig-java
    * Commit hash: ffe86ae1d07425f13b018329f51c7a7c0833d71f
    * License: MIT
