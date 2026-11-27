//
// MIT License
//
// Copyright (c) 2016-present, Approov Ltd.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files
// (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge,
// publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
// subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
// MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
// ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH
// THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

package io.approov.service.retrofit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.ResponseBody;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.http.GET;
import retrofit2.http.Header;

/**
 * The Retrofit block of ApproovService: the cache of Retrofit instances, and a Retrofit call checked at the wire
 * on a local HTTPS server. Retrofit adds nothing to the request path, so a call made through getRetrofit must
 * carry what a call made through getOkHttpClient carries, and a redirect to another origin must strip the same.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class RetrofitIntegration370Test {
    private static final String PLACEHOLDER = "api-key-placeholder";
    private static final String SECRET = "real-api-key-value";

    /** A minimal Retrofit service: raw response bodies, so that no converter is needed. */
    interface Api {
        @GET("data")
        Call<ResponseBody> data();

        @GET("start")
        Call<ResponseBody> start(@Header("Api-Key") String apiKey, @Header("Authorization") String authorization);
    }

    private TwoOriginFixture fixture;

    private void start() throws Exception {
        fixture = new TwoOriginFixture(true, PLACEHOLDER, SECRET);
        ApproovService.reset();
        RecordingSdk.install();
        fixture.initialize();
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder());
    }

    @After
    public void tearDown() throws Exception {
        if (fixture != null)
            fixture.shutdown();
        ApproovService.reset();
        RecordingSdk.uninstall();
    }

    private Retrofit.Builder builder() {
        return new Retrofit.Builder().baseUrl(fixture.protectedServer.url("/"));
    }

    @Test
    public void getRetrofitReturnsTheSameInstanceForTheSameBuilderAndAnotherForAnotherBuilder() throws Exception {
        start();
        Retrofit.Builder first = builder();
        Retrofit.Builder second = builder();
        Retrofit retrofit = ApproovService.getRetrofit(first);
        assertNotNull(retrofit);
        assertSame(retrofit, ApproovService.getRetrofit(first));
        assertNotSame(retrofit, ApproovService.getRetrofit(second));
    }

    @Test
    public void setOkHttpClientBuilderClearsTheRetrofitCache() throws Exception {
        start();
        Retrofit.Builder builder = builder();
        Retrofit before = ApproovService.getRetrofit(builder);
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder().retryOnConnectionFailure(false));
        Retrofit after = ApproovService.getRetrofit(builder);
        assertNotSame(before, after);
        assertFalse(((OkHttpClient) after.callFactory()).retryOnConnectionFailure());
    }

    @Test
    public void aNamedBuilderDoesNotClearTheCacheOfTheDefaultBuilderClient() throws Exception {
        start();
        Retrofit.Builder builder = builder();
        Retrofit before = ApproovService.getRetrofit(builder);
        // getRetrofit uses the default builder; setting another name leaves the cached instance alone
        ApproovService.setOkHttpClientBuilder("other", fixture.trustingBuilder());
        assertSame(before, ApproovService.getRetrofit(builder));
    }

    @Test
    public void initializeClearsTheRetrofitCache() throws Exception {
        start();
        Retrofit.Builder builder = builder();
        Retrofit before = ApproovService.getRetrofit(builder);
        fixture.initialize();
        Retrofit after = ApproovService.getRetrofit(builder);
        assertNotSame(before, after);
    }

    @Test
    public void aRetrofitCallCarriesTheApproovToken() throws Exception {
        start();
        fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));
        Response<ResponseBody> response = ApproovService.getRetrofit(builder()).create(Api.class).data().execute();
        assertEquals(200, response.code());
        RecordedRequest recorded = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(recorded);
        assertEquals("/data", recorded.getPath());
        String token = recorded.getHeader("Approov-Token");
        assertNotNull("the Approov token is added to a Retrofit call", token);
        assertFalse(token.isEmpty());
    }

    @Test
    public void aRetrofitCallCarriesTheInstallAndTheAccountSignatureWithCombinedAlwaysProceed() throws Exception {
        start();
        ApproovService.setServiceMutator(ApproovDefaultMessageSigning.combined(ApproovServiceMutator.ALWAYS_PROCEED));
        fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));
        Response<ResponseBody> response = ApproovService.getRetrofit(builder()).create(Api.class).data().execute();
        assertEquals(200, response.code());
        RecordedRequest recorded = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(recorded);
        String input = recorded.getHeader("Signature-Input");
        String signature = recorded.getHeader("Signature");
        assertNotNull(input);
        assertNotNull(signature);
        assertTrue(input, input.contains("install=(") && input.contains("account=("));
        assertTrue(signature, signature.contains("install=:") && signature.contains("account=:"));
    }

    @Test
    public void aSecureStringPlaceholderInARetrofitHeaderIsSubstituted() throws Exception {
        start();
        ApproovService.addSubstitutionHeader("Api-Key", null);
        fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));
        Response<ResponseBody> response = ApproovService.getRetrofit(builder()).create(Api.class)
                .start(PLACEHOLDER, "Bearer app-credential").execute();
        assertEquals(200, response.code());
        RecordedRequest recorded = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(recorded);
        assertEquals(SECRET, recorded.getHeader("Api-Key"));
        assertEquals("Bearer app-credential", recorded.getHeader("Authorization"));
    }

    @Test
    public void aCrossOriginRedirectThroughRetrofitStripsTheTokenTheSignaturesAndTheSubstitutedValue() throws Exception {
        start();
        ApproovService.addSubstitutionHeader("Api-Key", null);
        ApproovService.setServiceMutator(ApproovDefaultMessageSigning.combined(ApproovServiceMutator.ALWAYS_PROCEED));
        HttpUrl landing = fixture.otherUrl("/landing");
        fixture.protectedServer.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", landing));
        fixture.otherServer.enqueue(new MockResponse().setBody("ok"));

        Response<ResponseBody> response = ApproovService.getRetrofit(builder()).create(Api.class)
                .start(PLACEHOLDER, "Bearer app-credential").execute();
        assertEquals(200, response.code());

        RecordedRequest first = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(first);
        assertNotNull("control: the first hop carries the token", first.getHeader("Approov-Token"));
        assertNotNull("control: the first hop is signed", first.getHeader("Signature"));
        assertTrue("control: the first hop carries both signatures",
                first.getHeader("Signature").contains("install=:") && first.getHeader("Signature").contains("account=:"));
        assertEquals("control: the first hop is substituted", SECRET, first.getHeader("Api-Key"));

        RecordedRequest second = fixture.otherServer.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(second);
        assertEquals("/landing", second.getPath());
        LocalHttpsFixture.assertNoApproovHeaders(second);
        assertEquals("a substituted value goes back to the app's placeholder", PLACEHOLDER, second.getHeader("Api-Key"));
        assertNull("Authorization is not carried to another origin", second.getHeader("Authorization"));
    }

    @Test
    public void getRetrofitBeforeInitializeUsesAPlainClientWithTheBuilderSettings() throws Exception {
        ApproovService.reset();
        RecordingSdk.uninstall();
        okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer();
        try {
            server.enqueue(new MockResponse().setBody("ok"));
            server.start();
            ApproovService.setOkHttpClientBuilder(new OkHttpClient.Builder().retryOnConnectionFailure(false));
            Retrofit first = ApproovService.getRetrofit(new Retrofit.Builder().baseUrl(server.url("/")));
            assertFalse(((OkHttpClient) first.callFactory()).retryOnConnectionFailure());
            Response<ResponseBody> response = first.create(Api.class).data().execute();
            assertEquals(200, response.code());
            RecordedRequest recorded = server.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(recorded);
            LocalHttpsFixture.assertNoApproovHeaders(recorded);
        } finally {
            server.shutdown();
            ApproovService.reset();
        }
    }
}
