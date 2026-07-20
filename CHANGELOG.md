# Changelog

All notable changes to this package will be documented in this file.

The format is based on Keep a Changelog and this project adheres to Semantic Versioning.


## [3.5.8] - 2026-06-24

### Added
- Stale protection refresh: a new network interceptor detects requests that were held between Approov protection being applied and actual transmission (for example by a device deep sleep or doze period, or an app-level request queueing/backoff mechanism) and refreshes the Approov token and any message signature immediately before the request is sent, instead of transmitting expired credentials. Since it operates per network attempt it also refreshes protection on OkHttp generated retries and redirect followups. Configurable via `ApproovService.setStaleProtectionRefreshPeriod()` (default 3000ms, `<=0` disables). Because a refresh reinvokes the mutator's `handleInterceptorProcessedRequest` callback, it is gated on the new `ApproovServiceMutator.supportsProtectionRefresh()` capability: the default mutator and `ApproovDefaultMessageSigning` support it, while custom mutator implementations are never reinvoked unless they opt in.

### Fixed
- Message signing now conforms to the fail-open policy (approov/core-project-approov#564). Every signature-build failure — building the signature parameters or signature base, retrieving or base64-decoding the install/account signature, decoding the ES256 ASN.1/DER signature, or serializing the signature headers — now logs and proceeds **unsigned** instead of aborting the request, since the backend is the enforcement point for message signatures. Only a **required body digest** that cannot be generated (now a dedicated `RequiredBodyDigestException`) and an **unsupported algorithm** still fail closed.

### Changed
- Android build migrated from the unmaintained `com.github.johnrengelman.shadow` 8.1.1 plugin to the maintained fork `com.gradleup.shadow` 8.3.11 for Gradle 9 compatibility (Gradle 9 removed `FileCopyDetails.mode`, making the old plugin fail with a `MissingPropertyException`). Shaded BouncyCastle jar verified byte-identical; minimum supported Gradle remains 8.3.
- Raised the install/account message-signature skip logs for production visibility: genuine failures (signature unavailable, malformed base64/DER, serialization errors) log at error, while routine "signature is empty" skips log at warn to avoid alerting noise.
- Maven Central publishing now defaults to `PUBLISHING_TYPE=AUTOMATIC` on a pushed release tag; tagging the main branch stays manual, so the tag push is the release decision point.

### Documentation
- GitHub-style README: added status badges and a full `initialize` failure-handling example (Java + Kotlin) that wraps initialization in try/catch, logs the Approov device ID alongside an app-generated session/correlation id, and falls back to unprotected bypass mode on failure.
- Documented on `ApproovService.initialize` that initialization must succeed before any protected request (call it synchronously before building the client), with the same correlation and failure-handling guidance.


## [3.5.7] - 2026-05-19

### Added
- Consumer ProGuard rules (`consumer-rules.pro`) to automatically preserve native SDK interfaces and internal cryptography bindings.

### Changed
- Shaded and relocated the BouncyCastle dependency (`io.approov.internal.retrofit.bouncycastle`) to prevent version collisions for consuming applications.
- Removed the transitive `org.bouncycastle:bcprov-jdk15to18` dependency from `pom.xml`.
- Simplified `initialize` — removed the service-layer re-initialization guards (same-config short-circuit, `reinit` comment check). The service layer forwards non-empty config directly to the platform SDK and resets its own state only after the SDK confirms success. The SDK returns `false` if already initialized with the same config (service layer logs and continues), or throws `IllegalStateException` for a different config (service layer re-throws, preserving existing state).
- `initialize` now logs a warning when a re-initialization discards previously applied service-layer configuration (token headers, substitutions, exclusions, mutator, flags), and the reset contract plus full state/input behavior matrix is now documented on `initialize` and in REFERENCE.md.

### Fixed
- `initialize` now explicitly throws `IllegalArgumentException` when `config` is `null`, with a clear message directing callers to pass `""` for bypass mode. Passing `null` previously caused a silent coercion to `""` which masked caller errors.
- The 2-arg `initialize(context, config)` overload now correctly passes `null` (not `""`) as the comment to the native SDK, preventing unexpected re-initialization mismatches on subsequent calls.

## [3.5.6] - 2026-04-21

### Added
- Thread-safe failure mode caching for the interceptor path. When the platform SDK returns a failure status (`NO_NETWORK`, `POOR_NETWORK`, `MITM_DETECTED`, `NO_APPROOV_SERVICE`), the result is cached for 0.5 seconds. Subsequent requests within that window return the cached failure instantly, avoiding redundant ~1s SDK calls. Success is never cached.
- Added `ApproovService.setFailureCacheTtlMs()` to customize the caching duration of failure statuses in milliseconds.
- Added `ApproovService.isInitialized()` to expose the service-layer initialization state.
- Integrated a localized testing framework for comprehensive service layer verification.
- Added extensive test coverage for core service flows, including initialization, token management, and request mutation.

### Changed
- Initializing with an empty config string now keeps the service layer initialized while forwarding requests without Approov processing.
- Initializing first with an empty config string and later with a valid non-empty config string now enables Approov for newly obtained Retrofit instances instead of being rejected as a different-configuration initialization.
- Tightened the initialization guard so only actual `reinit...` comments bypass same-config enforcement.

### Fixed
- Added explicit cross-service-layer initialization handling so a benign same-config already-initialized native SDK outcome is tolerated, while real different-configuration failures still surface as initialization errors.
- Updated the build manifest to support flexible dependency resolution for verification suites.

## [3.5.5] - 2026-03-25

### Added
- ApproovServiceMutator protocol with default behavior to centralize decision points in the service flow.
- Mutator hooks for precheck, token fetch, secure string fetch, custom JWT fetch, interceptor decisions, and pinning.
- REFERENCE.md & CHANGELOG.md & USAGE.md
- Added `setUseApproovStatusIfNoToken` to allow using status as token value when token is missing.
### Changed
- ApproovService now routes decision logic through the service mutator and exposes set/get APIs.
- Pinning logic is now applied via `ApproovPinningInterceptor` which checks `ApproovServiceMutator.handlePinningShouldProcessRequest`.
- Update version to 3.5.5
### Fixed
- Prevented exceptions when key-pair generation fails. The service now logs an error and continues without the install message signature, allowing the backend to decide whether to reject the request. (inherited from shared sdk update)
- Memory leak fix in pinned handshake cache using LinkedHashSet.
- Initialized the Retrofit instance cache statically to avoid `NullPointerException` when `setOkHttpClientBuilder` or `getRetrofit` are called before `initialize`.
### Deprecated
- ApproovInterceptorExtensions in favor of ApproovServiceMutator.
- setProceedOnNetworkFail() and getProceedOnNetworkFail() in favor of setServiceMutator.
- prefetch() is obsolete and is now a no-op. The underlying Approov SDK manages prefetching automatically.
