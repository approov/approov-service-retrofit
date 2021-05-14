# Approov Service for Retrofit

A wrapper for the [Approov SDK](https://github.com/approov/approov-android-sdk) to enable easy integration when using [`Retrofit`](https://square.github.io/retrofit/) for making the API calls that you wish to protect with Approov. In order to use this you will need a trial or paid [Approov](https://www.approov.io) account.

## Adding ApproovService Dependency
The Approov integration is available via [`jitpack`](https://jitpack.io). This allows inclusion into the project by simply specifying a dependency in the `gradle` files for the app.

Firstly, `jitpack` needs to be added to the end the `repositories` section in the `build.gradle` file at the top root level of the project:

```
allprojects {
    repositories {
        ...
        maven { url 'https://jitpack.io' }
    }
}
```

Secondly, add the dependency in your app's `build.gradle`:

```
dependencies {
	implementation 'com.github.approov:approov-service-retrofit:2.7.0'
}
```

This package is actually an open source wrapper layer that allows you to easily use Approov with `Retrofit`. This has a further dependency to the closed source [Approov SDK](https://github.com/approov/approov-android-sdk).

## Using ApproovService
In order to use the `ApproovService` you must initialize it when your app is created, usually in the `onCreate` method:

```Java
import io.approov.service.retrofit.ApproovService;

public class YourApp extends Application {
    public static ApproovService approovService;

    @Override
    public void onCreate() {
        super.onCreate();
        approovService = new ApproovService(getApplicationContext(), "init-config");
    }
}

```

The `init-config` is a custom string that configures your Approov account access. Obtain this using the Approov CLI:

```
$ approov sdk -getConfig initial-config.txt
```

Paste the file content of `initial-config.txt` into the `init-config` string above.

You can then modify your code that obtains a `RetrofitInstance` to make API calls as follows:

```Java
public class ClientInstance {
    private static final String BASE_URL = "https://your.domain";
    private static Retrofit.Builder retrofitBuilder;
    public static Retrofit getRetrofitInstance() {
        if (retrofitBuilder == null) {
            retrofitBuilder = new retrofit2.Retrofit.Builder()
                    .baseUrl(BASE_URL)
                    .addConverterFactory(GsonConverterFactory.create());
        }
        return YourApp.approovService.getRetrofit(retrofitBuilder);
    }
}
```

This obtains a retrofit instance includes an `OkHttp` interceptor to add the `Approov-Token` header and pins the connections.

## Custom OkHttp Client
By default, the Retrofit instance gets a default client constructed with `new OkHttpClient()`. However, your existing code may use a customized `OkHttpClient` with, for instance, different timeouts or other interceptors. For example, if you have existing code:

```Java
OkHttpClient client = new OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build();
retrofit = new retrofit2.Retrofit.Builder().baseUrl("your.domain/").client(client).build();
```
Pass the modified `OkHttp.Builder` to the `ApproovService` as follows:

```Java
YourApp.approovService.setOkHttpClientBuilder(new OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS));
retrofitBuilder = new retrofit2.Retrofit.Builder().baseUrl("your.domain/");
retrofit = YourApp.approovService.getRetrofit(retrofitBuilder);
```

This call to `setOkHttpClientBuilder` only needs to be made once. Subsequent calls to `YourApp.approovService.getRetrofit()` will then always a `OkHttpClient` with the builder values included.

## Approov Token Header
The default header name of `Approov-Token` can be changed as follows:

```Java
YourApp.approovService.setApproovHeader("Authorization", "Bearer ")
```

The first parameter is the new header name and the second a prefix to be added to the Approov token. This is primarily for integrations where the Approov Token JWT might need to be prefixed with `Bearer` and passed in the `Authorization` header.

## Token Binding
If you are using [Token Binding](https://approov.io/docs/latest/approov-usage-documentation/#token-binding) then set the header holding the value to be used for binding as follows:

```Java
YourApp.approovService.setBindingHeader("Authorization")
```

In this case it means that the value of `Authorization` holds the token value to be bound. This only needs to be called once. On subsequent requests the value of the specified header is read and its value set as the token binding value. Note that if the header is not present on a request then the value `NONE` is used. Note that you should only select a header that is normally always present and the value does not typically change from request to request, as each change requires a new Approov token to be fetched.

## Token Prefetching
If you wish to reduce the latency associated with fetching the first Approov token, then make this call immediately after creating `ApproovService`:

```Java
YourApp.approovService.prefetchApproovToken()
```

This initiates the process of fetching an Approov token as a background task, so that a cached token is available immediately when subsequently needed, or at least the fetch time is reduced. Note that there is no point in performing a prefetch if you are using token binding.

## Configuration Persistence
An Approov app automatically downloads any new configurations of APIs and their pins that are available. These are stored in the [`SharedPreferences`](https://developer.android.com/reference/android/content/SharedPreferences) for the app in a preference file `approov-prefs` and key `approov-config`. You can store the preferences differently by modifying or overriding the methods `ApproovService.putApproovDynamicConfig` and `ApproovService.getApproovDynamicConfig`.
