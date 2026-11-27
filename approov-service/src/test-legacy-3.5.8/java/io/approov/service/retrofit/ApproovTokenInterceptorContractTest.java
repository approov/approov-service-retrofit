package io.approov.service.retrofit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mockStatic;

import com.criticalblue.approovsdk.Approov;

import java.util.Arrays;

import okhttp3.Request;
import okhttp3.Response;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

public class ApproovTokenInterceptorContractTest {

    private static final class RecordingMutator implements ApproovServiceMutator {
        private final boolean allowProceed;
        private final String extraHeaderValue;
        private final Approov.TokenFetchStatus forceFalseStatus;
        private ApproovRequestMutations capturedChanges;

        RecordingMutator(boolean allowProceed, String extraHeaderValue) {
            this(allowProceed, extraHeaderValue, null);
        }

        RecordingMutator(boolean allowProceed, String extraHeaderValue, Approov.TokenFetchStatus forceFalseStatus) {
            this.allowProceed = allowProceed;
            this.extraHeaderValue = extraHeaderValue;
            this.forceFalseStatus = forceFalseStatus;
        }

        @Override
        public boolean handleInterceptorFetchTokenResult(Approov.TokenFetchResult approovResults, String url)
                throws ApproovException {
            if (forceFalseStatus != null && approovResults.getStatus() == forceFalseStatus) {
                return false;
            }
            if (approovResults.getStatus() == Approov.TokenFetchStatus.NO_APPROOV_SERVICE) {
                if (!allowProceed) {
                    throw new ApproovNetworkException("custom block");
                }
                return false;
            }
            return ApproovServiceMutator.super.handleInterceptorFetchTokenResult(approovResults, url);
        }

        @Override
        public Request handleInterceptorProcessedRequest(Request request, ApproovRequestMutations changes) {
            capturedChanges = changes;
            if (extraHeaderValue == null) {
                return request;
            }
            return request.newBuilder()
                    .header("X-Mutated", extraHeaderValue)
                    .build();
        }
    }

    @Before
    public void setUp() {
        ApproovTestSupport.resetApproovServiceState();
    }

    @After
    public void tearDown() {
        ApproovService.setServiceMutator(ApproovServiceMutator.DEFAULT);
        ApproovTestSupport.resetApproovServiceState();
    }

