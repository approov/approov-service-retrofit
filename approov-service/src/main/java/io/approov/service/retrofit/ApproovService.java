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

import android.util.Log;
import android.content.Context;
import android.os.SystemClock;

import com.criticalblue.approovsdk.Approov;

import java.io.IOException;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.net.ssl.SSLPeerUnverifiedException;

import okhttp3.CertificatePinner;
import okhttp3.Connection;
import okhttp3.Interceptor;
import okhttp3.Handshake;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import retrofit2.Retrofit;

/**
 * ApproovService provides a mediation layer to the Approov SDK, enabling secure
 * token-based
 * authentication and dynamic pinning for network requests. It offers methods to
 * initialize
 * the SDK, configure token headers, handle secure strings, and manage OkHttp
 * clients.
 */
public class ApproovService {
    // logging tag
    private static final String TAG = "ApproovService";

    // default header that will be added to Approov enabled requests
    private static final String APPROOV_TOKEN_HEADER = "Approov-Token";

    // default prefix to be added before the Approov token by default
    private static final String APPROOV_TOKEN_PREFIX = "";

    // default header that will carry any optional Approov TraceID debug value from
    // the SDK
    private static final String APPROOV_TRACE_ID_HEADER = "Approov-TraceID";

    // default period in milliseconds after which a request that has been held
    // between Approov protection being applied and actual transmission (such as by
    // a device deep sleep or doze period) has its protection refreshed at the
    // network layer before being sent - this must be comfortably less than both
    // the Approov token lifetime and the default message signature expiry (15s)
    private static final long DEFAULT_STALE_PROTECTION_REFRESH_MS = 3000;

    // true if the Approov SDK initialized okay
    private static boolean isInitialized = false;

    // the config string used for initialization
    private static String configString;

    // true if the interceptor should proceed on network failures and not add an
    // Approov token
    private static boolean proceedOnNetworkFail = false;

    // true if the Approov fetch status should be used as the token header value if
    // the
    // actual token fetch fails or returns an empty token
    private static boolean useApproovStatusIfNoToken = false;

    // the Approov pinning interceptor to be used for all requests
    private static ApproovPinningInterceptor pinningInterceptor = null;

    // builder to be used for custom OkHttp clients
    private static OkHttpClient.Builder okHttpBuilder = null;

    // header to be used to send Approov tokens
    private static String approovTokenHeader = null;

    // any prefix String to be added before the transmitted Approov token
    private static String approovTokenPrefix = null;

    // header used to send any Approov TraceID provided by the SDK
    private static String approovTraceIDHeader = null;

    // any header to be used for binding in Approov tokens or null if not set
    private static String bindingHeader = null;

    // period in milliseconds after which a request held between protection and
    // transmission has its Approov protection refreshed at the network layer, or
    // <=0 if stale protection refresh is disabled
    private static long staleProtectionRefreshMS = DEFAULT_STALE_PROTECTION_REFRESH_MS;

    // The mutator instance used to control ApproovService behavior at key points in
    // the flow.
    // Unless set using the ApproovService.setServiceMutator() method, the default
    // behaviour
    // defined in the default implementation of ApproovServiceMutator will be used.
    private static ApproovServiceMutator serviceMutator = ApproovServiceMutator.DEFAULT;

    // map of headers that should have their values substituted for secure strings,
    // mapped to their
    // required prefixes
    private static Map<String, String> substitutionHeaders = null;

    // set of query parameters that may be substituted, specified by the key name
    // and mapped to the compiled Pattern
    private static Map<String, Pattern> substitutionQueryParams = null;

    // set of URL regexs that should be excluded from any Approov protection, mapped
    // to the compiled Pattern
    private static Map<String, Pattern> exclusionURLRegexs = null;

    // Cached failure result from the last Approov token fetch that returned a
    // failure status.
    // Protected by failureCacheLock for thread-safe access. This avoids redundant
    // ~1s SDK calls
    // when the platform is in a sustained failure state (e.g. no network, MITM
    // detected).
    private static final Object failureCacheLock = new Object();
    private static Approov.TokenFetchResult cachedFailureResult = null;
    private static long cachedFailureTimeMs = 0;
    private static long failureCacheTtlMs = 500; // 0.5 seconds default

    // Gate lock for the SDK fetch call. When the service-layer failure cache is
    // empty,
    // only ONE thread enters the SDK; all others wait on this lock and then
    // re-check
    // the failure cache. This collapses concurrent failure storms that the SDK does
    // not cache, while successful fetches use the SDK's own cache/fast path.
    // This is separate from failureCacheLock to avoid holding the cache lock during
    // the potentially long (~1-3s) SDK network call.
    private static final Object fetchGateLock = new Object();

    // map of cached Retrofit instances keyed by their unique builders
    private static Map<Retrofit.Builder, Retrofit> retrofitMap = new HashMap<>();

    /**
     * Construction is disallowed as this is a static only class.
     */
    private ApproovService() {
    }

    /**
     * Initializes the ApproovService with an account configuration and comment.
     * <p>
     * <b>Initialization must succeed before any protected request.</b> {@code initialize} is a
     * local, sub-millisecond call with no network I/O, so call it synchronously before building any
     * Retrofit/OkHttp client or making any API call (including from inside a DI provider). If the
     * client graph is built before initialize completes, the layer stays in bypass and a plain,
     * unprotected client can be cached for the whole app lifetime. Wrap the call in try/catch: on
     * success log the Approov device ID together with an app-generated session/correlation id so a
     * given install can be correlated across your app logs, backend and the Approov metrics; on
     * failure log it and continue unprotected by re-initializing with an empty config (bypass mode)
     * so the app still functions — those requests then go out without Approov protection.
     * <pre>{@code
     * String correlationId = UUID.randomUUID().toString();
     * try {
     *     ApproovService.initialize(getApplicationContext(), "<enter-your-config-string-here>");
     *     if (ApproovService.isApproovEnabled()) {
     *         Log.i(TAG, "Approov initialized; deviceID=" + ApproovService.getDeviceID()
     *                 + " session=" + correlationId);
     *     }
     * } catch (Exception e) {
     *     Log.e(TAG, "Approov init failed (session=" + correlationId + "); continuing unprotected", e);
     *     ApproovService.initialize(getApplicationContext(), ""); // empty config = bypass mode
     * }
     * }</pre>
     * <p>
     * <b>Configuration identity is owned by the native Approov SDK, not this layer.</b> Any
     * non-empty {@code config} is forwarded directly to {@code Approov.initialize}; this layer
     * does not compare it against the previous configuration. The native SDK returns {@code false}
     * when it is already initialized with the same configuration (treated here as success), and
     * throws {@code IllegalStateException} for a different configuration unless a
     * {@code reinit}-prefixed {@code comment} forces re-initialization.
     * <p>
     * <b>Service-layer state is changed only on success.</b> If the native SDK rejects the call,
     * this layer is left completely unchanged and keeps operating in whatever mode it was in
     * (protected or bypass). On success — including the benign same-config no-op — this layer
     * resets <i>all</i> of its own configuration back to defaults (token header and prefix,
     * trace-ID header, binding header, substitution headers and query params, exclusion regexes,
     * the service mutator, and the {@code proceedOnNetworkFail} / {@code useApproovStatusIfNoToken}
     * flags) and then re-commits. Follow the "initialize once, then configure" contract: anything
     * applied via {@code setApproovTokenHeader}, {@code addSubstitutionHeader},
     * {@code addExclusionURLRegex}, {@code setServiceMutator}, etc. must be (re)applied <i>after</i>
     * this call, otherwise a later re-initialization will silently discard it (a warning is logged
     * when a re-init discards state).
     * <p>
     * Behavior by prior state and {@code config} (the {@code comment} is forwarded to the native
     * SDK — use {@code "reinit…"} to re-initialize with a changed config, {@code "options:…"} for
     * startup options):
     * <pre>
     *   prior state   config       outcome
     *   ───────────   ──────────   ──────────────────────────────────────────────────
     *   uninit        empty        bypass mode (native SDK not initialized)
     *   uninit        valid        protected mode
     *   uninit        invalid      throws, stays uninitialized
     *   bypass        empty        stays bypass, state reset
     *   bypass        valid        upgrades to protected, state reset
     *   protected     empty        ignored, state preserved (cannot be downgraded)
     *   protected     same valid   benign no-op (SDK returns false), state reset
     *   protected     different    throws IllegalStateException, state preserved
     * </pre>
     *
     * @param context the Application context
     * @param config  the configuration string, or empty ({@code ""}) for bypass mode; must not be null
     * @param comment the comment forwarded to the native SDK (e.g. {@code "options:…"} on first
     *                init, or {@code "reinit…"} to re-initialize), or null for no comment
     * @throws IllegalArgumentException if {@code config} is null or the native SDK rejects it
     * @throws IllegalStateException    if the native SDK is already initialized with a different config
     */
    public static synchronized void initialize(Context context, String config, String comment) {
        if (config == null)
            throw new IllegalArgumentException("config must not be null; pass \"\" for bypass mode");

        // If we are already initialized with a valid config, ignore any subsequent
        // empty config initialization
        if (isApproovEnabled() && config.isEmpty()) {
            Log.d(TAG, "ApproovService already initialized with a valid config; ignoring empty configuration");
            return;
        }

        // Initialize the platform SDK if not in bypass mode (empty config).
        // State is only modified after the SDK confirms success, preserving the current
        // operating mode (protected or bypass) if the call fails.
        if (!config.isEmpty()) {
            try {
                boolean sdkInitialized = Approov.initialize(context.getApplicationContext(), config, "auto", comment);
                if (!sdkInitialized) {
                    Log.d(TAG, "Approov SDK already initialized");
                }
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Approov initialization failed: " + e.getMessage());
                throw e; // service-layer state NOT modified — prior operating mode preserved
            } catch (IllegalStateException e) {
                Log.e(TAG, "Approov initialization failed: " + e.getMessage());
                throw e; // service-layer state NOT modified — prior operating mode preserved
            }
        }
        // SDK succeeded (or bypass) — now reset and commit new service-layer state.
        // A re-initialization discards any service-layer configuration applied since the previous
        // initialize. Warn so this is visible, per the "initialize once, then configure" contract.
        if (isInitialized) {
            Log.w(TAG, "Re-initializing ApproovService: discarding previously applied service-layer "
                    + "configuration (token headers, substitutions, exclusions, mutator, flags). "
                    + "Re-apply any custom configuration after this call.");
        }
        isInitialized = false;
        proceedOnNetworkFail = false;
        useApproovStatusIfNoToken = false;
        okHttpBuilder = new OkHttpClient.Builder();
        retrofitMap = new HashMap<>();
        approovTokenHeader = APPROOV_TOKEN_HEADER;
        approovTokenPrefix = APPROOV_TOKEN_PREFIX;
        approovTraceIDHeader = APPROOV_TRACE_ID_HEADER;
        bindingHeader = null;
        staleProtectionRefreshMS = DEFAULT_STALE_PROTECTION_REFRESH_MS;
        serviceMutator = ApproovServiceMutator.DEFAULT;
        substitutionHeaders = new HashMap<>();
        substitutionQueryParams = new HashMap<>();
        exclusionURLRegexs = new HashMap<>();
        synchronized (failureCacheLock) {
            cachedFailureResult = null;
            cachedFailureTimeMs = 0;
        }
        if (!config.isEmpty())
            pinningInterceptor = new ApproovPinningInterceptor();
        else
            pinningInterceptor = null;
        isInitialized = true;
        configString = config;
        if (!config.isEmpty())
            Approov.setUserProperty("approov-service-retrofit/" + BuildConfig.APPROOV_SERVICE_VERSION);
    }

