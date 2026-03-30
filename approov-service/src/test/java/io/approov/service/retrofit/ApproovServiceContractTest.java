package io.approov.service.retrofit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mockStatic;

import com.criticalblue.approovsdk.Approov;

import okhttp3.OkHttpClient;
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
    public void fetchSecureStringAllowsUnknownKeysAndReturnsNull() throws Exception {
        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
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
            approov.when(() -> Approov.getAccountMessageSignature("message"))
                    .thenReturn("base64-account-signature");

            assertEquals("base64-account-signature", ApproovService.getAccountMessageSignature("message"));
        }
    }

    @Test
    public void installMessageSignatureWrapsPlatformSigningFailures() {
        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            approov.when(() -> Approov.getInstallMessageSignature("message"))
                    .thenThrow(new IllegalStateException("private key unavailable"));

            ApproovException error = assertThrows(
                    ApproovException.class,
                    () -> ApproovService.getInstallMessageSignature("message"));

            assertTrue(error.getMessage().contains("IllegalState: private key unavailable"));
        }
    }
}
