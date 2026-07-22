package io.approov.service.retrofit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowSystemClock;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

import android.os.SystemClock;

import com.criticalblue.approovsdk.Approov;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.Interceptor;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

@RunWith(RobolectricTestRunner.class)
public class FreshnessInterceptorTest {
    @Before
    @After
    public void resetApproovServiceState() {
        ApproovTestSupport.resetApproovServiceState();
    }

    // minimal chain that records the request it was asked to proceed with
    private static class FakeChain implements Interceptor.Chain {
        private final Request request;
        private final IOException proceedFailure;
        Request proceededWith;

        FakeChain(Request request) {
            this(request, null);
        }

        FakeChain(Request request, IOException proceedFailure) {
            this.request = request;
            this.proceedFailure = proceedFailure;
        }

        @Override
        public Request request() {
            return request;
        }

        @Override
        public Response proceed(Request request) throws IOException {
            proceededWith = request;
            if (proceedFailure != null)
                throw proceedFailure;
            return new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .build();
        }

        @Override
        public Connection connection() {
            return null;
        }

        @Override
        public Call call() {
            throw new UnsupportedOperationException("call() not supported in FakeChain");
        }

        @Override
        public int connectTimeoutMillis() {
            return 0;
        }

        @Override
        public Interceptor.Chain withConnectTimeout(int timeout, TimeUnit unit) {
            return this;
        }

        @Override
        public int readTimeoutMillis() {
            return 0;
        }

        @Override
        public Interceptor.Chain withReadTimeout(int timeout, TimeUnit unit) {
            return this;
        }

        @Override
        public int writeTimeoutMillis() {
            return 0;
        }

        @Override
        public Interceptor.Chain withWriteTimeout(int timeout, TimeUnit unit) {
            return this;
        }
    }

    @Test
    public void testAddedHeaderNamesComputesAddedHeaders() {
        Request before = new Request.Builder()
                .url("https://api.example.com/")
                .header("Approov-Token", "token")
                .header("Content-Type", "application/json")
                .build();
        Request after = before.newBuilder()
                .header("Signature", "sig")
                .header("Signature-Input", "sig-input")
                .header("content-type", "application/json")
                .build();
        List<String> added = ApproovRequestFreshness.addedHeaderNames(before, after);
        assertEquals(Arrays.asList("Signature", "Signature-Input"), added);
    }

    @Test
    public void testAddedHeaderNamesIsCaseInsensitive() {
        Request before = new Request.Builder()
                .url("https://api.example.com/")
                .header("Approov-Token", "token")
                .build();
        Request after = before.newBuilder()
                .header("APPROOV-TOKEN", "token2")
                .build();
        List<String> added = ApproovRequestFreshness.addedHeaderNames(before, after);
        assertTrue(added.isEmpty());
    }

    @Test
    public void testRequestWithoutMarkerProceedsUnchanged() throws IOException {
        Request request = new Request.Builder().url("https://api.example.com/").build();
        FakeChain chain = new FakeChain(request);
        new ApproovFreshnessInterceptor().intercept(chain);
        assertSame(request, chain.proceededWith);
    }

    @Test
    public void testFreshRequestProceedsUnchanged() throws IOException {
        ApproovRequestFreshness freshness = new ApproovRequestFreshness(
                "https://api.example.com/", new ApproovRequestMutations());
        freshness.markProtected(SystemClock.elapsedRealtime(), Arrays.asList("Signature"));
        Request request = new Request.Builder()
                .url("https://api.example.com/")
                .tag(ApproovRequestFreshness.class, freshness)
                .build();
        FakeChain chain = new FakeChain(request);
        new ApproovFreshnessInterceptor().intercept(chain);
        assertSame(request, chain.proceededWith);
    }

    @Test
    public void testStaleRequestProceedsUnchangedWhenRefreshDisabled() throws IOException {
        ApproovService.setStaleProtectionRefreshPeriod(0);
        ApproovRequestFreshness freshness = new ApproovRequestFreshness(
                "https://api.example.com/", new ApproovRequestMutations());
        freshness.markProtected(SystemClock.elapsedRealtime() - 60000, Arrays.asList("Signature"));
        Request request = new Request.Builder()
                .url("https://api.example.com/")
                .tag(ApproovRequestFreshness.class, freshness)
                .build();
        FakeChain chain = new FakeChain(request);
        new ApproovFreshnessInterceptor().intercept(chain);
        assertSame(request, chain.proceededWith);
    }

