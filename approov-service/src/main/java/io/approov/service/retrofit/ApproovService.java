//
// MIT License
// 
// Copyright (c) 2016-present, Critical Blue Ltd.
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

import com.criticalblue.approovsdk.Approov;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import okhttp3.CertificatePinner;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import retrofit2.Retrofit;

// ApproovService provides a mediation layer to the Approov SDK itself
public class ApproovService {
    // logging tag
    private static final String TAG = "ApproovService";

    // default header that will be added to Approov enabled requests
    private static final String APPROOV_TOKEN_HEADER = "Approov-Token";

    // default  prefix to be added before the Approov token by default
    private static final String APPROOV_TOKEN_PREFIX = "";

    // true if the Approov SDK initialized okay
    private static boolean isInitialized = false;

    // true if the interceptor should proceed on network failures and not add an
    // Approov token
    private static boolean proceedOnNetworkFail = false;

    // builder to be used for custom OkHttp clients
    private static OkHttpClient.Builder okHttpBuilder = null;

    // header to be used to send Approov tokens
    private static String approovTokenHeader = null;

    // any prefix String to be added before the transmitted Approov token
    private static String approovTokenPrefix = null;

    // any header to be used for binding in Approov tokens or null if not set
    private static String bindingHeader = null;

    // map of headers that should have their values substituted for secure strings, mapped to their
    // required prefixes
    private static Map<String, String> substitutionHeaders = null;

    // set of query parameters that may be substituted, specified by the key name
    private static Set<String> substitutionQueryParams = null;

    // set of URL regexs that should be excluded from any Approov protection, mapped to the compiled Pattern
    private static Map<String, Pattern> exclusionURLRegexs = null;

    // map of cached Retrofit instances keyed by their unique builders
    private static Map<Retrofit.Builder, Retrofit> retrofitMap = null;

    /**
     * Construction is disallowed as this is a static only class.
     */
    private ApproovService() {
    }

    /**
     * Initializes the ApproovService with an account configuration.
     *
     * @param context the Application context
     * @param config the configuration string or empty if no SDK initialization required
     */
    public static void initialize(Context context, String config) {
        // setup ready for building Retrofit instances
        okHttpBuilder = new OkHttpClient.Builder();
        retrofitMap = new HashMap<>();
        approovTokenHeader = APPROOV_TOKEN_HEADER;
        approovTokenPrefix = APPROOV_TOKEN_PREFIX;
        bindingHeader = null;
        proceedOnNetworkFail = false;
        substitutionHeaders = new HashMap<>();
        substitutionQueryParams = new HashSet<>();
        exclusionURLRegexs = new HashMap<>();

        // initialize the Approov SDK
        try {
            if (config.length() != 0)
                Approov.initialize(context, config, "auto", "init-fetch");
            Approov.setUserProperty("approov-service-retrofit");
            isInitialized = true;
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Approov initialization failed: " + e.getMessage());
        }
    }

    /**
     * Sets a flag indicating if the network interceptor should proceed anyway if it is
     * not possible to obtain an Approov token due to a networking failure. If this is set
     * then your backend API can receive calls without the expected Approov token header
     * being added, or without header/query parameter substitutions being made. Note that
     * this should be used with caution because it may allow a connection to be established
     * before any dynamic pins have been received via Approov, thus potentially opening the
     * channel to a MitM.
     *
     * @param proceed is true if Approov networking fails should allow continuation
     */
    public static synchronized void setProceedOnNetworkFail(boolean proceed) {
        Log.d(TAG, "setProceedOnNetworkFail " + proceed);
        proceedOnNetworkFail = proceed;
        retrofitMap = new HashMap<>();
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
        retrofitMap = new HashMap<>();
    }

    /**
     * Sets a binding header that must be present on all requests using the Approov service. A
     * header should be chosen whose value is unchanging for most requests (such as an
     * Authorization header). A hash of the header value is included in the issued Approov tokens
     * to bind them to the value. This may then be verified by the backend API integration. This
     * method should typically only be called once.
     *
     * @param header is the header to use for Approov token binding
     */
    public static synchronized void setBindingHeader(String header) {
        Log.d(TAG, "setBindingHeader " + header);
        bindingHeader = header;
        retrofitMap = new HashMap<>();
    }

