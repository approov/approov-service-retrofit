# Changelog

All notable changes to this package will be documented in this file.

The format is based on Keep a Changelog and this project adheres to Semantic Versioning.


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