    /**
     * Initializes the ApproovService with an account configuration.
     *
     * @param context the Application context
     * @param config  the configuration string, or empty for no SDK initialization
     */
    public static void initialize(Context context, String config) {
        // default uses null comment
        initialize(context, config, null);
    }

    /**
     * Indicates whether the service layer has been initialized.
     *
     * @return true if the service layer has been initialized, false otherwise
     */
    public static synchronized boolean isInitialized() {
        return isInitialized;
    }

    /**
     * Indicates whether Approov protection is enabled for this service layer
     * instance. If initialization used an empty config string then the layer is
     * initialized but Approov protection is bypassed.
     *
     * @return true if Approov protection is enabled, false otherwise
     */
    public static synchronized boolean isApproovEnabled() {
        return isInitialized && (configString != null) && !configString.isEmpty();
    }

    static Approov.TokenFetchResult getCachedFailure() {
        synchronized (failureCacheLock) {
            if (cachedFailureResult != null &&
                    (SystemClock.elapsedRealtime() - cachedFailureTimeMs) < failureCacheTtlMs) {
                Log.d(TAG, "using cached failure: " + cachedFailureResult.getStatus().toString());
                return cachedFailureResult;
            }
            if (cachedFailureResult != null) {
                Log.d(TAG, "failure cache expired");
            }
            // Cache miss or expired — clear and allow a fresh SDK call
            cachedFailureResult = null;
            cachedFailureTimeMs = 0;
            return null;
        }
    }

    /**
     * Sets the cache time-to-live for failure results (e.g. MITM_DETECTED).
     * 
     * @param ttlMs the time to live in milliseconds
     */
    public static void setFailureCacheTtlMs(long ttlMs) {
        synchronized (failureCacheLock) {
            failureCacheTtlMs = ttlMs;
            cachedFailureResult = null;
            cachedFailureTimeMs = 0;
        }
    }

    /**
     * Caches a failure result. Only failure statuses are cached by this service
     * layer;
     * success caching is handled by the SDK.
     */
    static void cacheFailureIfNeeded(Approov.TokenFetchResult result) {
        switch (result.getStatus()) {
            case NO_NETWORK:
            case POOR_NETWORK:
            case MITM_DETECTED:
            case NO_APPROOV_SERVICE:
                synchronized (failureCacheLock) {
                    cachedFailureResult = result;
                    cachedFailureTimeMs = SystemClock.elapsedRealtime();
                    Log.d(TAG, "caching failure: " + result.getStatus().toString());
                }
                break;
            default:
                // Success and other statuses are not cached by this service layer.
                break;
        }
    }

    /**
     * Fetches an Approov token using a double-checked locking pattern on the
     * failure cache.
     * <p>
     * Fast path: if a valid cached failure exists, return it immediately (no lock).
     * Slow path: acquire the fetch gate so only ONE thread calls the SDK. Other
     * threads
     * wait, then re-check the cache. This collapses N concurrent SDK calls into 1
     * when
     * the platform is in a failure state (MITM, no network, etc.).
     * <p>
     * On the happy path (SDK returns a valid token), this service layer does not
     * cache the result. Subsequent threads still pass through the gate, but the SDK
     * is expected to serve successful follow-up fetches from its own cache/fast
     * path.
     *
     * @param url the URL to fetch the token for
     * @return the token fetch result (from cache or fresh SDK call)
     */
    static Approov.TokenFetchResult fetchApproovTokenWithGate(String url) {
        // 1. Fast path — check cache without any lock
        Approov.TokenFetchResult cached = getCachedFailure();
        if (cached != null) {
            return cached;
        }

        // 2. Slow path — gate ensures only one thread refreshes the failure state
        synchronized (fetchGateLock) {
            // Double-check: another thread may have populated the cache while we waited
            cached = getCachedFailure();
            if (cached != null) {
                Log.d(TAG, "gate: another thread cached failure while waiting: " + cached.getStatus());
                return cached;
            }

            // We are the elected thread — make the SDK call
            Log.d(TAG, "gate: fetching token for " + url);
            Approov.TokenFetchResult result = Approov.fetchApproovTokenAndWait(url);

            // Cache only failures here; success caching is handled inside the SDK.
            cacheFailureIfNeeded(result);
            return result;
        }
    }

    /**
     * Sets a flag indicating if the network interceptor should proceed anyway if it
     * is
     * not possible to obtain an Approov token due to a networking failure. If this
     * is set
     * then your backend API can receive calls without the expected Approov token
     * header
     * being added, or without header/query parameter substitutions being made. Note
     * that
     * this should be used with caution because it may allow a connection to be
     * established
     * before any dynamic pins have been received via Approov, thus potentially
     * opening the
     * channel to a MitM.
     *
     * @param proceed is true if Approov networking fails should allow continuation
     * @deprecated Use setServiceMutator to control this behavior
     */
    @Deprecated
    public static synchronized void setProceedOnNetworkFail(boolean proceed) {
        Log.d(TAG, "setProceedOnNetworkFail " + proceed);
        proceedOnNetworkFail = proceed;
    }

    /**
     * Gets a flag indicating if the network interceptor should proceed anyway if it
     * is
     * not possible to obtain an Approov token due to a networking failure.
     *
     * @return true if Approov networking fails should allow continuation, false
     *         otherwise
     * @deprecated Use setServiceMutator to control this behavior
     */
    @Deprecated
    public static synchronized boolean getProceedOnNetworkFail() {
        return proceedOnNetworkFail;
    }