    /**
     * Adds the name of a header which should be subject to secure strings substitution. This
     * means that if the header is present then the value will be used as a key to look up a
     * secure string value which will be substituted into the header value instead. This allows
     * easy migration to the use of secure strings. Note that this should be done on initialization
     * rather than for every request as it will require a new OkHttpClient to be built. A required
     * prefix may be specified to deal with cases such as the use of "Bearer " prefixed before values
     * in an authorization header.
     *
     * @param header is the header to be marked for substitution
     * @param requiredPrefix is any required prefix to the value being substituted or null if not required
     */
    public static synchronized void addSubstitutionHeader(String header, String requiredPrefix) {
        if (isInitialized) {
            Log.d(TAG, "addSubstitutionHeader " + header + ", " + requiredPrefix);
            if (requiredPrefix == null)
                substitutionHeaders.put(header, "");
            else
                substitutionHeaders.put(header, requiredPrefix);
            retrofitMap = new HashMap<>();
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
            retrofitMap = new HashMap<>();
        }
    }

    /**
     * Adds a key name for a query parameter that should be subject to secure strings substitution.
     * This means that if the query parameter is present in a URL then the value will be used as a
     * key to look up a secure string value which will be substituted as the query parameter value
     * instead. This allows easy migration to the use of secure strings. Note that this function
     * should be called on initialization rather than for every request as it will require a new
     * OkHttpClient to be built.
     *
     * @param key is the query parameter key name to be added for substitution
     */
    public static synchronized void addSubstitutionQueryParam(String key) {
        if (isInitialized) {
            Log.d(TAG, "addSubstitutionQueryParam " + key);
            substitutionQueryParams.add(key);
            retrofitMap = new HashMap<>();
        }
    }

    /**
     * Removes a query parameter key name previously added using addSubstitutionQueryParam.
     *
     * @param key is the query parameter key name to be removed for substitution
     */
    public static synchronized void removeSubstitutionQueryParam(String key) {
        if (isInitialized) {
            Log.d(TAG, "removeSubstitutionQueryParam " + key);
            substitutionQueryParams.remove(key);
            retrofitMap = new HashMap<>();
        }
    }

    /**
     * Adds an exclusion URL regular expression. If a URL for a request matches this regular expression
     * then it will not be subject to any Approov protection. Note that this facility must be used with
     * EXTREME CAUTION due to the impact of dynamic pinning. Pinning may be applied to all domains added
     * using Approov, and updates to the pins are received when an Approov fetch is performed. If you
     * exclude some URLs on domains that are protected with Approov, then these will be protected with
     * Approov pins but without a path to update the pins until a URL is used that is not excluded. Thus
     * you are responsible for ensuring that there is always a possibility of calling a non-excluded
     * URL, or you should make an explicit call to fetchToken if there are persistent pinning failures.
     * Conversely, use of those option may allow a connection to be established before any dynamic pins
     * have been received via Approov, thus potentially opening the channel to a MitM.
     *
     * @param urlRegex is the regular expression that will be compared against URLs to exclude them
     */
    public static synchronized void addExclusionURLRegex(String urlRegex) {
        if (isInitialized) {
            try {
                Pattern pattern = Pattern.compile(urlRegex);
                exclusionURLRegexs.put(urlRegex, pattern);
                retrofitMap = new HashMap<>();
                Log.d(TAG, "addExclusionURLRegex " + urlRegex);
            } catch (PatternSyntaxException e) {
                Log.e(TAG, "addExclusionURLRegex " + urlRegex + " error: " + e.getMessage());
            }
        }
    }

    /**
     * Removes an exclusion URL regular expression previously added using addExclusionURLRegex.
     *
     * @param urlRegex is the regular expression that will be compared against URLs to exclude them
     */
    public static synchronized void removeExclusionURLRegex(String urlRegex) {
        if (isInitialized) {
            Log.d(TAG, "removeExclusionURLRegex " + urlRegex);
            exclusionURLRegexs.remove(urlRegex);
            retrofitMap = new HashMap<>();
        }
    }

    /**
     * Prefetches in the background to lower the effective latency of a subsequent token fetch or
     * secure string fetch by starting the operation earlier so the subsequent fetch may be able to
     * use cached data.
     */
    public static synchronized void prefetch() {
        if (isInitialized)
            // fetch an Approov token using a placeholder domain
            Approov.fetchApproovToken(new PrefetchCallbackHandler(), "approov.io");
    }

