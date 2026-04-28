package io.approov.service.retrofit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;

import com.criticalblue.approovsdk.Approov;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import retrofit2.Retrofit;

import org.mockito.MockedStatic;

final class ApproovTestSupport {

    private static final MediaType TEXT_PLAIN = MediaType.get("text/plain");

    private ApproovTestSupport() {
    }

    static void resetApproovServiceState() {
        setStaticField("isInitialized", false);
        setStaticField("configString", null);
        setStaticField("proceedOnNetworkFail", false);
        setStaticField("useApproovStatusIfNoToken", false);
        setStaticField("pinningInterceptor", null);
        setStaticField("okHttpBuilder", null);
        setStaticField("approovTokenHeader", null);
        setStaticField("approovTokenPrefix", null);
        setStaticField("approovTraceIDHeader", null);
        setStaticField("bindingHeader", null);
        setStaticField("serviceMutator", ApproovServiceMutator.DEFAULT);
        setStaticField("substitutionHeaders", new HashMap<String, String>());
        setStaticField("substitutionQueryParams", new HashMap<String, Pattern>());
        setStaticField("exclusionURLRegexs", new HashMap<String, Pattern>());
        setStaticField("retrofitMap", new HashMap<Retrofit.Builder, Retrofit>());
        try {
            setStaticField("cachedFailureKey", null);
            setStaticField("cachedFailureResult", null);
            setStaticField("cachedFailureTimeMs", 0L);
            setStaticField("failureCacheTtlMs", 500L);
        } catch (Throwable e) {
            // ignore
        }
    }

    static void initializeApproovService(MockedStatic<Approov> approov) {
        resetApproovServiceState();
        approov.when(() -> Approov.getPins("public-key-sha256")).thenReturn(new HashMap<>());
        Context context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        ApproovService.initialize(context, "dummy-config", "reinit-tests");
    }

    static Approov.TokenFetchResult tokenResult(Approov.TokenFetchStatus status) {
        return tokenResult(status, "", "", "", false);
    }

    static Approov.TokenFetchResult tokenResult(
            Approov.TokenFetchStatus status,
            String token,
            String secureString,
            String traceID,
            boolean configChanged
    ) {
        Approov.TokenFetchResult result = mock(Approov.TokenFetchResult.class);
        when(result.getStatus()).thenReturn(status);
        when(result.getToken()).thenReturn(token);
        when(result.getSecureString()).thenReturn(secureString);
        when(result.getTraceID()).thenReturn(traceID);
        when(result.getLoggableToken()).thenReturn(token);
        when(result.getARC()).thenReturn("ARC123");
        when(result.getRejectionReasons()).thenReturn("hooked,rooted");
        when(result.isConfigChanged()).thenReturn(configChanged);
        return result;
    }

    static Interceptor.Chain interceptorChain(Request request) throws Exception {
        Interceptor.Chain chain = mock(Interceptor.Chain.class);
        when(chain.request()).thenReturn(request);
        when(chain.connection()).thenReturn(null);
        when(chain.proceed(any(Request.class))).thenAnswer(invocation -> successResponse(invocation.getArgument(0)));
        return chain;
    }

    static Response successResponse(Request request) {
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create("ok", TEXT_PLAIN))
                .build();
    }

    static OkHttpClient retrofitClient(Retrofit retrofit) {
        return (OkHttpClient) retrofit.callFactory();
    }

    @SuppressWarnings("unchecked")
    static <T> T getStaticField(String name, Class<T> type) {
        try {
            Field field = ApproovService.class.getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read field " + name, e);
        }
    }

    private static void setStaticField(String name, Object value) {
        try {
            Field field = ApproovService.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to reset field " + name, e);
        }
    }
}
