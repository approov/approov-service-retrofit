package io.approov.service.retrofit;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.criticalblue.minisdk.testing.AttesterProxyController;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import com.criticalblue.approovsdk.Approov;

import static org.junit.Assert.*;

/**
 * Integration tests for the ApproovService Retrofit service layer.
 *
 * Tests are organized to match the sections defined in TESTING_REQUIREMENTS.md
 * from the core-service-layers-testing repository. Each test includes a comment
 * referencing the requirement(s) it covers.
 *
 * @see <a href="https://github.com/approov/core-service-layers-testing/blob/main/TESTING_REQUIREMENTS.md">TESTING_REQUIREMENTS.md</a>
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ApproovServiceMiniSdkTest {
    private final String validInitialConfig = "#cb-ivol#mAxOF0ekJUOC36J5XWmVmVipOcUoEdMjhPSp2FVtyTo=";
    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        AttesterProxyController.reset();
        ApproovService.initialize(context, validInitialConfig, "reinit-retrofit-tests");
    }

    @After
    public void tearDown() {
        ApproovService.setServiceMutator(ApproovServiceMutator.DEFAULT);
        AttesterProxyController.reset();
    }

    // ==================================================================================
    // SECTION 1: Initialization
    // TESTING_REQUIREMENTS.md §1
    // ==================================================================================

    /**
     * §1 Same Config Re-initialization / Different Config Re-initialization
     *
     * Re-initialize with the same config string should not fail.
     * Re-initialize with a different config string should fail with an exception.
     */
    @Test
    public void testInitializeIgnoresSameConfigAndRejectsDifferentConfig() {
        // Re-init with same config should be ignored (no exception)
        ApproovService.initialize(context, validInitialConfig);

        // Re-init with different config should throw illegal state
        String differentConfig = "#cb-other#mAxOF0ekJUOC36J5XWmVmVipOcUoEdMjhPSp2FVtyTo=";
        try {
            ApproovService.initialize(context, differentConfig);
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertEquals("ApproovService layer is already initialized.", e.getMessage());
        }
    }

    /**
     * §1 Empty Configuration
     *
     * Initializing with an empty config should keep the service layer initialized
     * while making the returned Retrofit client behave like a plain client with no
     * Approov mutations.
     */
    @Test
    public void testInitializeWithEmptyConfigBuildsPlainClient() throws Exception {
        reinitializeService(scenarioJson(uniqueCaseName("empty-config"),
            "\"protectedDomains\": [\"" + getTargetHost() + "\"]"));
        ApproovService.initialize(context, "", "reinit-empty-config");

        assertTrue(ApproovService.isInitialized());
        assertFalse(ApproovService.isApproovEnabled());

        OkHttpClient client = getOkHttpClientFromRetrofit();
        Request request = new Request.Builder()
            .url(getTargetURL())
            .build();

        try (Response response = client.newCall(request).execute()) {
            assertTrue(response.isSuccessful());
            JSONObject reply = new JSONObject(response.body().string());
            assertNull(getHeader(reply, "Approov-Token"));
            assertNull(getHeader(reply, "Approov-TraceID"));
        }
    }

    /**
     * §1 Empty Configuration then Valid Configuration
     *
     * Initializing first with an empty config should allow a later valid config to
     * enable Approov protection at runtime.
     */
    @Test
    public void testInitializeWithEmptyConfigCanLaterEnableApproov() throws Exception {
        reinitializeService(scenarioJson(uniqueCaseName("empty-then-valid"),
            "\"protectedDomains\": [\"" + getTargetHost() + "\"]"));
        ApproovService.initialize(context, "", "reinit-empty-config");

        assertTrue(ApproovService.isInitialized());
        assertFalse(ApproovService.isApproovEnabled());

        OkHttpClient plainClient = getOkHttpClientFromRetrofit();
        try (Response response = plainClient.newCall(new Request.Builder().url(getTargetURL()).build()).execute()) {
            assertTrue(response.isSuccessful());
            JSONObject reply = new JSONObject(response.body().string());
            assertNull(getHeader(reply, "Approov-Token"));
        }

        ApproovService.initialize(context, validInitialConfig);

        assertTrue(ApproovService.isInitialized());
        assertTrue(ApproovService.isApproovEnabled());

        OkHttpClient protectedClient = getOkHttpClientFromRetrofit();
        try (Response response = protectedClient.newCall(new Request.Builder().url(getTargetURL()).build()).execute()) {
            assertTrue(response.isSuccessful());
            JSONObject reply = new JSONObject(response.body().string());
            assertNotNull(getHeader(reply, "Approov-Token"));
        }
    }

    // ==================================================================================
    // SECTION 2: Request Processing & Token Behaviors
    // TESTING_REQUIREMENTS.md §2
    // ==================================================================================

    /**
     * §2 Precheck Evaluation
     */
    @Test
    public void testPrecheckTreatsUnknownKeyAsSuccess() throws ApproovException {
        ApproovService.precheck();
    }

    /**
     * §2 Device ID
     */
    @Test
    public void testGetDeviceIDReturnsMiniSDKDeviceID() throws ApproovException {
        assertEquals("daIvmEWBA2gvZny7a/RC/w==", ApproovService.getDeviceID());
    }

    /**
     * §2 Protected Request Processing / Token Binding Hash / Header & Query Substitution
     */
    @Test
    public void testUpdateRequestAddsTokenTraceBindingHashAndSubstitutions() throws Exception {
        String targetHost = getTargetHost();
        reinitializeService(scenarioJson(uniqueCaseName("substitutions"),
            "\"protectedDomains\": [\"" + targetHost + "\"]," +
            "\"initialSecureStrings\": {" +
            "  \"header-key\": \"header-secret\"," +
            "  \"query-key\": \"query-secret\"," +
            "  \"multiple-1\": \"secret-1\"," +
            "  \"multiple-2\": \"secret-2\"" +
            "}"
        ));

        ApproovService.setBindingHeader("Authorization");
        ApproovService.addSubstitutionHeader("Api-Key", null);
        ApproovService.addSubstitutionHeader("X-Multi-1", "pref-");
        ApproovService.addSubstitutionHeader("X-Multi-2", null);
        ApproovService.addSubstitutionQueryParam("api_key");
        ApproovService.addSubstitutionQueryParam("p2");

        OkHttpClient client = getOkHttpClientFromRetrofit();
        Request request = new Request.Builder()
            .url(getTargetURL() + "?api_key=query-key&p2=multiple-2")
            .header("Authorization", "Bearer oauth-token")
            .header("Api-Key", "header-key")
            .header("X-Multi-1", "pref-multiple-1")
            .header("X-Multi-2", "multiple-2")
            .build();

        try (Response response = client.newCall(request).execute()) {
            JSONObject reply = new JSONObject(response.body().string());

            String token = getHeader(reply, "Approov-Token");
            assertNotNull(token);
            assertNotNull(getHeader(reply, "Approov-TraceID"));
            assertEquals("header-secret", getHeader(reply, "Api-Key"));
            assertEquals("pref-secret-1", getHeader(reply, "X-Multi-1"));
            assertEquals("secret-2", getHeader(reply, "X-Multi-2"));

            String urlFromReply = reply.getString("url");
            assertTrue(urlFromReply.contains("api_key=query-secret"));
            assertTrue(urlFromReply.contains("p2=secret-2"));

            JSONObject payload = decodeJWTBody(token);
            assertEquals(sha256Base64("Bearer oauth-token"), payload.getString("pay"));
        }
    }

    /**
     * §2 Protected Request Processing — signed token with expected claims
     */
    @Test
    public void testFetchTokenReturnsSignedTokenWithExpectedClaims() throws Exception {
        reinitializeServiceWithTargetHost("");
        String token = ApproovService.fetchToken(getTargetURL());
        JSONObject payload = decodeJWTBody(token);

        assertEquals("81.149.55.236", payload.getString("ip"));
        assertEquals("daIvmEWBA2gvZny7a/RC/w==", payload.getString("did"));
        assertEquals("j3AWy6", payload.getString("mskid"));
        assertEquals("IXPSB7TRK26LXE3M", payload.getString("arc"));
        assertTrue(payload.has("exp"));
    }

    /**
     * §2 Missing Artifacts Fallback — NO_APPROOV_SERVICE proceeds without token
     */
    @Test
    public void testUpdateRequestNoApproovServiceProceedsWithoutToken() throws Exception {
        reinitializeServiceWithTargetHost("");
        setDirective("{" +
            "  \"operation\": \"fetchApproovToken\"," +
            "  \"response\": {" +
            "    \"status\": \"NO_APPROOV_SERVICE\"" +
            "  }" +
            "}");

        OkHttpClient client = getOkHttpClientFromRetrofit();
        Request request = new Request.Builder().url(getTargetURL()).build();
        try (Response response = client.newCall(request).execute()) {
            JSONObject reply = new JSONObject(response.body().string());
            assertNull(getHeader(reply, "Approov-Token"));
            assertNull(getHeader(reply, "Approov-TraceID"));
        }
    }

    /**
     * §2 Exclusion URL Matching
     */
    @Test
    public void testUpdateRequestCanIgnoreExcludedURL() throws Exception {
        reinitializeServiceWithTargetHost("");
        ApproovService.addExclusionURLRegex("^.*excluded.*$");

        OkHttpClient client = getOkHttpClientFromRetrofit();
        Request request = new Request.Builder().url(getTargetURL() + "/excluded").build();
        try (Response response = client.newCall(request).execute()) {
            JSONObject reply = new JSONObject(response.body().string());
            String token = getHeader(reply, "Approov-Token");
            assertNull("Expected null Approov-Token for excluded URL, but got: " + token, token);
        }
    }

    /**
     * §2 Token Fallback Status — NO_NETWORK → ApproovNetworkException
     */
    @Test
    public void testFetchTokenThrowsNetworkingErrorForNoNetwork() throws Exception {
        reinitializeServiceWithTargetHost("");
        setDirective("{" +
            "  \"operation\": \"fetchApproovToken\"," +
            "  \"response\": {" +
            "    \"status\": \"NO_NETWORK\"" +
            "  }" +
            "}");

        try {
            ApproovService.fetchToken(getTargetURL());
            fail("Expected ApproovNetworkException");
        } catch (ApproovNetworkException e) {
            assertTrue(e.getMessage().contains("fetchToken: NO_NETWORK"));
        }
    }

    /**
     * §2 Token Fallback Status — REJECTED → ApproovFetchStatusException
     */
    @Test
    public void testFetchTokenThrowsFetchStatusExceptionForRejected() throws Exception {
        reinitializeServiceWithTargetHost("");
        setDirective("{" +
            "  \"operation\": \"fetchApproovToken\"," +
            "  \"response\": {" +
            "    \"status\": \"REJECTED\"" +
            "  }" +
            "}");

        try {
            ApproovService.fetchToken(getTargetURL());
            fail("Expected ApproovFetchStatusException");
        } catch (ApproovFetchStatusException e) {
            assertTrue(e.getMessage().contains("REJECTED"));
        }
    }

    /**
     * §2 Token Fallback Status — POOR_NETWORK → ApproovNetworkException
     */
    @Test
    public void testFetchTokenThrowsNetworkExceptionForPoorNetwork() throws Exception {
        reinitializeServiceWithTargetHost("");
        setDirective("{" +
            "  \"operation\": \"fetchApproovToken\"," +
            "  \"response\": {" +
            "    \"status\": \"POOR_NETWORK\"" +
            "  }" +
            "}");

        try {
            ApproovService.fetchToken(getTargetURL());
            fail("Expected ApproovNetworkException");
        } catch (ApproovNetworkException e) {
            assertTrue(e.getMessage().contains("fetchToken: POOR_NETWORK"));
        }
    }

    /**
     * §2 Token Fallback Status — MITM_DETECTED → ApproovNetworkException
     */
    @Test
    public void testFetchTokenThrowsNetworkExceptionForMitmDetected() throws Exception {
        reinitializeServiceWithTargetHost("");
        setDirective("{" +
            "  \"operation\": \"fetchApproovToken\"," +
            "  \"response\": {" +
            "    \"status\": \"MITM_DETECTED\"" +
            "  }" +
            "}");

        try {
            ApproovService.fetchToken(getTargetURL());
            fail("Expected ApproovNetworkException");
        } catch (ApproovNetworkException e) {
            assertTrue(e.getMessage().contains("fetchToken: MITM_DETECTED"));
        }
    }

    // ==================================================================================
    // SECTION 3: Service Mutators & Decision Overrides
    // TESTING_REQUIREMENTS.md §3
    // ==================================================================================

    /**
     * §3 Custom Mutators / Decision Overrides — override fail-closed for MITM_DETECTED
     */
    @Test
    public void testServiceMutatorOverridesFailClosedBehavior() throws Exception {
        reinitializeServiceWithTargetHost("");

        setDirective("{" +
            "  \"operation\": \"fetchApproovToken\"," +
            "  \"response\": {" +
            "    \"status\": \"MITM_DETECTED\"" +
            "  }" +
            "}");

        ApproovService.setServiceMutator(new ApproovServiceMutator() {
            @Override
            public boolean handleInterceptorFetchTokenResult(Approov.TokenFetchResult approovResults, String url) throws ApproovException {
                return false;
            }
        });

        OkHttpClient client = getOkHttpClientFromRetrofit();
        Request request = new Request.Builder().url(getTargetURL()).get().build();

        try (Response response = client.newCall(request).execute()) {
            assertEquals(200, response.code());
            JSONObject reply = new JSONObject(response.body().string());
            assertNull(getHeader(reply, "Approov-Token"));
        }
    }

    // ==================================================================================
    // SECTION 5: Message Signing
    // TESTING_REQUIREMENTS.md §5
    // ==================================================================================

    /**
     * §5 Install Signature Success
     */
    @Test
    public void testUpdateRequestInstallMessageSigningAddsSignatureHeaders() throws Exception {
        reinitializeServiceWithTargetHost("");

        ApproovDefaultMessageSigning.SignatureParametersFactory factory =
            ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
            .setUseInstallMessageSigning();
        ApproovService.setServiceMutator(new ApproovDefaultMessageSigning().setDefaultFactory(factory));

        OkHttpClient client = getOkHttpClientFromRetrofit();
        Request request = new Request.Builder().url(getTargetURL()).get().build();
        try (Response response = client.newCall(request).execute()) {
            JSONObject reply = new JSONObject(response.body().string());

            assertNotNull(getHeader(reply, "Approov-Token"));
            String signatureInput = getHeader(reply, "Signature-Input");
            assertNotNull(signatureInput);
            assertTrue(signatureInput.startsWith("install="));
        }
    }

    /**
     * §5 Account Message Signing
     */
    @Test
    public void testUpdateRequestAccountMessageSigningAddsSignatureHeaders() throws Exception {
        reinitializeServiceWithTargetHost("");

        ApproovDefaultMessageSigning.SignatureParametersFactory factory =
            ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
            .setUseAccountMessageSigning();
        ApproovService.setServiceMutator(new ApproovDefaultMessageSigning().setDefaultFactory(factory));

        OkHttpClient client = getOkHttpClientFromRetrofit();
        Request request = new Request.Builder().url(getTargetURL()).get().build();
        try (Response response = client.newCall(request).execute()) {
            JSONObject reply = new JSONObject(response.body().string());

            assertNotNull(getHeader(reply, "Approov-Token"));
            String signatureInput = getHeader(reply, "Signature-Input");
            assertNotNull(signatureInput);
            assertTrue(signatureInput.startsWith("account="));
        }
    }

    // ==================================================================================
    // SECTION 6: Secure Strings & Custom JWT
    // TESTING_REQUIREMENTS.md §6
    // ==================================================================================

    /**
     * §6 Valid Secure String Key
     */
    @Test
    public void testFetchSecureStringReturnsConfiguredValue() throws ApproovException {
        setDirective("{" +
            "  \"operation\": \"fetchSecureString\"," +
            "  \"response\": {" +
            "    \"status\": \"SUCCESS\"," +
            "    \"secureString\": \"mini-secret\"" +
            "  }" +
            "}");

        String secureString = ApproovService.fetchSecureString("api-key", null);
        assertEquals("mini-secret", secureString);
    }

    /**
     * §6 Non-existent Secure String Key
     */
    @Test
    public void testFetchSecureStringReturnsNilForUnknownKey() throws ApproovException {
        setDirective("{" +
            "  \"operation\": \"fetchSecureString\"," +
            "  \"response\": {" +
            "    \"status\": \"UNKNOWN_KEY\"" +
            "  }" +
            "}");

        String secureString = ApproovService.fetchSecureString("missing-key", null);
        assertNull(secureString);
    }

    /**
     * §6 Custom JWT Fetch
     */
    @Test
    public void testFetchCustomJWTReturnsSignedJWT() throws Exception {
        String jwt = ApproovService.fetchCustomJWT("{\"role\":\"tester\"}");
        assertNotNull(jwt);
        JSONObject payload = decodeJWTBody(jwt);

        assertEquals("tester", payload.getString("role"));
        assertFalse(payload.has("exp"));
        assertFalse(payload.has("did"));
    }

    // ==================================================================================
    // Test Helpers
    // ==================================================================================

    /**
     * Extracts the underlying OkHttpClient from a Retrofit instance built via
     * ApproovService.getRetrofit(). This allows low-level request verification
     * using OkHttp directly while still exercising the Retrofit service path.
     */
    private OkHttpClient getOkHttpClientFromRetrofit() {
        Retrofit.Builder builder = new Retrofit.Builder()
            .baseUrl(getTargetURL() + "/")
            .addConverterFactory(GsonConverterFactory.create());
        Retrofit retrofit = ApproovService.getRetrofit(builder);
        return (OkHttpClient) retrofit.callFactory();
    }

    private String getTargetURL() {
        String url = System.getenv("TESTING_REPLY_URL");
        return (url != null) ? url : "https://replay.ivol.workers.dev";
    }

    private String getUnprotectedURL() {
        String url = System.getenv("TESTING_REPLY_URL_UNPROTECTED");
        return (url != null) ? url : "https://replay-unprotected.ivol.workers.dev";
    }

    private String getTargetHost() {
        String url = getTargetURL();
        return url.replace("https://", "").split("/")[0];
    }

    private void reinitializeServiceWithTargetHost(String scenarioBody) throws Exception {
        String targetHost = getTargetHost();
        String domainsJson = "\"protectedDomains\": [\"" + targetHost + "\"]," +
                             "\"pins\": {\"public-key-sha256\": {\"" + targetHost + "\": []}}";
        String fullBody = scenarioBody.isEmpty() ? domainsJson : domainsJson + ", " + scenarioBody;

        reinitializeService(scenarioJson(uniqueCaseName("target-host"), fullBody));
    }

    private void reinitializeService(String scenarioJson) {
        AttesterProxyController.reset();
        if (scenarioJson != null) {
            AttesterProxyController.loadScenarioJson(scenarioJson);
        }
        ApproovService.initialize(context, validInitialConfig, "reinit");
    }

    private void setDirective(String json) {
        AttesterProxyController.setNextAttestationDirectiveJson(json);
    }

    private String uniqueCaseName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().toLowerCase();
    }

    private String scenarioJson(String caseName, String body) {
        return "{" +
            "  \"activeCase\": \"" + caseName + "\"," +
            "  \"cases\": {" +
            "    \"" + caseName + "\": {" +
            "      " + body + "" +
            "    }" +
            "  }" +
            "}";
    }

    private String getHeader(JSONObject reply, String key) throws Exception {
        if (!reply.has("headers")) return null;
        JSONObject headers = reply.getJSONObject("headers");
        String lowerKey = key.toLowerCase();
        if (headers.has(lowerKey)) {
            Object val = headers.get(lowerKey);
            if (val instanceof String) return (String) val;
            if (val instanceof org.json.JSONArray) {
                org.json.JSONArray arr = (org.json.JSONArray) val;
                if (arr.length() > 0) return arr.getString(0);
            }
        }
        if (headers.has(key)) {
            Object val = headers.get(key);
            if (val instanceof String) return (String) val;
            if (val instanceof org.json.JSONArray) {
                org.json.JSONArray arr = (org.json.JSONArray) val;
                if (arr.length() > 0) return arr.getString(0);
            }
        }
        return null;
    }

    private JSONObject decodeJWTBody(String jwt) throws Exception {
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) return null;
        byte[] bytes = Base64.getUrlDecoder().decode(parts[1]);
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }

    private String sha256Base64(String data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash);
    }
}