    /**
     * Performs a precheck to determine if the app will pass attestation. This requires secure
     * strings to be enabled for the account, although no strings need to be set up. This will
     * likely require network access so may take some time to complete. It may throw ApproovException
     * if the precheck fails or if there is some other problem. ApproovRejectionException is thrown
     * if the app has failed Approov checks or ApproovNetworkException for networking issues where a
     * user initiated retry of the operation should be allowed. An ApproovRejectionException may provide
     * additional information about the cause of the rejection.
     *
     * @throws ApproovException if there was a problem
     */
    public static void precheck() throws ApproovException {
        // try and fetch a non-existent secure string in order to check for a rejection
        Approov.TokenFetchResult approovResults;
        try {
            approovResults = Approov.fetchSecureStringAndWait("precheck-dummy-key", null);
            Log.d(TAG, "precheck: " + approovResults.getStatus().toString());
        }
        catch (IllegalStateException e) {
            throw new ApproovException("IllegalState: " + e.getMessage());
        }
        catch (IllegalArgumentException e) {
            throw new ApproovException("IllegalArgument: " + e.getMessage());
        }

        // process the returned Approov status
        if (approovResults.getStatus() == Approov.TokenFetchStatus.REJECTED)
            // if the request is rejected then we provide a special exception with additional information
            throw new ApproovRejectionException("precheck: " + approovResults.getStatus().toString() + ": " +
                    approovResults.getARC() + " " + approovResults.getRejectionReasons(),
                    approovResults.getARC(), approovResults.getRejectionReasons());
        else if ((approovResults.getStatus() == Approov.TokenFetchStatus.NO_NETWORK) ||
                (approovResults.getStatus() == Approov.TokenFetchStatus.POOR_NETWORK) ||
                (approovResults.getStatus() == Approov.TokenFetchStatus.MITM_DETECTED))
            // we are unable to get the secure string due to network conditions so the request can
            // be retried by the user later
            throw new ApproovNetworkException("precheck: " + approovResults.getStatus().toString());
        else if ((approovResults.getStatus() != Approov.TokenFetchStatus.SUCCESS) &&
                (approovResults.getStatus() != Approov.TokenFetchStatus.UNKNOWN_KEY))
            // we are unable to get the secure string due to a more permanent error
            throw new ApproovException("precheck:" + approovResults.getStatus().toString());
    }

    /**
     * Gets the device ID used by Approov to identify the particular device that the SDK is running on. Note
     * that different Approov apps on the same device will return a different ID. Moreover, the ID may be
     * changed by an uninstall and reinstall of the app.
     *
     * @return String of the device ID
     * @throws ApproovException if there was a problem
     */
    public static String getDeviceID() throws ApproovException {
        try {
            String deviceID = Approov.getDeviceID();
            Log.d(TAG, "getDeviceID: " + deviceID);
            return deviceID;
        }
        catch (IllegalStateException e) {
            throw new ApproovException("IllegalState: " + e.getMessage());
        }
    }

    /**
     * Directly sets the data hash to be included in subsequently fetched Approov tokens. If the hash is
     * different from any previously set value then this will cause the next token fetch operation to
     * fetch a new token with the correct payload data hash. The hash appears in the
     * 'pay' claim of the Approov token as a base64 encoded string of the SHA256 hash of the
     * data. Note that the data is hashed locally and never sent to the Approov cloud service.
     *
     * @param data is the data to be hashed and set in the token
     * @throws ApproovException if there was a problem
     */
    public static void setDataHashInToken(String data) throws ApproovException {
        try {
            Approov.setDataHashInToken(data);
            Log.d(TAG, "setDataHashInToken");
        }
        catch (IllegalStateException e) {
            throw new ApproovException("IllegalState: " + e.getMessage());
        }
        catch (IllegalArgumentException e) {
            throw new ApproovException("IllegalArgument: " + e.getMessage());
        }
    }

