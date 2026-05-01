package io.approov.service.retrofit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.content.Context;
import com.criticalblue.approovsdk.Approov;

import java.util.HashMap;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import retrofit2.Retrofit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

public class ApproovServiceContractTest {

    @Before
    public void setUp() {
        ApproovTestSupport.resetApproovServiceState();
    }

    @After
    public void tearDown() {
        ApproovTestSupport.resetApproovServiceState();
    }

    @Test
    public void initializeWithEmptyConfigDoesNotTouchNativeSdkAndBuildsStockClient() {
        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            Context context = mock(Context.class);

            ApproovService.initialize(context, "", "");

            assertTrue(ApproovService.isInitialized());
            assertFalse(ApproovService.isApproovEnabled());

            Retrofit retrofit = ApproovService.getRetrofit(new Retrofit.Builder()
                    .baseUrl("https://example.com/"));
            OkHttpClient client = ApproovTestSupport.retrofitClient(retrofit);

            assertTrue(client.interceptors().stream().noneMatch(interceptor -> interceptor instanceof ApproovTokenInterceptor));
            assertTrue(client.networkInterceptors().stream().noneMatch(interceptor -> interceptor instanceof ApproovPinningInterceptor));
            approov.verifyNoInteractions();
        }
    }

    @Test
    public void initializeWithEmptyConfigCanLaterBuildProtectedClients() {
        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            approov.when(() -> Approov.getPins("public-key-sha256")).thenReturn(new HashMap<>());
            Context context = mock(Context.class);
            when(context.getApplicationContext()).thenReturn(context);

            ApproovService.initialize(context, "", "");
            Retrofit stockRetrofit = ApproovService.getRetrofit(new Retrofit.Builder()
                    .baseUrl("https://example.com/"));
            OkHttpClient stockClient = ApproovTestSupport.retrofitClient(stockRetrofit);

            ApproovService.initialize(context, "dummy-config");
            Retrofit protectedRetrofit = ApproovService.getRetrofit(new Retrofit.Builder()
                    .baseUrl("https://example.com/"));
            OkHttpClient protectedClient = ApproovTestSupport.retrofitClient(protectedRetrofit);

            assertTrue(stockClient.interceptors().stream().noneMatch(interceptor -> interceptor instanceof ApproovTokenInterceptor));
            assertTrue(stockClient.networkInterceptors().stream().noneMatch(interceptor -> interceptor instanceof ApproovPinningInterceptor));
            assertTrue(protectedClient.interceptors().stream().anyMatch(interceptor -> interceptor instanceof ApproovTokenInterceptor));
            assertTrue(protectedClient.networkInterceptors().stream().anyMatch(interceptor -> interceptor instanceof ApproovPinningInterceptor));
            approov.verify(() -> Approov.initialize(context, "dummy-config", "auto", ""));
            approov.verify(() -> Approov.setUserProperty("approov-service-retrofit"));
        }
    }

    @Test
    public void bypassModeNativeWrappersDoNotTouchNativeSdk() {
        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            Context context = mock(Context.class);
            ApproovService.initialize(context, "", "");

            assertEquals("", ApproovService.getLastARC());
            assertThrows(ApproovException.class, () -> ApproovService.setInstallAttrsInToken("attrs"));
            assertThrows(ApproovException.class, () -> ApproovService.setDevKey("dev-key"));
            approov.verifyNoInteractions();
        }
    }

    @Test
    public void getRetrofitSupportsPreInitializationBuilderConfiguration() {
        OkHttpClient.Builder okHttpBuilder = new OkHttpClient.Builder()
                .retryOnConnectionFailure(false);
        Retrofit.Builder retrofitBuilder = new Retrofit.Builder()
                .baseUrl("https://example.com/");

        ApproovService.setOkHttpClientBuilder(okHttpBuilder);

        Retrofit first = ApproovService.getRetrofit(retrofitBuilder);
        Retrofit second = ApproovService.getRetrofit(retrofitBuilder);
        OkHttpClient client = ApproovTestSupport.retrofitClient(first);

        assertNotNull(first);
        assertSame(first, second);
        assertFalse(client.retryOnConnectionFailure());
        assertTrue(client.interceptors().stream().noneMatch(interceptor -> interceptor instanceof ApproovTokenInterceptor));
        assertTrue(client.networkInterceptors().stream().noneMatch(interceptor -> interceptor instanceof ApproovPinningInterceptor));
    }

    @Test
    public void initializeRestoresDefaultHeadersAndTraceIdConfiguration() {
        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            ApproovTestSupport.initializeApproovService(approov);

            assertEquals("Approov-Token", ApproovService.getApproovTokenHeader());
            assertEquals("", ApproovService.getApproovTokenPrefix());
            assertEquals("Approov-TraceID", ApproovService.getApproovTraceIDHeader());

            ApproovService.setApproovTraceIDHeader(null);

            assertEquals(null, ApproovService.getApproovTraceIDHeader());
            approov.verify(() -> Approov.setUserProperty("approov-service-retrofit"));
        }
    }

    @Test
    public void fetchTokenReturnsTheSdkTokenOnSuccess() throws Exception {
        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            ApproovTestSupport.initializeApproovService(approov);
            Approov.TokenFetchResult successResult = ApproovTestSupport.tokenResult(
                    Approov.TokenFetchStatus.SUCCESS,
                    "jwt-token",
                    "",
                    "trace-123",
                    false);
            approov.when(() -> Approov.fetchApproovTokenAndWait("https://example.com/reply"))
                    .thenReturn(successResult);

            assertEquals("jwt-token", ApproovService.fetchToken("https://example.com/reply"));
        }
    }

    @Test
    public void fetchTokenThrowsNetworkExceptionForNoNetwork() {
        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            ApproovTestSupport.initializeApproovService(approov);
            Approov.TokenFetchResult noNetworkResult =
                    ApproovTestSupport.tokenResult(Approov.TokenFetchStatus.NO_NETWORK);
            approov.when(() -> Approov.fetchApproovTokenAndWait("https://example.com/reply"))
                    .thenReturn(noNetworkResult);

            ApproovNetworkException error = assertThrows(
                    ApproovNetworkException.class,
                    () -> ApproovService.fetchToken("https://example.com/reply"));

            assertEquals(Approov.TokenFetchStatus.NO_NETWORK, error.getTokenFetchStatus());
        }
    }

    @Test
    public void interceptorFailureCacheAppliesToAllUrls() throws Exception {
        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            ApproovTestSupport.initializeApproovService(approov);
            Approov.TokenFetchResult noNetworkResult =
                    ApproovTestSupport.tokenResult(Approov.TokenFetchStatus.NO_NETWORK);
            approov.when(() -> Approov.fetchApproovTokenAndWait("https://a.example.com/"))
                    .thenReturn(noNetworkResult);

            ApproovTokenInterceptor interceptor = new ApproovTokenInterceptor();
            Request firstRequest = new Request.Builder().url("https://a.example.com/").build();
            Request secondRequest = new Request.Builder().url("https://b.example.com/").build();
            Interceptor.Chain firstChain = ApproovTestSupport.interceptorChain(firstRequest);
            Interceptor.Chain secondChain = ApproovTestSupport.interceptorChain(secondRequest);

            // First request populates the global failure cache (throws because
            // NO_NETWORK is a hard failure by default)
            assertThrows(ApproovNetworkException.class,
                    () -> interceptor.intercept(firstChain));

            // Second request to a different URL should use the cached failure
            // without calling the SDK again (NO_NETWORK is device-wide)
            assertThrows(ApproovNetworkException.class,
                    () -> interceptor.intercept(secondChain));

            // The SDK should only have been called once (for the first URL)
            approov.verify(() -> Approov.fetchApproovTokenAndWait("https://a.example.com/"));
            approov.verify(() -> Approov.fetchApproovTokenAndWait("https://b.example.com/"),
                    org.mockito.Mockito.never());
        }
    }

    @Test
    public void fetchSecureStringAllowsUnknownKeysAndReturnsNull() throws Exception {
        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            ApproovTestSupport.initializeApproovService(approov);
            Approov.TokenFetchResult unknownKeyResult = ApproovTestSupport.tokenResult(
                    Approov.TokenFetchStatus.UNKNOWN_KEY,
                    "",
                    null,
                    "",
                    false);
            approov.when(() -> Approov.fetchSecureStringAndWait("missing-key", null))
                    .thenReturn(unknownKeyResult);

            assertEquals(null, ApproovService.fetchSecureString("missing-key", null));
        }
    }

    @Test
    public void accountMessageSignatureReturnsTheSdkValue() throws Exception {
        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            ApproovTestSupport.initializeApproovService(approov);
            approov.when(() -> Approov.getAccountMessageSignature("message"))
                    .thenReturn("base64-account-signature");

            assertEquals("base64-account-signature", ApproovService.getAccountMessageSignature("message"));
        }
    }

    @Test
    public void installMessageSignatureWrapsPlatformSigningFailures() {
        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            ApproovTestSupport.initializeApproovService(approov);
            approov.when(() -> Approov.getInstallMessageSignature("message"))
                    .thenThrow(new IllegalStateException("private key unavailable"));

            ApproovException error = assertThrows(
                    ApproovException.class,
                    () -> ApproovService.getInstallMessageSignature("message"));

            assertTrue(error.getMessage().contains("IllegalState: private key unavailable"));
        }
    }
}
