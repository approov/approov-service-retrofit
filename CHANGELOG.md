# Changelog

All notable changes to this package will be documented in this file.

The format is based on Keep a Changelog and this project adheres to Semantic Versioning.

## [3.7.0] - YYYY-MM-DD

Entries marked **Breaking** need a source change. See [Upgrading from 3.5.x](README.md#upgrading-from-35x).

### Changed
- A missing install or account signature is logged as a warning once per process, and a request sent unsigned as an error once per process. Later occurrences are logged at debug level.
- A `Retrofit` instance or client that you get before `initialize` sends its requests without Approov protection until `initialize` runs, and protects them from then on. In 3.5.x it stayed unprotected. `initialize` and `setOkHttpClientBuilder` discard the kept `Retrofit` instances, so get them again afterwards. See [getRetrofit](REFERENCE.md#getretrofit).
- BouncyCastle is replaced by Google Tink, bundled in the AAR and relocated, so it cannot clash with your own dependencies and the published POM adds none.
- **Breaking:** The message signing helper classes moved from `io.approov.util.http.sfv` and `io.approov.util.sig` to `io.approov.util.retrofit.http.sfv` and `io.approov.util.retrofit.sig`. Update imports of classes such as `SignatureParameters` and `ComponentProvider`.
- **Breaking:** `SignatureParametersFactory` has a new protected field, `useInstallMessageSigning`, which starts `true`. A subclass that sets `useAccountMessageSigning = true` directly now produces both signatures. Call `setUseAccountMessageSigning()` to produce the account signature only.
- The package depends on OkHttp 5.3.2, where 3.5.x depended on 4.12.0. It still depends on Retrofit 2.11.0. OkHttp 5 brings Happy Eyeballs: it tries IPv6 and IPv4 addresses side by side, so requests connect on networks that drop IPv6. Apps that compile Kotlin need Kotlin 2.1 or later. See [OkHttp version](ADVANCED.md#okhttp-version).
- The package depends on Approov SDK 3.7.0, where 3.5.x depended on 3.5.3.
- A failed secure string fetch no longer fails the request. The status name replaces the placeholder, such as `X-Api-Key: REJECTED`, so a backend that checks the value must expect it. A value that is not a secure string key (`UNKNOWN_KEY`) is left as your app wrote it, as in 3.5.x. See [secure strings](ADVANCED.md#secure-strings).
- A failed token fetch only affects requests to your Approov API domains, the hosts in your pin set. Requests to other hosts are sent unchanged.
- A WebSocket upgrade request is sent untouched, with no Approov token, secure strings or signature.
- `initialize` throws `IllegalArgumentException` for a null context, also in bypass mode (`""`).
- An `ApproovException` that your own mutator throws, such as an `ApproovNetworkException`, reaches your app as a plain `ApproovException` with the original as its cause.
- The token fetch exception message names the URL without its query, user info or fragment.

### Deprecated
- `getMessageSignature(message)`. Use `getAccountMessageSignature`.
- `setApproovHeader`, `setApproovTraceIDHeader`, `getApproovTokenHeader`, `getApproovTokenPrefix` and `getApproovTraceIDHeader`. Use `setTokenHeader(header, prefix)`, `getTokenHeader()`, `getTokenPrefix()`, `setTraceIDHeader(header)` and `getTraceIDHeader()`.

### Added
- `ApproovDefaultMessageSigning.combined()` and `combined(ApproovServiceMutator decisions)` give a signer that adds both the install and the account signature and makes the decisions you pass, for example `ApproovDefaultMessageSigning.combined(ApproovServiceMutator.ALWAYS_PROCEED)`. See [message signing](ADVANCED.md#message-signing).
- `ApproovService.getOkHttpClient()`, the Approov `OkHttpClient` that `getRetrofit` uses, for code that calls OkHttp directly. It works before `initialize`, and its requests go out without Approov protection until `initialize` is called.
- `ApproovService.setOkHttpClientBuilder(String builderName, OkHttpClient.Builder)` and `getOkHttpClient(String builderName)`, which keep several named client builders. `getRetrofit` uses the default builder.
- `ApproovServiceMutator.CLOSE_FAILURE`, the default, which keeps the 3.5.x decisions, and `ALWAYS_PROCEED`, which never aborts a request. See [what happens by default](ADVANCED.md#what-happens-by-default).
- `ApproovService.getServerAdjustedCurrentTimeMS()`, the current time corrected to the Approov servers' clock. Message signatures take their `created` and `expires` times from it.
- `ApproovService.setLoggingLevel(ApproovLogLevel)`, `INFO` by default. See [diagnostics](ADVANCED.md#diagnostics).
- Message signing can produce the account signature as well as the install signature: `SignatureParametersFactory.setUseInstallAndAccountMessageSigning()`. See [message signing](ADVANCED.md#message-signing).
- The names shared by all Approov packages: `isApproovServiceEnabled()`, `isApproovProtectionEnabled()`, `setInstallAttributes(String)` and the header methods listed under Deprecated.

### Removed
- `setProceedOnNetworkFail(boolean)` and `getProceedOnNetworkFail()`, which had no effect in 3.5.x. Use `ALWAYS_PROCEED` or `setUseApproovStatusIfNoToken(true)`.
- `prefetch()`. The SDK manages prefetching itself.
- `setApproovInterceptorExtensions`, `getApproovInterceptorExtensions` and the `ApproovInterceptorExtensions` interface. Use `setServiceMutator` with an `ApproovServiceMutator`.

### Fixed
- An exception from your mutator hook, from the Approov SDK or from a required body digest that cannot be generated fails the request with an `ApproovException` whose cause is the original, and never crashes an enqueued call.
- Cleartext requests are no longer blocked by the pinning interceptor.
- A connection that fails validation is no longer closed. Only the request fails. OkHttp can share one HTTP/2 connection between several hosts, so leaving it open keeps requests to the other hosts on it from failing.
- Each request's token is bound to its own binding header value.
- APIs added with `approov api -add <domain> -noApproovToken` get their secure strings.
- Redirects, `Authenticator` retries and stale refreshes are protected for what they send, with the mutator the request was first processed with.
- Message signing signs the request exactly as OkHttp sends it, as RFC 9421 requires.
- The body digest is calculated as the body is written, without a copy of the body in memory. A body that fails while it is written is signed without a digest, unless the digest is configured as required.
- `getLastARC()` makes no token fetch of its own.


## [3.5.8] - 2026-07-22

### Added
- Stale protection refresh: a new network interceptor detects requests that were held between Approov protection being applied and actual transmission (for example by a device deep sleep or doze period, or an app-level request queueing/backoff mechanism) and refreshes the Approov token and any message signature immediately before the request is sent, instead of transmitting expired credentials. Since it operates per network attempt it also refreshes protection on OkHttp generated retries and redirect followups. Configurable via `ApproovService.setStaleProtectionRefreshPeriod()` (default 3000ms, `<=0` disables). Because a refresh reinvokes the mutator's `handleInterceptorProcessedRequest` callback, it is gated on the new `ApproovServiceMutator.supportsProtectionRefresh()` capability: the default mutator and `ApproovDefaultMessageSigning` support it, while custom mutator implementations are never reinvoked unless they opt in.

### Fixed
- Message signing now conforms to the fail-open policy (approov/core-project-approov#564). Every signature-build failure — building the signature parameters or signature base, retrieving or base64-decoding the install/account signature, decoding the ES256 ASN.1/DER signature, or serializing the signature headers — now logs and proceeds **unsigned** instead of aborting the request, since the backend is the enforcement point for message signatures. Only a **required body digest** that cannot be generated (now a dedicated `RequiredBodyDigestException`) and an **unsupported algorithm** still fail closed.

### Changed
- Android build migrated from the unmaintained `com.github.johnrengelman.shadow` 8.1.1 plugin to the maintained fork `com.gradleup.shadow` 8.3.11 for Gradle 9 compatibility (Gradle 9 removed `FileCopyDetails.mode`, making the old plugin fail with a `MissingPropertyException`). Shaded BouncyCastle jar verified byte-identical; minimum supported Gradle remains 8.3.
- Raised the install/account message-signature skip logs for production visibility: genuine failures (signature unavailable, malformed base64/DER, serialization errors) log at error, while routine "signature is empty" skips log at warn to avoid alerting noise.
- Release automation restructured to match the okhttp reference layer: publishing moved from an automatic reaction to a pushed tag into a manual `release_to_maven.yml` (`workflow_dispatch`) that takes the release tag as input. All release consistency checks (branch is main, tag format, CHANGELOG matches the tag on main and at the tagged commit, tag points to a commit on main, README dependency snippets match the tag) run there in one place before any build or publish work, then it builds, signs and uploads to Maven Central with `PUBLISHING_TYPE=AUTOMATIC`. The tag is still pushed manually from main, so the dispatch is the release decision point. Continuous build/test stays on `build_and_test.yml` (push and pull_request).

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