    /**
     * Performs an Approov token fetch for the given URL. This should be used in situations where it
     * is not possible to use the networking interception to add the token. This will
     * likely require network access so may take some time to complete. If the attestation fails
     * for any reason then an ApproovException is thrown. This will be ApproovNetworkException for
     * networking issues wher a user initiated retry of the operation should be allowed. Note that
     * the returned token should NEVER be cached by your app, you should call this function when
     * it is needed.
     *
     * @param url is the URL giving the domain for the token fetch
     * @return String of the fetched token
     * @throws ApproovException if there was a problem
     */
    public static String fetchToken(String url) throws ApproovException {
        // fetch the Approov token
        Approov.TokenFetchResult approovResults;
        try {
            approovResults = Approov.fetchApproovTokenAndWait(url);
            Log.d(TAG, "fetchToken: " + approovResults.getStatus().toString());
        }
        catch (IllegalStateException e) {
            throw new ApproovException("IllegalState: " + e.getMessage());
        }
        catch (IllegalArgumentException e) {
            throw new ApproovException("IllegalArgument: " + e.getMessage());
        }

        // process the status
        if ((approovResults.getStatus() == Approov.TokenFetchStatus.NO_NETWORK) ||
                (approovResults.getStatus() == Approov.TokenFetchStatus.POOR_NETWORK) ||
                (approovResults.getStatus() == Approov.TokenFetchStatus.MITM_DETECTED))
            // we are unable to get the token due to network conditions
            throw new ApproovNetworkException("fetchToken: " + approovResults.getStatus().toString());
        else if (approovResults.getStatus() != Approov.TokenFetchStatus.SUCCESS)
            // we are unable to get the token due to a more permanent error
            throw new ApproovException("fetchToken: " + approovResults.getStatus().toString());
        else
            // provide the Approov token result
            return approovResults.getToken();
    }

    /**
     * Gets the signature for the given message. This uses an account specific message signing key that is
     * transmitted to the SDK after a successful fetch if the facility is enabled for the account. Note
     * that if the attestation failed then the signing key provided is actually random so that the
     * signature will be incorrect. An Approov token should always be included in the message
     * being signed and sent alongside this signature to prevent replay attacks. If no signature is
     * available, because there has been no prior fetch or the feature is not enabled, then an
     * ApproovException is thrown.
     *
     * @param message is the message whose content is to be signed
     * @return String of the base64 encoded message signature
     * @throws ApproovException if there was a problem
     */
    public static String getMessageSignature(String message) throws ApproovException {
        try {
            String signature = Approov.getMessageSignature(message);
            Log.d(TAG, "getMessageSignature");
            if (signature == null)
                throw new ApproovException("no signature available");
            return signature;
        }
        catch (IllegalStateException e) {
            throw new ApproovException("IllegalState: " + e.getMessage());
        }
        catch (IllegalArgumentException e) {
            throw new ApproovException("IllegalArgument: " + e.getMessage());
        }
    }

    /**
     * Fetches a secure string with the given key. If newDef is not null then a
     * secure string for the particular app instance may be defined. In this case the
     * new value is returned as the secure string. Use of an empty string for newDef removes
     * the string entry. Note that this call may require network transaction and thus may block
     * for some time, so should not be called from the UI thread. If the attestation fails
     * for any reason then an ApproovException is thrown. This will be ApproovRejectionException
     * if the app has failed Approov checks or ApproovNetworkException for networking issues where
     * a user initiated retry of the operation should be allowed. Note that the returned string
     * should NEVER be cached by your app, you should call this function when it is needed.
     *
     * @param key is the secure string key to be looked up
     * @param newDef is any new definition for the secure string, or null for lookup only
     * @return secure string (should not be cached by your app) or null if it was not defined
     * @throws ApproovException if there was a problem
     */
    public static String fetchSecureString(String key, String newDef) throws ApproovException {
        // determine the type of operation as the values themselves cannot be logged
        String type = "lookup";
        if (newDef != null)
            type = "definition";

        // fetch any secure string keyed by the value, catching any exceptions the SDK might throw
        Approov.TokenFetchResult approovResults;
        try {
            approovResults = Approov.fetchSecureStringAndWait(key, newDef);
            Log.d(TAG, "fetchSecureString " + type + ": " + key + ", " + approovResults.getStatus().toString());
        }
        catch (IllegalStateException e) {
            throw new ApproovException("IllegalState: " + e.getMessage());
        }
        catch (IllegalArgumentException e) {
            throw new ApproovException("IllegalArgument: " + e.getMessage());
        }

        // process the returned Approov status
        if (approovResults.getStatus() == Approov.TokenFetchStatus.REJECTED)
            // if the request is rejected then we provide a special exception with additional information
            throw new ApproovRejectionException("fetchSecureString " + type + " for " + key + ": " +
                    approovResults.getStatus().toString() + ": " + approovResults.getARC() +
                    " " + approovResults.getRejectionReasons(),
                    approovResults.getARC(), approovResults.getRejectionReasons());
        else if ((approovResults.getStatus() == Approov.TokenFetchStatus.NO_NETWORK) ||
                (approovResults.getStatus() == Approov.TokenFetchStatus.POOR_NETWORK) ||
                (approovResults.getStatus() == Approov.TokenFetchStatus.MITM_DETECTED))
            // we are unable to get the secure string due to network conditions so the request can
            // be retried by the user later
            throw new ApproovNetworkException("fetchSecureString " + type + " for " + key + ":" +
                    approovResults.getStatus().toString());
        else if ((approovResults.getStatus() != Approov.TokenFetchStatus.SUCCESS) &&
                (approovResults.getStatus() != Approov.TokenFetchStatus.UNKNOWN_KEY))
            // we are unable to get the secure string due to a more permanent error
            throw new ApproovException("fetchSecureString " + type + " for " + key + ":" +
                    approovResults.getStatus().toString());
        return approovResults.getSecureString();
    }