    /**
     * Sets a flag indicating if the Approov fetch status (e.g. "NO_NETWORK",
     * "MITM_DETECTED")
     * should be used as the token header value if the actual token fetch fails or
     * returns an empty token.
     * This allows passing error condition information to the backend via the
     * Approov-Token header,
     * which might otherwise be empty or missing.
     *
     * @param shouldUse is true if the status should be used as the token value
     */
    public static synchronized void setUseApproovStatusIfNoToken(boolean shouldUse) {
        Log.d(TAG, "setUseApproovStatusIfNoToken " + shouldUse);
        useApproovStatusIfNoToken = shouldUse;
    }

    /**
     * Gets a flag indicating if the Approov fetch status should be used as the
     * token header value
     * if the actual token fetch fails or returns an empty token.
     *
     * @return true if the status should be used as the token value, false otherwise
     */
    public static synchronized boolean getUseApproovStatusIfNoToken() {
        return useApproovStatusIfNoToken;
    }

    /**
     * Sets a development key indicating that the app is a development version and
     * it should
     * pass attestation even if the app is not registered or it is running on an
     * emulator. The
     * development key value can be rotated at any point in the account if a version
     * of the app
     * containing the development key is accidentally released. This is primarily
     * used for situations where the app package must be modified or resigned in
     * some way as part of the testing process.
     *
     * @param devKey is the development key to be used
     * @throws ApproovException if there was a problem
     */
    public static synchronized void setDevKey(String devKey) throws ApproovException {
        if (!isApproovEnabled()) {
            Log.e(TAG, "setDevKey: SDK not initialized");
            throw new ApproovException("setDevKey: SDK not initialized");
        }
        try {
            Approov.setDevKey(devKey);
            Log.d(TAG, "setDevKey");
        } catch (IllegalStateException e) {
            throw new ApproovException("IllegalState: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ApproovException("IllegalArgument: " + e.getMessage());
        }
    }

    /**
     * Sets the header that the Approov token is added on, as well as an optional
     * prefix String (such as "Bearer "). By default the token is provided on
     * "Approov-Token" with no prefix.
     *
     * @param header is the header to place the Approov token on
     * @param prefix is any prefix String for the Approov token header
     */
    public static synchronized void setApproovHeader(String header, String prefix) {
        Log.d(TAG, "setApproovHeader " + header + ", " + prefix);
        approovTokenHeader = header;
        approovTokenPrefix = prefix;
    }

    /**
     * Sets the header name that is used to pass any optional Approov TraceID debug
     * value. By default the TraceID is provided on "Approov-TraceID" if one is
     * available. Passing null disables adding the TraceID header.
     *
     * @param header is the name of the header on which to place the Approov
     *               TraceID, or null to disable the header
     */
    public static synchronized void setApproovTraceIDHeader(String header) {
        Log.d(TAG, "setApproovTraceIDHeader " + header);
        approovTraceIDHeader = header;
    }

    /**
     * Gets the name of the header that is used to hold the optional Approov
     * TraceID.
     *
     * @return String the name of the header used for the Approov TraceID, or null
     *         if disabled
     */
    public static synchronized String getApproovTraceIDHeader() {
        return approovTraceIDHeader;
    }

    /**
     * Gets the header that is used to add the Approov token.
     *
     * @return String of the header used for the Approov token
     */
    public static synchronized String getApproovTokenHeader() {
        return approovTokenHeader;
    }

    /**
     * Gets the prefix that is added before the Approov token in the header.
     *
     * @return String of the prefix added before the Approov token
     */
    public static synchronized String getApproovTokenPrefix() {
        return approovTokenPrefix;
    }

    /**
     * Sets a binding header that must be present on all requests using the Approov
     * service. A
     * header should be chosen whose value is unchanging for most requests (such as
     * an
     * Authorization header). A hash of the header value is included in the issued
     * Approov tokens
     * to bind them to the value. This may then be verified by the backend API
     * integration. This
     * method should typically only be called once.
     *
     * @param header is the header to use for Approov token binding
     */
    public static synchronized void setBindingHeader(String header) {
        Log.d(TAG, "setBindingHeader " + header);
        bindingHeader = header;
    }

    /**
     * Gets any current binding header.
     *
     * @return binding header or null if not set
     */
    static synchronized String getBindingHeader() {
        return bindingHeader;
    }

    /**
     * Sets the period after which a request that was held between having its
     * Approov protection applied and being actually transmitted has that
     * protection (Approov token and any message signature) refreshed at the
     * network layer immediately before transmission. Requests may be held in
     * this way if the device enters a deep sleep or doze state while the
     * request is in flight, or if the app employs its own request queueing or
     * backoff mechanism. Note that such a refresh reissues the Approov token
     * fetch (usually satisfied instantly from the SDK's cache) and reapplies
     * any message signing by reinvoking the service mutator's processed
     * request callback, so it is only performed if the mutator's
     * supportsProtectionRefresh() indicates that this is safe (true for the
     * default mutator and ApproovDefaultMessageSigning, false for custom
     * mutators unless they opt in). The period should be comfortably less than the
     * message signature expiry (15 seconds by default) but high enough that
     * ordinary requests are not reprocessed. The default is 3000ms.
     *
     * @param periodMS refresh threshold in milliseconds, or <=0 to disable
     *                 stale protection refresh
     */
    public static synchronized void setStaleProtectionRefreshPeriod(long periodMS) {
        Log.d(TAG, "setStaleProtectionRefreshPeriod " + periodMS);
        staleProtectionRefreshMS = periodMS;
    }

    /**
     * Gets the period after which a held request has its Approov protection
     * refreshed at the network layer before transmission.
     *
     * @return refresh threshold in milliseconds, or <=0 if disabled
     */
    static synchronized long getStaleProtectionRefreshPeriod() {
        return staleProtectionRefreshMS;
    }

    /**
     * Sets the ApproovServiceMutator instance to handle callbacks from the
     * ApproovService implementation. This facility enables customization of
     * ApproovService operations at key points in the configuration and
     * attestation flows. It should reduce the number of times this service
     * layer implementation needs to be forked in order to introduce custom
     * behavior.
     *
     * @param mutator is the ApproovServiceMutator with callback handlers that may
     *                override the default behavior of the ApproovService singleton.
     *                Passing null to this method will reinstate the default
     *                behavior.
     */
    public static synchronized void setServiceMutator(ApproovServiceMutator mutator) {
        if (mutator == null) {
            mutator = ApproovServiceMutator.DEFAULT;
        }
        Log.d(TAG, "Applied ApproovServiceMutator:" + mutator.toString());
        serviceMutator = mutator;
    }

    /**
     * @deprecated Use setServiceMutator instead
     */
    @Deprecated
    public static void setApproovInterceptorExtensions(ApproovServiceMutator mutator) {
        setServiceMutator(mutator);
    }

    /**
     * Gets the active service mutator instance that is handling callbacks from
     * ApproovService.
     *
     * @return the service mutator instance (never null)
     */
    public static synchronized ApproovServiceMutator getServiceMutator() {
        return serviceMutator;
    }

    /**
     * Gets the interceptor extensions callback handlers if the active mutator is an
     * ApproovInterceptorExtensions instance, or null otherwise.
     *
     * @return the interceptor extensions callback handlers, or null if none set or
     *         the active
     *         mutator is not an ApproovInterceptorExtensions
     * @deprecated Use getServiceMutator instead
     */
    @Deprecated
    public static ApproovInterceptorExtensions getApproovInterceptorExtensions() {
        ApproovServiceMutator mutator = getServiceMutator();
        if (mutator instanceof ApproovInterceptorExtensions) {
            return (ApproovInterceptorExtensions) mutator;
        }
        return null;
    }

    /**
     * Adds the name of a header which should be subject to secure strings
     * substitution. This
     * means that if the header is present then the value will be used as a key to
     * look up a
     * secure string value which will be substituted into the header value instead.
     * This allows
     * easy migration to the use of secure strings. Note that this function should
     * be called on initialization
     * rather than for every request as it will require a new OkHttpClient to be
     * built. A required
     * prefix may be specified to deal with cases such as the use of "Bearer "
     * prefixed before values
     * in an authorization header.
     *
     * @param header         is the header to be marked for substitution
     * @param requiredPrefix is any required prefix to the value being substituted
     *                       or null if not required
     */
    public static synchronized void addSubstitutionHeader(String header, String requiredPrefix) {
        if (isInitialized) {
            Log.d(TAG, "addSubstitutionHeader " + header + ", " + requiredPrefix);
            if (requiredPrefix == null)
                substitutionHeaders.put(header, "");
            else
                substitutionHeaders.put(header, requiredPrefix);
        }
    }

    /**
     * Removes a header previously added using addSubstitutionHeader.
     *
     * @param header is the header to be removed for substitution
     */
    public static synchronized void removeSubstitutionHeader(String header) {
        if (isInitialized) {
            Log.d(TAG, "removeSubstitutionHeader " + header);
            substitutionHeaders.remove(header);
        }
    }

    /**
     * Gets the map of headers that are subject to substitution.
     *
     * @return a map of headers that are subject to substitution, mapped to the
     *         required prefix
     */
    public static synchronized Map<String, String> getSubstitutionHeaders() {
        return new HashMap<>(substitutionHeaders);
    }

    /**
     * Adds a key name for a query parameter that should be subject to secure
     * strings substitution.
     * This means that if the query parameter is present in a URL then the value
     * will be used as a
     * key to look up a secure string value which will be substituted as the query
     * parameter value
     * instead. This allows easy migration to the use of secure strings. Note that
     * this function
     * should be called on initialization rather than for every request as it will
     * require a new
     * OkHttpClient to be built.
     *
     * @param key is the query parameter key name to be added for substitution
     */
    public static synchronized void addSubstitutionQueryParam(String key) {
        if (isInitialized) {
            Log.d(TAG, "addSubstitutionQueryParam " + key);
            try {
                Pattern pattern = Pattern.compile("[\\?&]" + key + "=([^&;]+)");
                substitutionQueryParams.put(key, pattern);
            } catch (PatternSyntaxException e) {
                Log.e(TAG, "addSubstitutionQueryParam " + key + " error: " + e.getMessage());
            }
        }
    }

    /**
     * Removes a query parameter key name previously added using
     * addSubstitutionQueryParam.
     *
     * @param key is the query parameter key name to be removed for substitution
     */
    public static synchronized void removeSubstitutionQueryParam(String key) {
        if (isInitialized) {
            Log.d(TAG, "removeSubstitutionQueryParam " + key);
            substitutionQueryParams.remove(key);
        }
    }

    /**
     * Gets the map of substitution query parameters.
     *
     * @return a map of query parameters to be substituted, mapped to the compiled
     *         Pattern
     */
    public static synchronized Map<String, Pattern> getSubstitutionQueryParams() {
        return new HashMap<>(substitutionQueryParams);
    }

    /**
     * Adds an exclusion URL regular expression. If a URL for a request matches this
     * regular expression
     * then it will not be subject to any Approov protection. Note that this
     * facility must be used with
     * EXTREME CAUTION due to the impact of dynamic pinning. Pinning may be applied
     * to all domains added
     * using Approov, and updates to the pins are received when an Approov fetch is
     * performed. If you
     * exclude some URLs on domains that are protected with Approov, then these will
     * be protected with
     * Approov pins but without a path to update the pins until a URL is used that
     * is not excluded. Thus
     * you are responsible for ensuring that there is always a possibility of
     * calling a non-excluded
     * URL, or you should make an explicit call to fetchToken if there are
     * persistent pinning failures.
     * Conversely, use of those option may allow a connection to be established
     * before any dynamic pins
     * have been received via Approov, thus potentially opening the channel to a
     * MitM.
     *
     * @param urlRegex is the regular expression that will be compared against URLs
     *                 to exclude them
     */
    public static synchronized void addExclusionURLRegex(String urlRegex) {
        if (isInitialized) {
            try {
                Pattern pattern = Pattern.compile(urlRegex);
                exclusionURLRegexs.put(urlRegex, pattern);
                Log.d(TAG, "addExclusionURLRegex " + urlRegex);
            } catch (PatternSyntaxException e) {
                Log.e(TAG, "addExclusionURLRegex " + urlRegex + " error: " + e.getMessage());
            }
        }
    }

    /**
     * Removes an exclusion URL regular expression previously added using
     * addExclusionURLRegex.
     *
     * @param urlRegex is the regular expression that will be compared against URLs
     *                 to exclude them
     */
    public static synchronized void removeExclusionURLRegex(String urlRegex) {
        if (isInitialized) {
            Log.d(TAG, "removeExclusionURLRegex " + urlRegex);
            exclusionURLRegexs.remove(urlRegex);
        }
    }

    /**
     * Gets a copy of the current exclusion URL regexs.
     *
     * @return Map<String, Pattern> of the exclusion regexs to their respective
     *         Patterns
     */
    static synchronized Map<String, Pattern> getExclusionURLRegexs() {
        return new HashMap<>(exclusionURLRegexs);
    }

    /**
     * Prefetches in the background to lower the effective latency of a subsequent
     * token fetch or
     * secure string fetch by starting the operation earlier so the subsequent fetch
     * may be able to
     * use cached data.
     * <p>
     * Note: This method is obsolete and is now a no-op. The underlying Approov SDK
     * manages prefetching automatically.
     */
    @Deprecated
    public static synchronized void prefetch() {
        Log.w(TAG, "prefetch is no longer used and does nothing.");
    }

    /**
     * Performs a precheck to determine if the app will pass attestation. This
     * requires secure
     * strings to be enabled for the account, although no strings need to be set up.
     * This will
     * likely require network access so may take some time to complete. It may throw
     * ApproovException
     * if the precheck fails or if there is some other problem.
     * ApproovRejectionException is thrown
     * if the app has failed Approov checks or ApproovNetworkException for
     * networking issues where a
     * user initiated retry of the operation should be allowed. An
     * ApproovRejectionException may provide
     * additional information about the cause of the rejection.
     *
     * @throws ApproovException if there was a problem
     */
    public static void precheck() throws ApproovException {
        if (!isApproovEnabled()) {
            Log.e(TAG, "precheck: SDK not initialized");
            throw new ApproovException("precheck: SDK not initialized");
        }
        // try and fetch a non-existent secure string in order to check for a rejection
        Approov.TokenFetchResult approovResults;
        try {
            approovResults = Approov.fetchSecureStringAndWait("precheck-dummy-key", null);
            Log.d(TAG, "precheck: " + approovResults.getStatus().toString());
        } catch (IllegalStateException e) {
            throw new ApproovException("IllegalState: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ApproovException("IllegalArgument: " + e.getMessage());
        }

        // process the returned Approov status using decision maker
        getServiceMutator().handlePrecheckResult(approovResults);
    }

    /**
     * Gets the device ID used by Approov to identify the particular device that the
     * SDK is running on. Note
     * that different Approov apps on the same device will return a different ID.
     * Moreover, the ID may be
     * changed by an uninstall and reinstall of the app.
     *
     * @return String of the device ID
     * @throws ApproovException if there was a problem
     */
    public static String getDeviceID() throws ApproovException {
        if (!isApproovEnabled()) {
            Log.e(TAG, "getDeviceID: SDK not initialized");
            throw new ApproovException("getDeviceID: SDK not initialized");
        }
        try {
            String deviceID = Approov.getDeviceID();
            Log.d(TAG, "getDeviceID: " + deviceID);
            return deviceID;
        } catch (IllegalStateException e) {
            throw new ApproovException("IllegalState: " + e.getMessage());
        }
    }

    /**
     * Directly sets the data hash to be included in subsequently fetched Approov
     * tokens. If the hash is
     * different from any previously set value then this will cause the next token
     * fetch operation to
     * fetch a new token with the correct payload data hash. The hash appears in the
     * 'pay' claim of the Approov token as a base64 encoded string of the SHA256
     * hash of the
     * data. Note that the data is hashed locally and never sent to the Approov
     * cloud service.
     *
     * @param data is the data to be hashed and set in the token
     * @throws ApproovException if there was a problem
     */
    public static void setDataHashInToken(String data) throws ApproovException {
        if (!isApproovEnabled()) {
            Log.e(TAG, "setDataHashInToken: SDK not initialized");
            throw new ApproovException("setDataHashInToken: SDK not initialized");
        }
        try {
            Approov.setDataHashInToken(data);
            Log.d(TAG, "setDataHashInToken");
        } catch (IllegalStateException e) {
            throw new ApproovException("IllegalState: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ApproovException("IllegalArgument: " + e.getMessage());
        }
    }

    /**
     * Performs an Approov token fetch for the given URL. This should be used in
     * situations where it
     * is not possible to use the networking interception to add the token. This
     * will
     * likely require network access so may take some time to complete. If the
     * attestation fails
     * for any reason then an ApproovException is thrown. This will be
     * ApproovNetworkException for
     * networking issues where a user initiated retry of the operation should be
     * allowed. Note that
     * the returned token should NEVER be cached by your app, you should call this
     * function when
     * it is needed.
     *
     * @param url is the full URL (including path) for the token fetch
     * @return String of the fetched token
     * @throws ApproovException if there was a problem
     */
    public static String fetchToken(String url) throws ApproovException {
        if (!isApproovEnabled()) {
            Log.e(TAG, "fetchToken: SDK not initialized");
            throw new ApproovException("fetchToken: SDK not initialized");
        }
        // fetch the Approov token
        Approov.TokenFetchResult approovResults;
        try {
            approovResults = Approov.fetchApproovTokenAndWait(url);
            Log.d(TAG, "fetchToken: " + approovResults.getStatus().toString());
        } catch (IllegalStateException e) {
            throw new ApproovException("IllegalState: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ApproovException("IllegalArgument: " + e.getMessage());
        }

        // process the status using decision maker
        getServiceMutator().handleFetchTokenResult(approovResults);
        return approovResults.getToken();
    }

    /**
     * Gets the signature for the given message. This uses an account specific
     * message signing key that is
     * transmitted to the SDK after a successful fetch if the facility is enabled
     * for the account. Note
     * that if the attestation failed then the signing key provided is actually
     * random so that the
     * signature will be incorrect. An Approov token should always be included in
     * the message
     * being signed and sent alongside this signature to prevent replay attacks. If
     * no signature is
     * available, because there has been no prior fetch or the feature is not
     * enabled, then an
     * ApproovException is thrown.
     * <p>
     * Deprecated: use getAccountMessageSignature instead
     *
     * @param message is the message whose content is to be signed
     * @return String of the base64 encoded message signature
     * @throws ApproovException if there was a problem
     */
    @Deprecated
    public static String getMessageSignature(String message) throws ApproovException {
        return getAccountMessageSignature(message);
    }

    /**
     * Gets the signature for the given message. This uses an account specific
     * message signing key that is
     * transmitted to the SDK after a successful fetch if the facility is enabled
     * for the account. Note
     * that if the attestation failed then the signing key provided is actually
     * random so that the
     * signature will be incorrect. An Approov token should always be included in
     * the message
     * being signed and sent alongside this signature to prevent replay attacks. If
     * no signature is
     * available, because there has been no prior fetch or the feature is not
     * enabled, then an
     * ApproovException is thrown.
     *
     * @param message is the message whose content is to be signed
     * @return String of the base64 encoded message signature
     * @throws ApproovException if there was a problem
     */
    public static String getAccountMessageSignature(String message) throws ApproovException {
        if (!isApproovEnabled()) {
            Log.e(TAG, "getAccountMessageSignature: SDK not initialized");
            throw new ApproovException("getAccountMessageSignature: SDK not initialized");
        }
        try {
            String signature = Approov.getAccountMessageSignature(message);
            Log.d(TAG, "getAccountMessageSignature");
            if (signature == null)
                throw new ApproovException("no account signature available");
            return signature;
        } catch (IllegalStateException e) {
            throw new ApproovException("IllegalState: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ApproovException("IllegalArgument: " + e.getMessage());
        }
    }

    /**
     * Gets the install signature for the given message. This uses an app install
     * specific message
     * signing key that is generated the first time an app launches. This signing
     * mechanism uses an
     * ECC key pair where the private key is managed by the secure element or
     * trusted execution
     * environment of the device. Where it can, Approov uses attested key pairs to
     * perform the
     * message signing.
     * <p>
     * An Approov token should always be included in the message being signed and
     * sent alongside
     * this signature to prevent replay attacks.
     * <p>
     * If no signature is available, because there has been no prior fetch or the
     * feature is not
     * enabled, then an ApproovException is thrown.
     *
     * @param message is the message whose content is to be signed
     * @return String of the base64 encoded message signature in ASN.1 DER format
     * @throws ApproovException if there was a problem
     */
    public static String getInstallMessageSignature(String message) throws ApproovException {
        if (!isApproovEnabled()) {
            Log.e(TAG, "getInstallMessageSignature: SDK not initialized");
            throw new ApproovException("getInstallMessageSignature: SDK not initialized");
        }
        try {
            String signature = Approov.getInstallMessageSignature(message);
            Log.d(TAG, "getInstallMessageSignature");
            if (signature == null)
                throw new ApproovException("no device signature available");
            return signature;
        } catch (IllegalStateException e) {
            throw new ApproovException("IllegalState: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ApproovException("IllegalArgument: " + e.getMessage());
        }
    }

    /**
     * Fetches a secure string with the given key. If newDef is not null then a
     * secure string for the particular app instance may be defined. In this case
     * the
     * new value is returned as the secure string. Use of an empty string for newDef
     * removes
     * the string entry. Note that this call may require network transaction and
     * thus may block
     * for some time, so should not be called from the UI thread. If the attestation
     * fails
     * for any reason then an ApproovException is thrown. This will be
     * ApproovRejectionException
     * if the app has failed Approov checks or ApproovNetworkException for
     * networking issues where
     * a user initiated retry of the operation should be allowed. Note that the
     * returned string
     * should NEVER be cached by your app, you should call this function when it is
     * needed.
     *
     * @param key    is the secure string key to be looked up
     * @param newDef is any new definition for the secure string, or null for lookup
     *               only
     * @return secure string (should not be cached by your app) or null if it was
     *         not defined
     * @throws ApproovException if there was a problem
     */
    public static String fetchSecureString(String key, String newDef) throws ApproovException {
        if (!isApproovEnabled()) {
            Log.e(TAG, "fetchSecureString: SDK not initialized");
            throw new ApproovException("fetchSecureString: SDK not initialized");
        }
        // determine the type of operation as the values themselves cannot be logged
        String type = "lookup";
        if (newDef != null)
            type = "definition";

        // fetch any secure string keyed by the value, catching any exceptions the SDK
        // might throw
        Approov.TokenFetchResult approovResults;
        try {
            approovResults = Approov.fetchSecureStringAndWait(key, newDef);
            Log.d(TAG, "fetchSecureString " + type + ": " + key + ", " + approovResults.getStatus().toString());
        } catch (IllegalStateException e) {
            throw new ApproovException("IllegalState: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ApproovException("IllegalArgument: " + e.getMessage());
        }

        // process the returned Approov status using decision maker
        getServiceMutator().handleFetchSecureStringResult(approovResults, type, key);
        return approovResults.getSecureString();
    }

    /**
     * Fetches a custom JWT with the given payload. Note that this call will require
     * network
     * transaction and thus will block for some time, so should not be called from
     * the UI thread.
     * If the attestation fails for any reason then an IOException is thrown. This
     * will be
     * ApproovRejectionException if the app has failed Approov checks or
     * ApproovNetworkException
     * for networking issues where a user initiated retry of the operation should be
     * allowed.
     *
     * @param payload is the marshaled JSON object for the claims to be included
     * @return custom JWT string
     * @throws ApproovException if there was a problem
     */
    public static String fetchCustomJWT(String payload) throws ApproovException {
        if (!isApproovEnabled()) {
            Log.e(TAG, "fetchCustomJWT: SDK not initialized");
            throw new ApproovException("fetchCustomJWT: SDK not initialized");
        }
        // fetch the custom JWT catching any exceptions the SDK might throw
        Approov.TokenFetchResult approovResults;
        try {
            approovResults = Approov.fetchCustomJWTAndWait(payload);
            Log.d(TAG, "fetchCustomJWT: " + approovResults.getStatus().toString());
        } catch (IllegalStateException e) {
            throw new ApproovException("IllegalState: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ApproovException("IllegalArgument: " + e.getMessage());
        }

        // process the returned Approov status using decision maker
        getServiceMutator().handleFetchCustomJWTResult(approovResults);
        return approovResults.getToken();
    }

    /**
     * Gets the last ARC (Attestation Response Code) code.
     *
     * Always resolves with a string (ARC or empty string).
     * NOTE: You MUST only call this method upon successful attestation completion.
     * Any networking
     * errors returned from the service layer will not return a meaningful ARC code
     * if the method is called!!!
     * 
     * @return String ARC from last attestation request or empty string if network
     *         unavailable
     */
    public static String getLastARC() {
        if (!isApproovEnabled()) {
            Log.e(TAG, "getLastARC: SDK not initialized");
            return "";
        }
        // Get the dynamic pins from Approov
        Map<String, List<String>> approovPins = Approov.getPins("public-key-sha256");
        if (approovPins == null || approovPins.isEmpty()) {
            Log.e(TAG, "ApproovService: no host pinning information available");
            return "";
        }
        // The approovPins contains a map of hostnames to pin strings. Skip '*' and use
        // another hostname if available.
        String hostname = null;
        for (String key : approovPins.keySet()) {
            if (!"*".equals(key)) {
                hostname = key;
                break;
            }
        }
        if (hostname != null) {
            try {
                Approov.TokenFetchResult result = Approov.fetchApproovTokenAndWait(hostname);
                if (result.getToken() != null && !result.getToken().isEmpty()) {
                    String arc = result.getARC();
                    if (arc != null) {
                        return arc;
                    }
                }
                Log.i(TAG, "ApproovService: ARC code unavailable");
                return "";
            } catch (Exception e) {
                Log.e(TAG, "ApproovService: error fetching ARC", e);
                return "";
            }
        } else {
            Log.i(TAG, "ApproovService: ARC code unavailable");
            return "";
        }
    }

    /**
     * Sets an install attributes token to be sent to the server and associated with
     * this particular
     * app installation for future Approov token fetches. The token must be signed,
     * within its
     * expiry time and bound to the correct device ID for it to be accepted by the
     * server.
     * Calling this method ensures that the next call to fetch an Approov
     * token will not use a cached version, so that this information can be
     * transmitted to the server.
     *
     * @param attrs is the signed JWT holding the new install attributes
     * @return void
     * @throws ApproovException if the attrs parameter is invalid or the SDK is not
     *                          initialized
     */
    public static void setInstallAttrsInToken(String attrs) throws ApproovException {
        if (!isApproovEnabled()) {
            Log.e(TAG, "setInstallAttrsInToken: SDK not initialized");
            throw new ApproovException("setInstallAttrsInToken: SDK not initialized");
        }
        try {
            Approov.setInstallAttrsInToken(attrs);
            Log.d(TAG, "setInstallAttrsInToken");
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "setInstallAttrsInToken failed with IllegalArgument: " + e.getMessage());
            throw new ApproovException("setInstallAttrsInToken: " + e.getMessage());
        } catch (IllegalStateException e) {
            Log.e(TAG, "setInstallAttrsInToken failed with IllegalState: " + e.getMessage());
            throw new ApproovException("setInstallAttrsInToken: " + e.getMessage());
        }
    }

    /**
     * Rebuilds the pins in the pinning interceptor after a dynamic configuration
     * change.
     */
    static synchronized void rebuildPins() {
        if (pinningInterceptor != null)
            pinningInterceptor.buildPins();
    }

    /**
     * Sets the OkHttpClient.Builder to be used for constructing the OkHttpClients
     * used in the
     * Retrofit instances. This allows a custom configuration to be set, with
     * additional interceptors
     * and properties. This clears the cached Retrofit client instances so should
     * only be called when
     * an actual builder change is required.
     *
     * @param builder is the OkHttpClient.Builder to be used in Retrofit instances
     */
    public static synchronized void setOkHttpClientBuilder(OkHttpClient.Builder builder) {
        okHttpBuilder = builder;
        retrofitMap.clear();
    }

    /**
     * Gets a Retrofit instance that enables the Approov service. The builder for
     * Retrofit should
     * be provided to allow its customization. This simply adds the underlying
     * OkHttpClient to be
     * used. Approov tokens are added in headers to requests, and connections are
     * also pinned.
     * Retrofit instances are added lazily on demand but are cached if there is no
     * change.
     * lazily on demand but is cached if there are no changes. Note that once
     * constructed and
     * passed to this method, Retrofit builder instances should not be changed
     * further. If any
     * changes are required then a new builder should be constructed. Use
     * "setOkHttpClientBuilder"
     * to provide any special properties for the underlying OkHttpClient.
     *
     * @param builder is the Retrofit.Builder for required client instance
     * @return Retrofit instance to be used with Approov
     */
    public static synchronized Retrofit getRetrofit(Retrofit.Builder builder) {
        Retrofit retrofit = retrofitMap.get(builder);
        if (retrofit == null) {
            // build any required OkHttpClient on demand
            OkHttpClient okHttpClient;
            if (isApproovEnabled()) {
                // remove any existing ApproovTokenInterceptor from the builder
                List<Interceptor> interceptors = okHttpBuilder.interceptors();
                Iterator<Interceptor> iter = interceptors.iterator();
                while (iter.hasNext()) {
                    Interceptor interceptor = iter.next();
                    if (interceptor instanceof ApproovTokenInterceptor)
                        iter.remove();
                }

                // remove any existing ApproovFreshnessInterceptor or
                // ApproovPinningInterceptor from the builder
                interceptors = okHttpBuilder.networkInterceptors();
                iter = interceptors.iterator();
                while (iter.hasNext()) {
                    Interceptor interceptor = iter.next();
                    if ((interceptor instanceof ApproovFreshnessInterceptor) ||
                            (interceptor instanceof ApproovPinningInterceptor))
                        iter.remove();
                }

                // build the OkHttpClient with the correct pins preset and Approov interceptor.
                // The freshness interceptor runs as a network interceptor before pinning so
                // that it re-checks protection immediately before transmission, including on
                // OkHttp generated retries and redirect followups.
                Log.d(TAG, "Building new Approov OkHttpClient");
                ApproovTokenInterceptor tokenInterceptor = new ApproovTokenInterceptor();
                okHttpClient = okHttpBuilder.addInterceptor(tokenInterceptor)
                        .addNetworkInterceptor(new ApproovFreshnessInterceptor())
                        .addNetworkInterceptor(pinningInterceptor).build();
            } else {
                if (isInitialized()) {
                    Log.i(TAG, "Building basic OkHttpClient (Approov bypass mode)");
                } else {
                    Log.e(TAG, "Cannot build Approov OkHttpClient as not initialized");
                }
                if (okHttpBuilder == null)
                    okHttpBuilder = new OkHttpClient.Builder();
                okHttpClient = okHttpBuilder.build();
            }

            // build a new Retrofit instance
            retrofit = builder.client(okHttpClient).build();
            retrofitMap.put(builder, retrofit);
        }
        return retrofit;
    }
}

/**
 * Callback handler for prefetching. We simply log as we don't need the result
 * itself, as it will be returned as a cached value on a subsequent fetch.
 */
final class PrefetchCallbackHandler implements Approov.TokenFetchCallback {
    // logging tag
    private static final String TAG = "ApproovPrefetch";

    @Override
    public void approovCallback(Approov.TokenFetchResult result) {
        if ((result.getStatus() == Approov.TokenFetchStatus.SUCCESS) ||
                (result.getStatus() == Approov.TokenFetchStatus.UNKNOWN_URL)) {
            Log.d(TAG, "Prefetch success");
        } else {
            Log.e(TAG, "Prefetch failure: " + result.getStatus().toString());
        }
    }
}

// interceptor to add Approov tokens or substitute headers and query parameters
class ApproovTokenInterceptor implements Interceptor {
    // logging tag
    private final static String TAG = "ApproovTokenInterceptor";

    /**
     * Constructs a new interceptor that adds Approov tokens and substitutes headers
     * or query
     * parameters.
     */
    public ApproovTokenInterceptor() {
    }

    /**
     * Determines whether a fallback Approov status should be sent in the
     * Approov token header when a request is allowed to continue without a real
     * token.
     */
    private boolean shouldSendFallbackStatusHeader(Approov.TokenFetchResult approovResults) {
        if (!ApproovService.getUseApproovStatusIfNoToken())
            return false;

        switch (approovResults.getStatus()) {
            case NO_NETWORK:
            case POOR_NETWORK:
            case MITM_DETECTED:
            case NO_APPROOV_SERVICE:
            case REJECTED:
                return true;
            default:
                return false;
        }
    }

    @Override
    public Response intercept(Chain chain) throws IOException {

        Request request = chain.request();
        if (!ApproovService.isApproovEnabled()) {
            return chain.proceed(request);
        }

        // cache the mutator for the duration of the interceptor to make sure
        // it is not changed mid-flight
        ApproovServiceMutator mutator = ApproovService.getServiceMutator();
        // first check if we are to proceed with any Approov processing
        if (!mutator.handleInterceptorShouldProcessRequest(request)) {
            // we are not to proceed with any Approov processing so just continue
            return chain.proceed(request);
        }

        // update the data hash based on any token binding header (presence is optional)
        String bindingHeader = ApproovService.getBindingHeader();
        if ((bindingHeader != null) && request.headers().names().contains(bindingHeader))
            Approov.setDataHashInToken(request.header(bindingHeader));

        okhttp3.HttpUrl url = request.url();

        // Fetch token using double-checked locking on the failure cache.
        // Fast path: cached failure returned immediately (no lock).
        // Slow path: gate ensures only one thread refreshes failure state; others wait
        // and re-check.
        Approov.TokenFetchResult approovResults = ApproovService.fetchApproovTokenWithGate(url.toString());

        // provide information about the obtained token or error (note "approov token
        // -check" can
        // be used to check the validity of the token and if you use token annotations
        // they
        // will appear here to determine why a request is being rejected)
        Log.d(TAG, "Token for " + url.toString() + ": " + approovResults.getLoggableToken());

        // force a pinning rebuild if there is any dynamic config update
        if (approovResults.isConfigChanged()) {
            Approov.fetchConfig();
            ApproovService.rebuildPins();
            Log.d(TAG, "Dynamic configuration updated");
        }

        // check the status of Approov token fetch using decision maker
        boolean aChange = false;
        String setTokenHeaderKey = null;
        String setTokenHeaderValue = null;
        String setTraceIDHeaderKey = null;
        String setTraceIDHeaderValue = null;
        boolean continueWithFullProcessing = mutator.handleInterceptorFetchTokenResult(approovResults, url.toString());
        if (continueWithFullProcessing) {
            // we successfully obtained a token so add it to the header for the request
            aChange = true;
            setTokenHeaderKey = ApproovService.getApproovTokenHeader();
            if ((approovResults.getToken().isEmpty()) && ApproovService.getUseApproovStatusIfNoToken())
                setTokenHeaderValue = ApproovService.getApproovTokenPrefix() + approovResults.getStatus().toString();
            else
                setTokenHeaderValue = ApproovService.getApproovTokenPrefix() + approovResults.getToken();

            String traceIDHeader = ApproovService.getApproovTraceIDHeader();
            String traceID = approovResults.getTraceID();
            if ((traceIDHeader != null) && (traceID != null) && !traceID.isEmpty()) {
                setTraceIDHeaderKey = traceIDHeader;
                setTraceIDHeaderValue = traceID;
            }
        } else if (shouldSendFallbackStatusHeader(approovResults)) {
            // the mutator chose to proceed without a real token, but the caller
            // has requested that failure statuses be forwarded in the token
            // header. We continue the request with only that fallback header and
            // skip any subsequent Approov-dependent substitution fetches.
            aChange = true;
            setTokenHeaderKey = ApproovService.getApproovTokenHeader();
            setTokenHeaderValue = ApproovService.getApproovTokenPrefix() + approovResults.getStatus().toString();
            Log.d(TAG, "Proceeding with fallback token header " + setTokenHeaderKey + ": " + setTokenHeaderValue);

            String traceIDHeader = ApproovService.getApproovTraceIDHeader();
            String traceID = approovResults.getTraceID();
            if ((traceIDHeader != null) && (traceID != null) && !traceID.isEmpty()) {
                setTraceIDHeaderKey = traceIDHeader;
                setTraceIDHeaderValue = traceID;
            }
        } else {
            // we only continue additional processing if we had a valid status from Approov,
            // to prevent additional delays
            // by trying to fetch from Approov again and this also protects against header
            // substitutions in domains not
            // protected by Approov and therefore potential subject to a MitM
            // setTokenHeaderKey and setTokenHeaderValue must be null
            return chain.proceed(request);
        }

        // we now deal with any header substitutions, which may require further fetches
        // but these
        // should be using cached results
        Map<String, String> setSubstitutionHeaders = new java.util.LinkedHashMap<>();
        String originalURL = request.url().toString();
        String replacementURL = originalURL;
        List<String> queryKeys = new ArrayList<>();
        if (continueWithFullProcessing) {
            Map<String, String> substitutionHeaders = ApproovService.getSubstitutionHeaders();
            setSubstitutionHeaders = new java.util.LinkedHashMap<>(substitutionHeaders.size());
            for (Map.Entry<String, String> entry : substitutionHeaders.entrySet()) {
                String header = entry.getKey();
                String prefix = entry.getValue();
                String value = request.header(header);
                if ((value != null) && value.startsWith(prefix) && (value.length() > prefix.length())) {
                    approovResults = Approov.fetchSecureStringAndWait(value.substring(prefix.length()), null);
                    Log.d(TAG, "Substituting header: " + header + ", " + approovResults.getStatus().toString());
                    if (mutator.handleInterceptorHeaderSubstitutionResult(approovResults, header)) {
                        // Only overwrite the header when a non-empty secure string is available.
                        // A null or empty result means substitution yielded no value; the original
                        // placeholder is preserved in place (TESTING_REQUIREMENTS §2 Missing Artifacts Fallback).
                        String secureString = approovResults.getSecureString();
                        if (secureString != null && !secureString.isEmpty()) {
                            aChange = true;
                            setSubstitutionHeaders.put(header, prefix + secureString);
                        }
                    }
                }
            }

            // we now deal with any query parameter substitutions, which may require further
            // fetches but these
            // should be using cached results
            Map<String, Pattern> substitutionQueryParams = ApproovService.getSubstitutionQueryParams();
            queryKeys = new ArrayList<>(substitutionQueryParams.size());
            for (Map.Entry<String, Pattern> entry : substitutionQueryParams.entrySet()) {
                String queryKey = entry.getKey();
                Pattern pattern = entry.getValue();
                Matcher matcher = pattern.matcher(replacementURL);
                if (matcher.find()) {
                    // we have found an occurrence of the query parameter to be replaced so we look
                    // up the existing
                    // value as a key for a secure string
                    String queryValue = matcher.group(1);
                    approovResults = Approov.fetchSecureStringAndWait(queryValue, null);
                    Log.d(TAG,
                            "Substituting query parameter: " + queryKey + ", " + approovResults.getStatus().toString());
                    if (mutator.handleInterceptorQueryParamSubstitutionResult(approovResults, queryKey)) {
                        // Only substitute when a non-empty secure string is available.
                        // A null or empty result means substitution yielded no value; the original
                        // placeholder is preserved in place (TESTING_REQUIREMENTS §2 Missing Artifacts Fallback).
                        String secureString = approovResults.getSecureString();
                        if (secureString != null && !secureString.isEmpty()) {
                            aChange = true;
                            queryKeys.add(queryKey);
                            replacementURL = new StringBuilder(replacementURL).replace(matcher.start(1),
                                    matcher.end(1), secureString).toString();
                        }
                    }
                }
            }
        }
        // gather the request changes applied to the request
        ApproovRequestMutations changes = new ApproovRequestMutations();
        // apply all the changes to the request
        ApproovRequestFreshness freshness = null;
        if (aChange) {
            Request.Builder builder = request.newBuilder();
            if (setTokenHeaderKey != null) {
                builder.header(setTokenHeaderKey, setTokenHeaderValue);
                changes.setTokenHeaderKey(setTokenHeaderKey);

                // tag the request so that the freshness interceptor can determine at the
                // network layer whether the protection was applied too long ago and must
                // be refreshed before transmission
                freshness = new ApproovRequestFreshness(url.toString(), changes);
                builder.tag(ApproovRequestFreshness.class, freshness);
            }
            if (setTraceIDHeaderKey != null) {
                builder.header(setTraceIDHeaderKey, setTraceIDHeaderValue);
                changes.setTraceIDHeaderKey(setTraceIDHeaderKey);
            }
            if (!setSubstitutionHeaders.isEmpty()) {
                for (Map.Entry<String, String> entry : setSubstitutionHeaders.entrySet()) {
                    // substitute the header
                    builder.header(entry.getKey(), entry.getValue());
                }
                changes.setSubstitutionHeaderKeys(new ArrayList<>(setSubstitutionHeaders.keySet()));
            }
            if (!originalURL.equals(replacementURL)) {
                builder.url(replacementURL);
                changes.setSubstitutionQueryParamResults(originalURL, queryKeys);
            }
            request = builder.build();
        }

        // call the processed request callback
        Request processedRequest = mutator.handleInterceptorProcessedRequest(request, changes);

        // record the time at which the protection was applied, along with the names
        // of any headers added by the processed request callback (normally message
        // signature headers), so that the freshness interceptor can refresh the
        // protection at the network layer if the request is held too long before
        // transmission
        if (freshness != null)
            freshness.markProtected(SystemClock.elapsedRealtime(),
                    ApproovRequestFreshness.addedHeaderNames(request, processedRequest));

        // proceed with the rest of the chain
        return chain.proceed(processedRequest);
    }
}

// network interceptor that refreshes the Approov protection (token and any
// message signature) on requests that were held for too long between the
// ApproovTokenInterceptor applying the protection and the request actually
// being transmitted. Requests may be held in this way if the device enters a
// deep sleep or doze state while the request is queued, or if the app employs
// its own request queueing or backoff mechanism; the Approov token and any
// message signature (which carries created/expires timestamps) may then have
// expired by the time the request is sent. Since this is a network interceptor
// it runs immediately before transmission for every attempt, including OkHttp
// generated retries and redirect followups which do not pass through the
// application layer ApproovTokenInterceptor again.
class ApproovFreshnessInterceptor implements Interceptor {
    // logging tag
    private final static String TAG = "ApproovFreshness";

    /**
     * Constructs a new interceptor that refreshes stale Approov protection.
     */
    public ApproovFreshnessInterceptor() {
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();

        // only requests given a token by the ApproovTokenInterceptor carry a
        // freshness marker and are candidates for a refresh
        ApproovRequestFreshness freshness = request.tag(ApproovRequestFreshness.class);
        if (freshness == null)
            return chain.proceed(request);

        // measure how long the request has been held since the protection was
        // applied, using a clock that advances during device sleep, and proceed
        // unchanged if within the refresh period or if the refresh is disabled
        long refreshPeriodMS = ApproovService.getStaleProtectionRefreshPeriod();
        if ((refreshPeriodMS <= 0) || (freshness.getProtectedAtMillis() < 0))
            return chain.proceed(request);
        long heldMS = SystemClock.elapsedRealtime() - freshness.getProtectedAtMillis();
        if (heldMS <= refreshPeriodMS)
            return chain.proceed(request);

        // cache the mutator for the duration of the interceptor to make sure
        // it is not changed mid-flight - a refresh reinvokes the mutator's
        // processed request callback so it is only performed if the mutator
        // declares that this is safe
        ApproovServiceMutator mutator = ApproovService.getServiceMutator();
        if (!mutator.supportsProtectionRefresh()) {
            Log.d(TAG, "Request held for " + heldMS + "ms but " + mutator +
                    " does not support protection refresh");
            return chain.proceed(request);
        }
        Log.d(TAG, "Request held for " + heldMS + "ms since Approov protection was applied, " +
                "refreshing before transmission");

        // update the data hash based on any token binding header (presence is optional)
        String bindingHeader = ApproovService.getBindingHeader();
        if ((bindingHeader != null) && request.headers().names().contains(bindingHeader))
            Approov.setDataHashInToken(request.header(bindingHeader));

        // refetch the Approov token using the URL from the original fetch - if the
        // cached token is still valid this returns immediately, otherwise a fresh
        // token is fetched.
        // TODO: this uses the retrofit-specific failure-cache gate for consistency
        // with the ApproovTokenInterceptor. This MUST be moved to a plain
        // Approov.fetchApproovTokenAndWait(freshness.getFetchURL()) once the
        // fetchApproovTokenWithGate gating function is removed (matching the okhttp
        // reference layer).
        Approov.TokenFetchResult approovResults =
                ApproovService.fetchApproovTokenWithGate(freshness.getFetchURL());
        Log.d(TAG, "Refreshed token for " + freshness.getFetchURL() + ": " + approovResults.getLoggableToken());

        // force a pinning rebuild if there is any dynamic config update
        if (approovResults.isConfigChanged()) {
            Approov.fetchConfig();
            ApproovService.rebuildPins();
            Log.d(TAG, "Dynamic configuration updated");
        }

        // check the status of the Approov token fetch using the decision maker - if
        // no token is available (but this is not an error) then the request is sent
        // unchanged
        if (!mutator.handleInterceptorFetchTokenResult(approovResults, freshness.getFetchURL()))
            return chain.proceed(request);

        // rebuild the request by removing the headers previously added by the
        // processed request callback (normally the message signature headers) and
        // updating the token header with the fresh token
        ApproovRequestMutations changes = freshness.getChanges();
        Request.Builder builder = request.newBuilder();
        for (String header : freshness.getMutatorAddedHeaders())
            builder.removeHeader(header);
        String tokenValue;
        if ((approovResults.getToken().isEmpty()) && ApproovService.getUseApproovStatusIfNoToken())
            tokenValue = ApproovService.getApproovTokenPrefix() + approovResults.getStatus().toString();
        else
            tokenValue = ApproovService.getApproovTokenPrefix() + approovResults.getToken();
        builder.header(changes.getTokenHeaderKey(), tokenValue);
        String traceIDHeader = changes.getTraceIDHeaderKey();
        String traceID = approovResults.getTraceID();
        if ((traceIDHeader != null) && (traceID != null) && !traceID.isEmpty())
            builder.header(traceIDHeader, traceID);
        Request refreshedRequest = builder.build();

        // reapply the processed request callback so that any message signature is
        // regenerated over the fresh token with new created/expires timestamps
        Request processedRequest = mutator.handleInterceptorProcessedRequest(refreshedRequest, changes);

        // Do not mark the shared freshness state until the network attempt succeeds.
        // OkHttp retries a recoverable failure with the original request, so marking
        // it before chain.proceed returns would make that original request appear
        // fresh even though it still carries the stale token and signature.
        Response response = chain.proceed(processedRequest);
        freshness.markProtected(SystemClock.elapsedRealtime(),
                ApproovRequestFreshness.addedHeaderNames(refreshedRequest, processedRequest));

        return response;
    }
}

// interceptor to implement pinning on network connections
class ApproovPinningInterceptor implements Interceptor {
    // logging tag
    private final static String TAG = "ApproovPinningInterceptor";

    // maximum number of elements that may be held in the handshake cache to allow
    // caching
    // of different concurrent connections but without causing a significant memory
    // leak
    private final static int maxCachedHandshakes = 10;

    // the certificate pinner to use for pinning that may be rebuilt if there is a
    // change
    // in the pinning configuration
    private CertificatePinner certificatePinner;

    // set of TLS handshakes that are known to be valid constrained to a size of
    // maxCachedHandshakes
    // to prevent a memory leak for long running apps
    private java.util.LinkedHashSet<Handshake> knownValidHandshakes;

    /**
     * Construct a new pinning interceptor.
     */
    public ApproovPinningInterceptor() {
        knownValidHandshakes = new java.util.LinkedHashSet<>();
        buildPins();
    }

    /**
     * Rebuild the pinning configuration. This is called when the dynamic
     * configuration
     * changes and we need to update the pinning information. This forces all known
     * valid
     * handshakes to be cleared.
     */
    synchronized public void buildPins() {
        CertificatePinner.Builder pinBuilder = new CertificatePinner.Builder();
        Map<String, List<String>> allPins = Approov.getPins("public-key-sha256");
        for (Map.Entry<String, List<String>> entry : allPins.entrySet()) {
            String domain = entry.getKey();
            if (!domain.equals("*")) {
                // the * domain is for managed trust roots and should
                // not be added directly
                List<String> pins = entry.getValue();

                // if there are no pins then we try and use any managed trust roots
                if (pins.isEmpty() && (allPins.get("*") != null))
                    pins = allPins.get("*");

                // add the required pins for the domain
                for (String pin : pins)
                    pinBuilder = pinBuilder.add(domain, "sha256/" + pin);
            }
        }
        certificatePinner = pinBuilder.build();
        knownValidHandshakes.clear();
    }

    /**
     * Gets the current CertificatePinner for checking peer certificate on a TLS
     * handshake.
     *
     * @return the current CertificatePinner
     */
    synchronized private CertificatePinner getCertificatePinner() {
        return certificatePinner;
    }

    /**
     * Determines if the given handshake is known to be valid, supporting different
     * TLS
     * negotiations on different domains as required.
     *
     * @param handshake ot be checked
     * @return true if the handshake is known valid, false otherwise
     */
    synchronized private boolean isValidHandshake(Handshake handshake) {
        return knownValidHandshakes.contains(handshake);
    }

    /**
     * Adds a valid handshake to the cached set, clearing the cache if that would
     * exceed
     * the maximum size.
     *
     * @param handshake to be added as known valid
     */
    synchronized private void addValidHandshake(Handshake handshake) {
        while (knownValidHandshakes.size() >= maxCachedHandshakes) {
            Iterator<Handshake> it = knownValidHandshakes.iterator();
            if (it.hasNext()) { // can't really fail, but this keeps it safe
                it.next();
                it.remove();
            }
        }
        knownValidHandshakes.add(handshake);
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        if (!ApproovService.isApproovEnabled()) {
            return chain.proceed(request);
        }

        // first check if we are to proceed with any pinning processing
        if (!ApproovService.getServiceMutator().handlePinningShouldProcessRequest(request)) {
            // we are not to proceed with any pinning processing so just continue
            return chain.proceed(request);
        }

        String host = chain.request().url().host();
        Connection connection = chain.connection();
        Handshake handshake = (connection != null) ? connection.handshake() : null;
        if (handshake == null)
            throw new ApproovNetworkException("network interceptor has no connection information");
        if (!isValidHandshake(handshake)) {
            // if we haven't seen this handshake and pins combination before then we
            // need to check it
            List<Certificate> certs = handshake.peerCertificates();
            try {
                getCertificatePinner().check(host, certs);
            } catch (SSLPeerUnverifiedException e) {
                // if a certificate pinning error is detected then close the socket to force
                // the next request to redo the TLS negotiation
                Log.d(TAG, "Pinning failure: " + e.toString());
                connection.socket().close();
                throw e;
            }

            // pins were valid for the handshake so cache it
            addValidHandshake(handshake);
            Log.d(TAG, "Valid pinning for: " + handshake.toString());
        }
        return chain.proceed(chain.request());
    }
}
