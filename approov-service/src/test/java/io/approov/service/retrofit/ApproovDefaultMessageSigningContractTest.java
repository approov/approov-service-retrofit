package io.approov.service.retrofit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.approov.util.sig.ComponentProvider;
import io.approov.util.sig.SignatureParameters;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

import org.junit.Test;

public class ApproovDefaultMessageSigningContractTest {

    private static final MediaType APPLICATION_JSON = MediaType.get("application/json");

    private static final class RecordingSigner extends ApproovDefaultMessageSigning {
        private String installSignatureBase64 = "";
        private String accountSignatureBase64 = "";
        private ApproovException installError;
        private String lastInstallMessage;
        private String lastAccountMessage;

        @Override
        protected String getInstallMessageSignature(String message) throws ApproovException {
            lastInstallMessage = message;
            if (installError != null) {
                throw installError;
            }
            return installSignatureBase64;
        }

        @Override
        protected String getAccountMessageSignature(String message) {
            lastAccountMessage = message;
            return accountSignatureBase64;
        }

        @Override
        protected byte[] decodeBase64(String base64) {
            return Base64.getDecoder().decode(base64);
        }
    }

    private static final class UnsupportedAlgorithmFactory
            extends ApproovDefaultMessageSigning.SignatureParametersFactory {
        @Override
        protected SignatureParameters buildSignatureParameters(
                ApproovDefaultMessageSigning.OkHttpComponentProvider provider,
                ApproovRequestMutations changes) {
            return new SignatureParameters()
                    .addComponentIdentifier(ComponentProvider.DC_METHOD)
                    .addComponentIdentifier(ComponentProvider.DC_TARGET_URI)
                    .addComponentIdentifier(changes.getTokenHeaderKey())
                    .setAlg("unsupported-alg");
        }
    }

    private static String derEncodedInstallSignature() {
        byte[] der = new byte[] { 0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x02 };
        return Base64.getEncoder().encodeToString(der);
    }