    /**
     * Fetches a custom JWT with the given payload. Note that this call will require network
     * transaction and thus will block for some time, so should not be called from the UI thread.
     * If the attestation fails for any reason then an IOException is thrown. This will be
     * ApproovRejectionException if the app has failed Approov checks or ApproovNetworkException
     * for networking issues where a user initiated retry of the operation should be allowed.
     *
     * @param payload is the marshaled JSON object for the claims to be included
     * @return custom JWT string
     * @throws ApproovException if there was a problem
     */
    public static String fetchCustomJWT(String payload) throws ApproovException {
        // fetch the custom JWT catching any exceptions the SDK might throw
        Approov.TokenFetchResult approovResults;
        try {
            approovResults = Approov.fetchCustomJWTAndWait(payload);
            Log.d(TAG, "fetchCustomJWT: " + approovResults.getStatus().toString());
        }
        catch (IllegalStateException e) {
            throw new ApproovException("IllegalState: " + e.getMessage());
        }
        catch (IllegalArgumentException e) {
            throw new ApproovException("IllegalArgument: " + e.getMessage());
        }

        // process the returned Approov status
        if (approovResults.getStatus() == Approov.TokenFetchStatus.REJECTED)
            // if the request is rejected then we provide a special exception with additional information
            throw new ApproovRejectionException("fetchCustomJWT: "+ approovResults.getStatus().toString() + ": " +
                    approovResults.getARC() +  " " + approovResults.getRejectionReasons(),
                    approovResults.getARC(), approovResults.getRejectionReasons());
        else if ((approovResults.getStatus() == Approov.TokenFetchStatus.NO_NETWORK) ||
                (approovResults.getStatus() == Approov.TokenFetchStatus.POOR_NETWORK) ||
                (approovResults.getStatus() == Approov.TokenFetchStatus.MITM_DETECTED))
            // we are unable to get the custom JWT due to network conditions so the request can
            // be retried by the user later
            throw new ApproovNetworkException("fetchCustomJWT: " + approovResults.getStatus().toString());
        else if (approovResults.getStatus() != Approov.TokenFetchStatus.SUCCESS)
            // we are unable to get the custom JWT due to a more permanent error
            throw new ApproovException("fetchCustomJWT: " + approovResults.getStatus().toString());
        return approovResults.getToken();
    }

    /**
     * Clear the retrofit map to force a new build on the next request. This must be done if there
     * are any pinning changes.
     */
    public static synchronized void clearRetrofitMap() {
        Log.d(TAG, "RetrofitMap cleared");
        retrofitMap = new HashMap<>();
    }

    /**
     * Sets the OkHttpClient.Builder to be used for constructing the OkHttpClients used in the
     * Retrofit instances. This allows a custom configuration to be set, with additional interceptors
     * and properties. This clears the cached Retrofit client instances so should only be called when
     * an actual builder change is required.
     *
     * @param builder is the OkHttpClient.Builder to be used in Retrofit instances
     */
    public static synchronized void setOkHttpClientBuilder(OkHttpClient.Builder builder) {
        okHttpBuilder = builder;
        retrofitMap = new HashMap<>();
    }