    @Test
    public void testUnmarkedProtectionTimeProceedsUnchanged() throws IOException {
        // a marker that was never marked as protected must not trigger a refresh
        ApproovRequestFreshness freshness = new ApproovRequestFreshness(
                "https://api.example.com/", new ApproovRequestMutations());
        Request request = new Request.Builder()
                .url("https://api.example.com/")
                .tag(ApproovRequestFreshness.class, freshness)
                .build();
        FakeChain chain = new FakeChain(request);
        new ApproovFreshnessInterceptor().intercept(chain);
        assertSame(request, chain.proceededWith);
    }

    @Test
    public void testMarkerSurvivesRequestRebuild() {
        // OkHttp followup requests (redirects, retries) are built with newBuilder()
        // and must retain the freshness marker
        ApproovRequestFreshness freshness = new ApproovRequestFreshness(
                "https://api.example.com/", new ApproovRequestMutations());
        Request request = new Request.Builder()
                .url("https://api.example.com/")
                .tag(ApproovRequestFreshness.class, freshness)
                .build();
        Request followup = request.newBuilder().url("https://api.example.com/redirected").build();
        assertSame(freshness, followup.tag(ApproovRequestFreshness.class));
    }

    @Test
    public void testStaleProtectionRefreshPeriodDefaultAndReset() {
        assertEquals(3000, ApproovService.getStaleProtectionRefreshPeriod());
        ApproovService.setStaleProtectionRefreshPeriod(10000);
        assertEquals(10000, ApproovService.getStaleProtectionRefreshPeriod());
        ApproovTestSupport.resetApproovServiceState();
        assertEquals(3000, ApproovService.getStaleProtectionRefreshPeriod());
    }

    @Test
    public void testFailedNetworkAttemptDoesNotMarkOriginalRequestFresh() throws Exception {
        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            ApproovTestSupport.initializeApproovService(approov);

            String url = "https://api.example.com/reply";
            Approov.TokenFetchResult firstRefresh = ApproovTestSupport.tokenResult(
                    Approov.TokenFetchStatus.SUCCESS, "fresh-token-1", "", "", false);
            Approov.TokenFetchResult retryRefresh = ApproovTestSupport.tokenResult(
                    Approov.TokenFetchStatus.SUCCESS, "fresh-token-2", "", "", false);
            approov.when(() -> Approov.fetchApproovTokenAndWait(url))
                    .thenReturn(firstRefresh, retryRefresh);

            ApproovRequestMutations changes = new ApproovRequestMutations();
            changes.setTokenHeaderKey("Approov-Token");
            ApproovRequestFreshness freshness = new ApproovRequestFreshness(url, changes);
            freshness.markProtected(SystemClock.elapsedRealtime(), Arrays.asList("Signature"));
            ShadowSystemClock.advanceBy(Duration.ofSeconds(60));
            Request originalRequest = new Request.Builder()
                    .url(url)
                    .header("Approov-Token", "stale-token")
                    .header("Signature", "stale-signature")
                    .tag(ApproovRequestFreshness.class, freshness)
                    .build();

            FakeChain failedAttempt = new FakeChain(originalRequest, new IOException("socket closed"));
            assertThrows(IOException.class,
                    () -> new ApproovFreshnessInterceptor().intercept(failedAttempt));
            assertEquals("fresh-token-1", failedAttempt.proceededWith.header("Approov-Token"));

            // OkHttp retries a recoverable network failure with the original request. It must
            // still look stale so the old token/signature are refreshed again for that retry.
            FakeChain retry = new FakeChain(originalRequest);
            new ApproovFreshnessInterceptor().intercept(retry);

            assertEquals("fresh-token-2", retry.proceededWith.header("Approov-Token"));
            approov.verify(() -> Approov.fetchApproovTokenAndWait(url), times(2));
        }
    }
}