    @Test
    public void interceptorAddsApproovTokenTraceIdAndMutatorChangesOnSuccess() throws Exception {
        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            ApproovTestSupport.initializeApproovService(approov);
            ApproovService.setApproovHeader("Approov-Token", "Bearer ");
            RecordingMutator mutator = new RecordingMutator(true, "yes");
            ApproovService.setServiceMutator(mutator);

            Request request = new Request.Builder()
                    .url("https://api.example.com/reply")
                    .build();
            Approov.TokenFetchResult successResult = ApproovTestSupport.tokenResult(
                    Approov.TokenFetchStatus.SUCCESS,
                    "jwt-token",
                    "",
                    "trace-123",
                    false);
            approov.when(() -> Approov.fetchApproovTokenAndWait("https://api.example.com/reply"))
                    .thenReturn(successResult);

            Response response = new ApproovTokenInterceptor().intercept(ApproovTestSupport.interceptorChain(request));
            Request proceeded = response.request();

            assertEquals("Bearer jwt-token", proceeded.header("Approov-Token"));
            assertEquals("trace-123", proceeded.header("Approov-TraceID"));
            assertEquals("yes", proceeded.header("X-Mutated"));
            assertNotNull(mutator.capturedChanges);
            assertEquals("Approov-Token", mutator.capturedChanges.getTokenHeaderKey());
            assertEquals("Approov-TraceID", mutator.capturedChanges.getTraceIDHeaderKey());
            assertNull(mutator.capturedChanges.getSubstitutionHeaderKeys());
            assertNull(mutator.capturedChanges.getSubstitutionQueryParamKeys());
        }
    }

    @Test
    public void interceptorUsesFetchStatusAsTokenValueWhenConfigured() throws Exception {
        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            ApproovTestSupport.initializeApproovService(approov);
            ApproovService.setUseApproovStatusIfNoToken(true);

            Request request = new Request.Builder()
                    .url("https://api.example.com/reply")
                    .build();
            Approov.TokenFetchResult mitmResult =
                    ApproovTestSupport.tokenResult(Approov.TokenFetchStatus.MITM_DETECTED);
            approov.when(() -> Approov.fetchApproovTokenAndWait("https://api.example.com/reply"))
                    .thenReturn(mitmResult);

            Response response = new ApproovTokenInterceptor().intercept(ApproovTestSupport.interceptorChain(request));
            Request proceeded = response.request();

            assertEquals("MITM_DETECTED", proceeded.header("Approov-Token"));
            assertNull(proceeded.header("Approov-TraceID"));
        }
    }

    @Test
    public void interceptorKeepsFallbackStatusHeaderWhenMutatorReturnsFalse() throws Exception {
        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            ApproovTestSupport.initializeApproovService(approov);
            ApproovService.setUseApproovStatusIfNoToken(true);
            ApproovService.setServiceMutator(new RecordingMutator(
                    true,
                    null,
                    Approov.TokenFetchStatus.MITM_DETECTED));

            Request request = new Request.Builder()
                    .url("https://api.example.com/reply")
                    .build();
            Approov.TokenFetchResult mitmResult =
                    ApproovTestSupport.tokenResult(Approov.TokenFetchStatus.MITM_DETECTED);
            approov.when(() -> Approov.fetchApproovTokenAndWait("https://api.example.com/reply"))
                    .thenReturn(mitmResult);

            Response response = new ApproovTokenInterceptor().intercept(ApproovTestSupport.interceptorChain(request));
            Request proceeded = response.request();

            assertEquals("MITM_DETECTED", proceeded.header("Approov-Token"));
            assertNull(proceeded.header("Approov-TraceID"));
        }
    }

    @Test
    public void interceptorSkipsTokenAndTraceHeadersOnNoApproovService() throws Exception {
        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            ApproovTestSupport.initializeApproovService(approov);

            Request request = new Request.Builder()
                    .url("https://api.example.com/reply")
                    .build();
            Approov.TokenFetchResult noServiceResult =
                    ApproovTestSupport.tokenResult(Approov.TokenFetchStatus.NO_APPROOV_SERVICE);
            approov.when(() -> Approov.fetchApproovTokenAndWait("https://api.example.com/reply"))
                    .thenReturn(noServiceResult);

            Response response = new ApproovTokenInterceptor().intercept(ApproovTestSupport.interceptorChain(request));
            Request proceeded = response.request();

            assertNull(proceeded.header("Approov-Token"));
            assertNull(proceeded.header("Approov-TraceID"));
        }
    }

    @Test
    public void interceptorSupportsSecureStringHeaderAndQuerySubstitutions() throws Exception {
        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            ApproovTestSupport.initializeApproovService(approov);
            ApproovService.setApproovHeader("Approov-Token", "Bearer ");
            ApproovService.addSubstitutionHeader("Api-Key", "Bearer ");
            ApproovService.addSubstitutionQueryParam("secret");
            RecordingMutator mutator = new RecordingMutator(true, null);
            ApproovService.setServiceMutator(mutator);

            Request request = new Request.Builder()
                    .url("https://api.example.com/reply?secret=query-secret")
                    .header("Api-Key", "Bearer header-secret")
                    .build();
            Approov.TokenFetchResult tokenResult = ApproovTestSupport.tokenResult(
                    Approov.TokenFetchStatus.SUCCESS,
                    "jwt-token",
                    "",
                    "",
                    false);
            Approov.TokenFetchResult headerResult = ApproovTestSupport.tokenResult(
                    Approov.TokenFetchStatus.SUCCESS,
                    "",
                    "live-header",
                    "",
                    false);
            Approov.TokenFetchResult queryResult = ApproovTestSupport.tokenResult(
                    Approov.TokenFetchStatus.SUCCESS,
                    "",
                    "live-query",
                    "",
                    false);
            approov.when(() -> Approov.fetchApproovTokenAndWait("https://api.example.com/reply?secret=query-secret"))
                    .thenReturn(tokenResult);
            approov.when(() -> Approov.fetchSecureStringAndWait("header-secret", null))
                    .thenReturn(headerResult);
            approov.when(() -> Approov.fetchSecureStringAndWait("query-secret", null))
                    .thenReturn(queryResult);

            Response response = new ApproovTokenInterceptor().intercept(ApproovTestSupport.interceptorChain(request));
            Request proceeded = response.request();

            assertEquals("Bearer jwt-token", proceeded.header("Approov-Token"));
            assertEquals("Bearer live-header", proceeded.header("Api-Key"));
            assertTrue(proceeded.url().toString().contains("secret=live-query"));
            assertNotNull(mutator.capturedChanges);
            assertEquals(Arrays.asList("Api-Key"), mutator.capturedChanges.getSubstitutionHeaderKeys());
            assertEquals("https://api.example.com/reply?secret=query-secret", mutator.capturedChanges.getOriginalURL());
            assertEquals(Arrays.asList("secret"), mutator.capturedChanges.getSubstitutionQueryParamKeys());
        }
    }

    @Test
    public void interceptorCanDisableTraceIdHeaderEvenWhenTheSdkProvidesOne() throws Exception {
        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            ApproovTestSupport.initializeApproovService(approov);
            ApproovService.setApproovTraceIDHeader(null);

            Request request = new Request.Builder()
                    .url("https://api.example.com/reply")
                    .build();
            Approov.TokenFetchResult successResult = ApproovTestSupport.tokenResult(
                    Approov.TokenFetchStatus.SUCCESS,
                    "jwt-token",
                    "",
                    "trace-123",
                    false);
            approov.when(() -> Approov.fetchApproovTokenAndWait("https://api.example.com/reply"))
                    .thenReturn(successResult);

            Response response = new ApproovTokenInterceptor().intercept(ApproovTestSupport.interceptorChain(request));

            assertEquals("jwt-token", response.request().header("Approov-Token"));
            assertFalse(response.request().headers().names().contains("Approov-TraceID"));
        }
    }

    @Test
    public void customMutatorCanBlockNoApproovServiceRequests() throws Exception {
        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            ApproovTestSupport.initializeApproovService(approov);
            ApproovService.setServiceMutator(new RecordingMutator(false, null));

            Request request = new Request.Builder()
                    .url("https://api.example.com/reply")
                    .build();
            Approov.TokenFetchResult noServiceResult =
                    ApproovTestSupport.tokenResult(Approov.TokenFetchStatus.NO_APPROOV_SERVICE);
            approov.when(() -> Approov.fetchApproovTokenAndWait("https://api.example.com/reply"))
                    .thenReturn(noServiceResult);

            ApproovNetworkException error = assertThrows(
                    ApproovNetworkException.class,
                    () -> new ApproovTokenInterceptor().intercept(ApproovTestSupport.interceptorChain(request)));

            assertEquals("custom block", error.getMessage());
        }
    }
}