    /**
     * Gets a Retrofit instance that enables the Approov service. The builder for Retrofit should
     * be provided to allow its customization. This simply adds the underlying OkHttpClient to be
     * used. Approov tokens are added in headers to requests, and connections are also pinned.
     * Retrofit instances are added lazily on demand but are cached if there is no change.
     * lazily on demand but is cached if there are no changes. Note that once constructed and
     * passed to this method, Retrofit builder instances should not be changed further. If any
     * changes are required then a new builder should be constructed. Use "setOkHttpClientBuilder"
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
            if (isInitialized) {
                // build the pinning configuration
                CertificatePinner.Builder pinBuilder = new CertificatePinner.Builder();
                Map<String, List<String>> allPins = Approov.getPins("public-key-sha256");
                for (Map.Entry<String, List<String>> entry: allPins.entrySet()) {
                    String domain = entry.getKey();
                    if (!domain.equals("*")) {
                        // the * domain is for managed trust roots and should
                        // not be added directly
                        List<String> pins = entry.getValue();

                        // if there are no pins then we try and use any managed trust roots
                        if (pins.isEmpty() && (allPins.get("*") != null))
                            pins = allPins.get("*");

                        // add the required pins for the domain
                        for (String pin: pins)
                            pinBuilder = pinBuilder.add(domain, "sha256/" + pin);
                    }
                }

                // remove any existing ApproovTokenInterceptor from the builder
                List<Interceptor> interceptors = okHttpBuilder.interceptors();
                Iterator<Interceptor> iter = interceptors.iterator();
                while (iter.hasNext()) {
                    Interceptor interceptor = iter.next();
                    if (interceptor instanceof ApproovTokenInterceptor)
                        iter.remove();
                }

                // build the OkHttpClient with the correct pins preset and Approov interceptor
                Log.d(TAG, "Building new Approov OkHttpClient");
                okHttpClient = okHttpBuilder.certificatePinner(pinBuilder.build())
                        .addInterceptor(new ApproovTokenInterceptor(approovTokenHeader,
                                approovTokenPrefix, bindingHeader, proceedOnNetworkFail,
                                substitutionHeaders, substitutionQueryParams, exclusionURLRegexs)).build();
            } else {
                // if the Approov SDK could not be initialized then we can't pin or add Approov tokens
                Log.e(TAG, "Cannot build Approov OkHttpClient as not initialized");
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
                (result.getStatus() == Approov.TokenFetchStatus.UNKNOWN_URL))
            Log.d(TAG, "Prefetch success");
        else
            Log.e(TAG, "Prefetch failure: " + result.getStatus().toString());
    }
}

// interceptor to add Approov tokens or substitute headers and query string parameters
class ApproovTokenInterceptor implements Interceptor {
    // logging tag
    private final static String TAG = "ApproovInterceptor";

    // the name of the header to be added to hold the Approov token
    private String approovTokenHeader;

    // prefix to be used for the Approov token
    private String approovTokenPrefix;

    // any binding header for Approov token binding, or null if none
    private String bindingHeader;

    // true if the interceptor should proceed on network failures and not add an Approov token
    private boolean proceedOnNetworkFail;

    // map of headers that should have their values substituted for secure strings, mapped to their
    // required prefixes
    private Map<String, String> substitutionHeaders;

    // set of query parameters that may be substituted, specified by the key name, mapped to their regex patterns
    private Map<String, Pattern> substitutionQueryParams;

    // set of URL regexs that should be excluded from any Approov protection, mapped to the compiled Pattern
    private Map<String, Pattern> exclusionURLRegexs;

    /**
     * Constructs an new interceptor that adds Approov tokens.
     *
     * @param approovTokenHeader is the name of the header to be used for the Approov token
     * @param approovTokenPrefix is the prefix string to be used with the Approov token
     * @param bindingHeader is any token binding header to use or null otherwise
     * @param proceedOnNetworkFail is true the interceptor should proceed on Approov networking failures
     * @param substitutionHeaders is the map of secure string substitution headers mapped to any required prefixes
     * @param substitutionQueryParams is the set of query parameter key names subject to substitution
     * @param exclusionURLRegexs specifies regexs of URLs that should be excluded
     */
    public ApproovTokenInterceptor(String approovTokenHeader, String approovTokenPrefix,
                                   String bindingHeader, boolean proceedOnNetworkFail, Map<String, String> substitutionHeaders,
                                   Set<String> substitutionQueryParams, Map<String, Pattern> exclusionURLRegexs) {
        this.approovTokenHeader = approovTokenHeader;
        this.approovTokenPrefix = approovTokenPrefix;
        this.bindingHeader = bindingHeader;
        this.proceedOnNetworkFail = proceedOnNetworkFail;
        this.substitutionHeaders = new HashMap<>(substitutionHeaders);
        this.substitutionQueryParams = new HashMap<>();
        for (String key: substitutionQueryParams) {
            try {
                Pattern pattern = Pattern.compile("[\\?&]"+key+"=([^&;]+)");
                this.substitutionQueryParams.put(key, pattern);
            }
            catch (PatternSyntaxException e) {
                Log.e(TAG, "addSubstitutionQueryParam " + key + " error: " + e.getMessage());
            }
        }
        this.exclusionURLRegexs = new HashMap<>(exclusionURLRegexs);
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        // check if the URL matches one of the exclusion regexs and just proceed
        Request request = chain.request();
        String url = request.url().toString();
        for (Pattern pattern: exclusionURLRegexs.values()) {
            Matcher matcher = pattern.matcher(url);
            if (matcher.find())
                return chain.proceed(request);
        }

        // update the data hash based on any token binding header
        if ((bindingHeader != null) && request.headers().names().contains(bindingHeader))
            Approov.setDataHashInToken(request.header(bindingHeader));

        // request an Approov token for the domain
        String host = request.url().host();
        Approov.TokenFetchResult approovResults = Approov.fetchApproovTokenAndWait(host);

        // provide information about the obtained token or error (note "approov token -check" can
        // be used to check the validity of the token and if you use token annotations they
        // will appear here to determine why a request is being rejected)
        Log.d(TAG, "Token for " + host + ": " + approovResults.getLoggableToken());

        // if there is any dynamic configuration update then we need to force a pin update
        if (approovResults.isConfigChanged()) {
            Approov.fetchConfig();
            ApproovService.clearRetrofitMap();
        }

        // do not proceed if the Approov pins need to be updated (this will be cleared by using getRetrofit
        // but will persist if the app fails to call this regularly)
        if (approovResults.isForceApplyPins()) {
            ApproovService.clearRetrofitMap();
            throw new ApproovNetworkException("Approov pins need to be updated");
        }

        // check the status of Approov token fetch
        if (approovResults.getStatus() == Approov.TokenFetchStatus.SUCCESS)
            // we successfully obtained a token so add it to the header for the request
            request = request.newBuilder().header(approovTokenHeader, approovTokenPrefix + approovResults.getToken()).build();
        else if ((approovResults.getStatus() == Approov.TokenFetchStatus.NO_NETWORK) ||
                 (approovResults.getStatus() == Approov.TokenFetchStatus.POOR_NETWORK) ||
                 (approovResults.getStatus() == Approov.TokenFetchStatus.MITM_DETECTED)) {
            // we are unable to get an Approov token due to network conditions so the request can
            // be retried by the user later - unless this is overridden
            if (!proceedOnNetworkFail)
                throw new ApproovNetworkException("Approov token fetch for " + host + ": " + approovResults.getStatus().toString());
        }
        else if ((approovResults.getStatus() != Approov.TokenFetchStatus.NO_APPROOV_SERVICE) &&
                 (approovResults.getStatus() != Approov.TokenFetchStatus.UNKNOWN_URL) &&
                 (approovResults.getStatus() != Approov.TokenFetchStatus.UNPROTECTED_URL))
            // we have failed to get an Approov token with a more serious permanent error
            throw new ApproovException("Approov token fetch for " + host + ": " + approovResults.getStatus().toString());

        // we only continue additional processing if we had a valid status from Approov, to prevent additional delays
        // by trying to fetch from Approov again and this also protects against header substitutions in domains not
        // protected by Approov and therefore potential subject to a MitM
        if ((approovResults.getStatus() != Approov.TokenFetchStatus.SUCCESS) &&
                (approovResults.getStatus() != Approov.TokenFetchStatus.UNPROTECTED_URL))
            return chain.proceed(request);

        // we now deal with any header substitutions, which may require further fetches but these
        // should be using cached results
        for (Map.Entry<String, String> entry: substitutionHeaders.entrySet()) {
            String header = entry.getKey();
            String prefix = entry.getValue();
            String value = request.header(header);
            if ((value != null) && value.startsWith(prefix) && (value.length() > prefix.length())) {
                approovResults = Approov.fetchSecureStringAndWait(value.substring(prefix.length()), null);
                Log.d(TAG, "Substituting header: " + header + ", " + approovResults.getStatus().toString());
                if (approovResults.getStatus() == Approov.TokenFetchStatus.SUCCESS) {
                    // substitute the header
                    request = request.newBuilder().header(header, prefix + approovResults.getSecureString()).build();
                }
                else if (approovResults.getStatus() == Approov.TokenFetchStatus.REJECTED)
                    // if the request is rejected then we provide a special exception with additional information
                    throw new ApproovRejectionException("Header substitution for " + header + ": " +
                            approovResults.getStatus().toString() + ": " + approovResults.getARC() +
                            " " + approovResults.getRejectionReasons(),
                            approovResults.getARC(), approovResults.getRejectionReasons());
                else if ((approovResults.getStatus() == Approov.TokenFetchStatus.NO_NETWORK) ||
                        (approovResults.getStatus() == Approov.TokenFetchStatus.POOR_NETWORK) ||
                        (approovResults.getStatus() == Approov.TokenFetchStatus.MITM_DETECTED)) {
                    // we are unable to get the secure string due to network conditions so the request can
                    // be retried by the user later - unless this is overridden
                    if (!proceedOnNetworkFail)
                        throw new ApproovNetworkException("Header substitution for " + header + ": " +
                                approovResults.getStatus().toString());
                }
                else if (approovResults.getStatus() != Approov.TokenFetchStatus.UNKNOWN_KEY)
                    // we have failed to get a secure string with a more serious permanent error
                    throw new ApproovException("Header substitution for " + header + ": " +
                            approovResults.getStatus().toString());
            }
        }

        // we now deal with any query parameter substitutions, which may require further fetches but these
        // should be using cached results
        String currentURL = request.url().toString();
        for (Map.Entry<String, Pattern> entry: substitutionQueryParams.entrySet()) {
            String queryKey = entry.getKey();
            Pattern pattern = entry.getValue();
            Matcher matcher = pattern.matcher(currentURL);
            if (matcher.find()) {
                // we have found an occurrence of the query parameter to be replaced so we look up the existing
                // value as a key for a secure string
                String queryValue = matcher.group(1);
                approovResults = Approov.fetchSecureStringAndWait(queryValue, null);
                Log.d(TAG, "Substituting query parameter: " + queryKey + ", " + approovResults.getStatus().toString());
                if (approovResults.getStatus() == Approov.TokenFetchStatus.SUCCESS) {
                    // substitute the query parameter
                    currentURL = new StringBuilder(currentURL).replace(matcher.start(1),
                            matcher.end(1), approovResults.getSecureString()).toString();
                    request = request.newBuilder().url(currentURL).build();
                }
                else if (approovResults.getStatus() == Approov.TokenFetchStatus.REJECTED)
                    // if the request is rejected then we provide a special exception with additional information
                    throw new ApproovRejectionException("Query parameter substitution for " + queryKey + ": " +
                            approovResults.getStatus().toString() + ": " + approovResults.getARC() +
                            " " + approovResults.getRejectionReasons(),
                            approovResults.getARC(), approovResults.getRejectionReasons());
                else if ((approovResults.getStatus() == Approov.TokenFetchStatus.NO_NETWORK) ||
                        (approovResults.getStatus() == Approov.TokenFetchStatus.POOR_NETWORK) ||
                        (approovResults.getStatus() == Approov.TokenFetchStatus.MITM_DETECTED)) {
                    // we are unable to get the secure string due to network conditions so the request can
                    // be retried by the user later - unless this is overridden
                    if (!proceedOnNetworkFail)
                        throw new ApproovNetworkException("Query parameter substitution for " + queryKey + ": " +
                                approovResults.getStatus().toString());
                }
                else if (approovResults.getStatus() != Approov.TokenFetchStatus.UNKNOWN_KEY)
                    // we have failed to get a secure string with a more serious permanent error
                    throw new ApproovException("Query parameter substitution for " + queryKey + ": " +
                            approovResults.getStatus().toString());
            }
        }

        // proceed with the rest of the chain
        return chain.proceed(request);
    }
}