    private static Request signedRequestFixture() {
        return new Request.Builder()
                .url("https://api.example.com/reply")
                .post(RequestBody.create(APPLICATION_JSON, "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8)))
                .header("Approov-Token", "Bearer jwt-token")
                .header("Approov-TraceID", "trace-123")
                .header("Authorization", "Bearer auth-token")
                .header("Content-Type", "application/json")
                .header("Content-Digest", "stale-digest")
                .header("Signature", "stale-signature")
                .header("Signature-Input", "stale-input")
                .header("Signature-Base-Digest", "stale-base-digest")
                .build();
    }

    private static Request unsignedRequestFixture() {
        return signedRequestFixture().newBuilder()
                .removeHeader("Content-Digest")
                .removeHeader("Signature")
                .removeHeader("Signature-Input")
                .removeHeader("Signature-Base-Digest")
                .build();
    }

    private static Request requestWithoutBodyFixture() {
        return new Request.Builder()
                .url("https://api.example.com/reply")
                .get()
                .header("Approov-Token", "Bearer jwt-token")
                .header("Approov-TraceID", "trace-123")
                .header("Authorization", "Bearer auth-token")
                .build();
    }

    private static ApproovRequestMutations defaultChanges() {
        ApproovRequestMutations changes = new ApproovRequestMutations();
        changes.setTokenHeaderKey("Approov-Token");
        changes.setTraceIDHeaderKey("Approov-TraceID");
        return changes;
    }

    private static void assertUnsignedWithoutSignatureHeaders(Request original, Request processed) {
        assertSame(original, processed);
        assertEquals("Bearer jwt-token", processed.header("Approov-Token"));
        assertEquals("trace-123", processed.header("Approov-TraceID"));
        assertNull(processed.header("Content-Digest"));
        assertNull(processed.header("Signature"));
        assertNull(processed.header("Signature-Input"));
        assertNull(processed.header("Signature-Base-Digest"));
    }

    @Test
    public void defaultSigningAddsDigestAndSignatureHeaders() throws Exception {
        RecordingSigner signer = new RecordingSigner();
        signer.setDefaultFactory(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory());
        signer.installSignatureBase64 = derEncodedInstallSignature();

        Request signed = signer.processedRequest(signedRequestFixture(), defaultChanges());

        assertTrue(signer.lastInstallMessage.contains("\"@method\""));
        assertTrue(signer.lastInstallMessage.contains("\"@target-uri\""));
        assertTrue(signer.lastInstallMessage.contains("\"approov-token\""));
        assertTrue(signer.lastInstallMessage.contains("\"approov-traceid\""));
        assertTrue(signer.lastInstallMessage.contains("\"content-digest\""));
        assertEquals("Bearer jwt-token", signed.header("Approov-Token"));
        assertEquals("trace-123", signed.header("Approov-TraceID"));
        assertTrue(signed.header("Content-Digest").contains("sha-256=:"));
        assertTrue(signed.header("Signature").contains("install=:"));
        assertTrue(signed.header("Signature-Input").contains("install=("));
        assertFalse(signed.header("Signature").contains("stale-signature"));
        assertFalse(signed.header("Signature-Input").contains("stale-input"));
        assertNull(signed.header("Signature-Base-Digest"));
        assertEquals(1, signed.headers("Content-Digest").size());
        assertEquals(1, signed.headers("Signature").size());
        assertEquals(1, signed.headers("Signature-Input").size());
    }

    @Test
    public void signingTwiceReplacesExistingSignatureAndDigestHeaders() throws Exception {
        RecordingSigner signer = new RecordingSigner();
        signer.setDefaultFactory(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory());
        signer.installSignatureBase64 = derEncodedInstallSignature();

        Request signedOnce = signer.processedRequest(signedRequestFixture(), defaultChanges());
        Request signedTwice = signer.processedRequest(signedOnce, defaultChanges());

        assertEquals(1, signedTwice.headers("Content-Digest").size());
        assertEquals(1, signedTwice.headers("Signature").size());
        assertEquals(1, signedTwice.headers("Signature-Input").size());
        assertFalse(signedTwice.header("Signature").contains("stale-signature"));
        assertFalse(signedTwice.header("Signature-Input").contains("stale-input"));
        assertNull(signedTwice.header("Signature-Base-Digest"));
    }

    @Test
    public void signingWithoutAnApproovTokenMutationLeavesTheRequestUnchanged() throws Exception {
        RecordingSigner signer = new RecordingSigner();
        signer.setDefaultFactory(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory());
        signer.installSignatureBase64 = derEncodedInstallSignature();
        Request request = signedRequestFixture();

        Request signed = signer.processedRequest(request, new ApproovRequestMutations());

        assertSame(request, signed);
        assertEquals("stale-signature", signed.header("Signature"));
        assertEquals("stale-input", signed.header("Signature-Input"));
        assertEquals("stale-digest", signed.header("Content-Digest"));
    }

    @Test
    public void signingSkipsGracefullyWhenInstallSigningIsUnavailable() throws Exception {
        RecordingSigner signer = new RecordingSigner();
        signer.setDefaultFactory(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory());
        signer.installError = new ApproovException("no device signature available");
        Request request = unsignedRequestFixture();

        Request signed = signer.processedRequest(request, defaultChanges());

        assertUnsignedWithoutSignatureHeaders(request, signed);
    }

    @Test
    public void accountSigningUsesTheAccountSignatureFlow() throws Exception {
        RecordingSigner signer = new RecordingSigner();
        signer.setDefaultFactory(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
                .setUseAccountMessageSigning());
        signer.accountSignatureBase64 = Base64.getEncoder().encodeToString("account-signature".getBytes(StandardCharsets.UTF_8));

        Request signed = signer.processedRequest(signedRequestFixture(), defaultChanges());

        assertTrue(signed.header("Signature").contains("account=:"));
        assertTrue(signed.header("Signature-Input").contains("account=("));
        assertNull(signer.lastInstallMessage);
        assertTrue(signer.lastAccountMessage.contains("\"approov-token\""));
    }

    @Test
    public void accountSigningSkipsGracefullyWhenSignatureBase64IsInvalid() throws Exception {
        RecordingSigner signer = new RecordingSigner();
        signer.setDefaultFactory(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
                .setUseAccountMessageSigning());
        signer.accountSignatureBase64 = "not-base64";
        Request request = unsignedRequestFixture();

        Request signed = signer.processedRequest(request, defaultChanges());

        assertTrue(signer.lastAccountMessage.contains("\"approov-token\""));
        assertUnsignedWithoutSignatureHeaders(request, signed);
    }

    @Test
    public void installSigningSkipsGracefullyWhenSignatureBase64IsInvalid() throws Exception {
        RecordingSigner signer = new RecordingSigner();
        signer.setDefaultFactory(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory());
        signer.installSignatureBase64 = "not-base64";
        Request request = unsignedRequestFixture();

        Request signed = signer.processedRequest(request, defaultChanges());

        assertTrue(signer.lastInstallMessage.contains("\"approov-token\""));
        assertUnsignedWithoutSignatureHeaders(request, signed);
    }

    @Test
    public void installSigningSkipsGracefullyWhenDerSignatureIsMalformed() throws Exception {
        RecordingSigner signer = new RecordingSigner();
        signer.setDefaultFactory(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory());
        signer.installSignatureBase64 = Base64.getEncoder().encodeToString(
                new byte[] { 0x31, 0x00 });
        Request request = unsignedRequestFixture();

        Request signed = signer.processedRequest(request, defaultChanges());

        assertTrue(signer.lastInstallMessage.contains("\"approov-token\""));
        assertUnsignedWithoutSignatureHeaders(request, signed);
    }

    @Test
    public void installSigningSkipsGracefullyWhenDerSignatureIsTruncated() throws Exception {
        RecordingSigner signer = new RecordingSigner();
        signer.setDefaultFactory(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory());
        signer.installSignatureBase64 = Base64.getEncoder().encodeToString(
                new byte[] { 0x30, 0x06, 0x02 });
        Request request = unsignedRequestFixture();

        Request signed = signer.processedRequest(request, defaultChanges());

        assertTrue(signer.lastInstallMessage.contains("\"approov-token\""));
        assertUnsignedWithoutSignatureHeaders(request, signed);
    }

    @Test
    public void signingSkipsGracefullyWhenSignatureBaseCannotBeBuilt() throws Exception {
        RecordingSigner signer = new RecordingSigner();
        SignatureParameters missingRequiredHeader = new SignatureParameters()
                .addComponentIdentifier("X-Missing-Required-Header");
        signer.setDefaultFactory(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory(missingRequiredHeader)
                .setUseAccountMessageSigning()
                .setAddApproovTokenHeader(false)
                .setAddApproovTraceIDHeader(false)
                .setBodyDigestConfig(null, false));
        signer.accountSignatureBase64 = Base64.getEncoder().encodeToString(
                "account-signature".getBytes(StandardCharsets.UTF_8));
        Request request = unsignedRequestFixture();

        Request signed = signer.processedRequest(request, defaultChanges());

        assertNull(signer.lastAccountMessage);
        assertUnsignedWithoutSignatureHeaders(request, signed);
    }

    @Test
    public void requiredBodyDigestFailureIsFailClosed() {
        RecordingSigner signer = new RecordingSigner();
        signer.setDefaultFactory(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
                .setBodyDigestConfig(ApproovDefaultMessageSigning.DIGEST_SHA256, true));

        assertThrows(ApproovDefaultMessageSigning.RequiredBodyDigestException.class,
                () -> signer.processedRequest(requestWithoutBodyFixture(), defaultChanges()));
        assertNull(signer.lastInstallMessage);
    }

    @Test
    public void unsupportedSigningAlgorithmIsFailClosed() {
        RecordingSigner signer = new RecordingSigner();
        signer.setDefaultFactory(new UnsupportedAlgorithmFactory());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> signer.processedRequest(unsignedRequestFixture(), defaultChanges()));

        assertTrue(error.getMessage().contains("Unsupported algorithm identifier"));
        assertNull(signer.lastInstallMessage);
        assertNull(signer.lastAccountMessage);
    }
}
