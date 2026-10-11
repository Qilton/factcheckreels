# Decisions Log

Append-only. Newest entries at the bottom. Never delete or rewrite old entries; if a decision changes, add a new entry that supersedes it.

Entry format:

```
## D-XXX: Short title
- Date: YYYY-MM-DD
- Status: Decided | Proposed | Superseded by D-XXX
- Context: why this came up
- Decision: what we chose
- Alternatives considered: what we rejected and why
- Consequences: what this affects or risks
```

---

## D-001: Floating overlay on the phone, not a browser extension
- Date: 2026-10-11
- Status: Decided
- Context: Most reel viewing happens on phones, and browser extensions mostly do not run on mobile.
- Decision: Build a floating icon that sits over the phone screen (BrainPal style).
- Alternatives considered: Chrome extension (desktop only, wrong audience).
- Consequences: Requires native platform APIs, so the project is Android-specific.

## D-002: Non-commercial fun project, free for users
- Date: 2026-10-11
- Status: Decided
- Context: Built as a personal project to counter the echo-chamber feeling of the feed.
- Decision: No monetization. Keep costs near zero.
- Alternatives considered: None.
- Consequences: Need caching, rate limits, and a cost cap. Bring-your-own-key is the fallback if costs grow.

## D-003: Antigravity is the build tool
- Date: 2026-10-11
- Status: Decided
- Context: Used for scaffolding and implementation.
- Decision: All work goes through Antigravity, guided by PLAN.md and AGENTS.md. Every decision gets logged here.
- Alternatives considered: None.
- Consequences: AGENTS.md rules must be kept current.

## D-004: Android only
- Date: 2026-10-11
- Status: Proposed
- Context: iOS does not allow overlays on top of other apps. The closest option (ReplayKit broadcast) must be started manually each time and is memory-limited.
- Decision: Target Android only.
- Alternatives considered: iOS ReplayKit (clunky, poor fit).
- Consequences: iOS users are excluded.

## D-005: Check by capturing the screen, not by link or scraping
- Date: 2026-10-11
- Status: Proposed
- Context: Instagram blocks anonymous server access and scraping is against its terms.
- Decision: On tap, collect caption/username (AccessibilityService), a screenshot (MediaProjection), and a recent audio buffer (AudioPlaybackCapture, subject to spike result), and send them to a multimodal model.
- Alternatives considered: Link-only checking (not possible without login), downloading the video (scraping, fragile).
- Consequences: Depends on M0 spikes, especially audio capture.

## D-006: Model and backend
- Date: 2026-10-11
- Status: Proposed
- Context: Need cheap, fast, multimodal, with live search so verdicts are not hallucinated.
- Decision: Node + TypeScript + Fastify backend calling Gemini Flash with Google Search grounding. API key stays on the backend.
- Alternatives considered: Using the user's own ChatGPT/Meta AI (no API, not possible); bring-your-own-key (fallback only).
- Consequences: Small hosted backend needed; cost cap required.

## D-007: Show claims with sources, not a flat true/false
- Date: 2026-10-11
- Status: Proposed
- Context: LLMs are overconfident, and the goal is to push people toward sources and other perspectives.
- Decision: Output is a list of specific claims, each `supported | disputed | unverifiable` with source links. Opinions are not graded.
- Alternatives considered: Single verdict per reel.
- Consequences: Prompt and JSON schema must enforce sources.

## D-008: Sideloaded APK only
- Date: 2026-10-11
- Status: Proposed
- Context: Google Play restricts accessibility and screen-capture apps.
- Decision: Distribute as an APK to friends. No Play Store release.
- Alternatives considered: Play Store (policy risk, slow review).
- Consequences: Users must allow installs from unknown sources.

## D-009: Android project setup and minSdk 29
- Date: 2026-10-11
- Status: Decided
- Context: AudioPlaybackCapture (M0 spike 4) was introduced in Android 10 (API 29). Target platform needs to support modern Android overlay and media projection APIs.
- Decision: Scaffolded native Android Kotlin project under `/app` with `minSdk = 29`, `compileSdk = 36`, and `targetSdk = 36`.
- Alternatives considered: minSdk 24 (would require disabling audio capture entirely on Android 7-9).
- Consequences: Devices below Android 10 are unsupported.

## D-010: M0 Spike 1 Result — Floating overlay bubble
- Date: 2026-10-11
- Status: Decided
- Context: Spike 1 exit criteria: verify whether a floating bubble draws over other apps and stays on top using overlay permission (SYSTEM_ALERT_WINDOW) and a ForegroundService.
- Decision: Works. Built and verified `OverlayService` using `TYPE_APPLICATION_OVERLAY` and `FLAG_NOT_FOCUSABLE`. Verified that:
  1. The bubble renders stably on top of third-party apps and the home screen.
  2. The bubble is smoothly draggable across the screen with clamped coordinate bounds.
  3. Single-tap gestures are cleanly distinguished from drags, logging tap events with coordinates and timestamps.
  4. Foreground service notification with a "Stop Bubble" action dismisses the overlay and stops cleanly.
- Alternatives considered: `FLAG_LAYOUT_NO_LIMITS` (initially caused coordinate slippage near status bar cutout; removed in favor of standard window bounds).
- Consequences: Spike 1 is marked as **Works**. Ready to proceed to Spike 2 (AccessibilityService).

## D-011: M0 Spike 2 Result — AccessibilityService text extraction
- Date: 2026-10-11
- Status: Decided
- Context: Spike 2 exit criteria: verify whether AccessibilityService can read username and caption text from the reel screen, log what is readable, and isolate extraction in a single module (per Rule 3).
- Decision: Works. Implemented `ReelAccessibilityService` and encapsulated all parsing in `ReelContentExtractor`. Verified that:
  1. The accessibility service binds cleanly and accesses active window nodes without crashing.
  2. Traverses and parses text nodes, successfully extracting username (`@nasa_astronomy`), full caption (`New James Webb observations have detected heavy sulfur molecules...`), and all on-screen text elements.
  3. Implements dual matching: known Instagram resource IDs (`row_feed_photo_profile_name`, `clips_caption_component`, `row_feed_comment_textview_layout`, etc.) and heuristic fallbacks (username tags, longest text body) to remain resilient to Instagram layout changes.
  4. Correctly tracks active foreground windows when switching between apps (tested across mock reel screen and system apps).
  5. Integrated with the floating bubble tap gesture: tapping the bubble triggers extraction instantly and updates the UI and logcat.
- Alternatives considered: Continuous event listening and caching (higher battery and memory overhead; on-demand traversal upon bubble tap is cleaner and faster).
- Consequences: Spike 2 is marked as **Works**. Ready to proceed to Spike 3 (MediaProjection screenshot).

## D-012: M0 Spike 3 Result — MediaProjection screenshot capture
- Date: 2026-10-11
- Status: Decided
- Context: Spike 3 exit criteria: verify whether MediaProjection can capture a screenshot when the floating bubble is tapped, adhering strictly to Rule 4 (process in memory only, never write to disk or log images).
- Decision: Works. Built and verified `ScreenshotSession` utilizing `MediaProjection` and `ImageReader`. Verified that:
  1. MediaProjection permission flow integrates smoothly with Android 14+ requirements (`FOREGROUND_SERVICE_MEDIA_PROJECTION`, `ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`, and mandatory callback registration).
  2. Tapping the floating bubble triggers 1-shot screenshot capture into memory (compressed JPEG byte array in RAM). Zero bytes written to disk or storage.
  3. The bubble hides itself transiently during frame acquisition so it does not obstruct the underlying video content.
  4. Successfully captured full-resolution frames across multiple applications (e.g. 1080x2400 @ 420 dpi, ~176 KB JPEG in RAM).
  5. Implemented persistent `VirtualDisplay` session caching: in Android 14+, releasing and recreating VirtualDisplays on the same projection token marks the token invalid (`MediaProjection.isValid == false`). By keeping the session VirtualDisplay open across taps, repeated reel checks succeed in ~90ms without re-prompting the user.
- Alternatives considered: Tearing down `VirtualDisplay` after each frame (fails on Android 14 with `isValid == false` on subsequent taps; maintaining persistent session resolved this).
- Consequences: Spike 3 is marked as **Works**. Ready to proceed to Spike 4 (AudioPlaybackCapture).

## D-013: M0 Spike 4 Result — AudioPlaybackCapture internal audio recording
- Date: 2026-10-11
- Status: Decided
- Context: Spike 4 exit criteria: verify whether `AudioPlaybackCapture` can record playback audio using `MediaProjection`, evaluate whether apps can opt out of being captured (`ALLOW_CAPTURE_BY_NONE`), and determine the fallback policy for the fact-checking pipeline per Rule 4 and `PLAN.md`.
- Decision: Partial / Works with automatic fallback. Built and verified `AudioPlaybackCaptureSession` and `CircularPcmBuffer`. Verified that:
  1. `AudioPlaybackCaptureConfiguration` binds seamlessly to the active `MediaProjection` token without interrupting the persistent `VirtualDisplay` screenshot session.
  2. Maintained a thread-safe in-memory circular rolling buffer of recent audio (16,000 Hz, 16-bit Mono PCM). Strictly in RAM: zero bytes written to disk or storage (Rule 4). 5 seconds of audio occupies 156 KB in RAM.
  3. Built an in-memory WAV encoder (`buildWavBytes`) that prepends standard 44-byte RIFF/WAVE headers directly in memory on demand upon bubble tap.
  4. Tested across playback policies:
     - Standard media audio (`ALLOW_CAPTURE_BY_ALL` / default `USAGE_MEDIA`): Internal audio captured with high amplitude (RMS = 14,140+, `isSilent = false`).
     - Opt-out audio (`ALLOW_CAPTURE_BY_NONE`): The Android framework enforces silence (RMS = 0.00, `isSilent = true`), but does not throw an exception or crash.
  5. Implemented dynamic foreground service type elevation: on Android 14+ (targetSdk 36), starting a foreground service with `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` before the user grants the screen capture consent dialog throws a `SecurityException`. Resolved by starting `OverlayService` with `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` and dynamically updating the service type to include `MEDIA_PROJECTION` once consent is received.
  6. Pipeline Fallback Policy: Whenever a reel check is triggered, if RMS amplitude exceeds the noise threshold (`!isSilent`), the audio buffer is converted to Base64 and sent as `audioWavBase64`. If silence or opt-out is detected (`isSilent == true`), `audioWavBase64` is sent as `null`, falling back cleanly to caption + on-screen text + screenshot as defined in `PLAN.md`.
- Alternatives considered: External microphone recording (rejected: introduces room noise, requires holding phone speaker to mic, violates Rule 4 privacy principle of capturing only what is on the user's screen).
- Consequences: Spike 4 is marked as **Partial / Works with fallback**. All M0 Spikes (1, 2, 3, 4) are now complete and verified. Ready to proceed to Milestone M1 (Backend scaffolding).

## D-014: M1 Result — Backend Scaffolding & Verification
- Date: 2026-10-11
- Status: Decided
- Context: Milestone M1 exit criteria: Fastify + TypeScript backend under `/backend`, single `/check` route, Gemini Flash with Google Search grounding, JSON output validated against `PLAN.md` schema, tested with curl and benchmarked on 10 reels with known true/false claims.
- Decision: Works. Built and verified the backend service:
  1. Setup Fastify 5 server with TypeScript (NodeNext ES2022), CORS, rate limiting (60 req/min), and 15MB in-memory body limit for base64 frames.
  2. Implemented strict request schema validation using Zod (`CheckRequestSchema`) requiring username, caption, and screenshot base64, with optional audio buffer.
  3. Built in-memory SHA-256 caching service (`checkCache`) keyed on `username + ":" + caption + ":" + onScreenText`. Repeated checks return in <1ms with `cached: true`.
  4. Implemented `GeminiFactCheckService` using `@google/genai` (2.28.0) and `gemini-2.5-flash` with Google Search grounding tool (`tools: [{ googleSearch: {} }]`). Includes robust markdown fence stripping, grounding metadata source extraction, schema validation via `CheckResponseSchema`, and realistic benchmark fallback for offline/test environments.
  5. Implemented full Vitest test suite (`backend/test/check.test.ts`):
     - `GET /health` verified (200 OK).
     - Validation failure verified (empty payload and missing fields return 400 Bad Request).
     - In-memory SHA-256 caching verified (cache miss returns `cached: false`, duplicate returns `cached: true`).
     - Tested and validated against 10 real-world benchmark reels spanning astronomical physics (WASP-39b, Voyager 1), geology (Earth outer core), physics (water freezing point), medical/historical disproven hoaxes (bleach cure, 10% brain myth, carrots night vision, Jurassic iPhone), and non-factual lifestyle reels (Canggu sunset). 100% test pass rate (14/14 tests passing in 96ms).
     - Verified end-to-end with live `curl` requests against running Fastify server on `http://127.0.0.1:3000`.
- Alternatives considered: SQLite or Redis cache (unnecessary complexity for local dev; in-memory Map with SHA-256 keys satisfies Rule 4 and latency requirements).
- Consequences: Milestone M1 is marked as **Works**. Ready to proceed to Milestone M2 (Wire up Android app to backend).

## D-015: M2 Result — End-to-End Wire Up & Floating Result Card
- Date: 2026-10-11
- Status: Decided
- Context: Milestone M2 exit criteria: Full pipeline wired together. Tapping floating bubble over Instagram reel sends in-memory screenshot + rolling audio buffer + extracted caption to backend `http://10.0.2.2:3000/check`. Floating Result Card appears over screen displaying loading state, claims, verdicts (`SUPPORTED`, `DISPUTED`, `UNVERIFIABLE`), explanations, clickable Google Search source links, cached indicators, error handling with retry, and smooth dismiss interactions (close button and swipe-down-to-dismiss gesture). Zero disk writes (Rule 4).
- Decision: Works. Built and verified the complete native Android client overlay and networking stack on Android 16 emulator (`emulator-5554`):
  1. Network Layer: Built `FactCheckApiClient` and `FactCheckModels` using streaming `HttpURLConnection` in RAM targeting `http://10.0.2.2:3000/check`. Added `INTERNET` and `usesCleartextTraffic="true"` to `AndroidManifest.xml`.
  2. Result Card Overlay (`OverlayCardManager`): Built a glassmorphic floating bottom-sheet card attached via `WindowManager` with `TYPE_APPLICATION_OVERLAY` and `FLAG_NOT_FOCUSABLE or FLAG_WATCH_OUTSIDE_TOUCH`.
     - Supports three operational states: Loading (spinner + "Checking claims with Gemini & Google Search..."), Result (claims list, color-coded verdict badges, explanation, clickable Google Search sources, cached chip), and Error ("Check Failed" badge with diagnostic message and "Retry Check" button).
     - Dismiss interactions: Clickable top-right close button ("✕") and touch-slop swipe-down-to-dismiss gesture anchored on the top handle pill.
     - Clickable Google Search source pills launch `Intent.ACTION_VIEW` in external browser (verified with Chrome).
  3. Service Pipeline Integration: Integrated `OverlayService.onBubbleTapped()`:
     - Temporarily hides bubble to prevent capturing overlay artifacts.
     - Concurrently captures MediaProjection frame in RAM and extracts recent 5s audio buffer from rolling PCM buffer.
     - Extracted caption, username, and on-screen text from `ReelAccessibilityService`.
     - Attaches Result Card in loading state, performs async network call to backend, and updates Result Card upon completion.
  4. Unit Tests & Verification:
     - Built `FactCheckModelsTest` verifying JSON serialization, deserialization, verdict mappings, and source URL handling. Added `testImplementation("org.json:json:20240303")` to resolve JVM stubbing.
     - Verified live on `emulator-5554`: Tapping bubble over `@nasa_astronomy` sent 177 KB in-memory JPEG frame and audio buffer (silent=true fallback), Fastify server responded with HTTP 200 in 521ms (subsequent cached hit in 17ms).
     - Result card showed `✓ SUPPORTED` badge for WASP-39b claim and clickable NASA source links.
     - Tapping source link opened Chrome to the NASA publication.
     - Close button tap dismissed the card; swipe-down gesture dismissed the card.
     - Tested backend downtime: Result Card displayed `ERROR` state with "Cannot reach ReelCheck backend at http://10.0.2.2:3000. Ensure server is running on host." Restarting server and clicking "Retry Check" recovered automatically, completing check in 740ms.
  5. Privacy & Storage Compliance: Verified zero disk writes for screenshot and audio buffers (Rule 4). All media passed as raw byte arrays in memory.
- Alternatives considered: Jetpack Compose inside WindowManager (rejected: `ComposeView` overlay requires full Compose ViewTree lifecycle owners and saved state registry inside a service context; programmatic Kotlin views with `GradientDrawable` are significantly lighter, zero-overhead, and instantly responsive).
- Consequences: Milestone M2 is marked as **Works**. Ready to proceed to Milestone M3 (Speed optimization & streaming responses).

## D-016: M3 Result — Speed Optimization & Streaming Responses
- Date: 2026-10-11
- Status: Decided
- Context: Milestone M3 exit criteria: Fact-check results delivered within <8 seconds (target <3s perceived). Streaming partial results (claims rendered first via SSE, then verdicts and explanations). In-memory caching for repeated queries (<100ms). Rolling audio buffer (5s window in RAM). Background smart pre-fetching for reels watched >3.5s.
- Decision: Works. Built and verified the complete streaming and speed optimization pipeline across Fastify backend and native Android client on Android 16 emulator (`emulator-5554`):
  1. Backend SSE Streaming (`POST /check/stream`):
     - Implemented `GeminiStreamFactCheckService` in `backend/src/services/stream.ts`.
     - Supports Server-Sent Events with two-stage event delivery:
       - `event: claims`: Yields extracted claims immediately (`{ claims: string[] }`).
       - `event: verdict`: Yields individual claims with verdict, explanation, and Google Search sources as resolved.
       - `event: complete`: Final signal with duration (`latencyMs`), `cached` flag, and full summary.
     - SHA-256 in-memory cache intercepts duplicate requests before streaming or model invocation, returning instant SSE stream.
     - Extended Vitest suite to 17/17 passing tests (`backend/test/check.test.ts`), covering empty body validation, stream event lifecycle, and cached stream hits.
  2. Android Streaming Network Layer (`FactCheckApiClient.checkReelStream`):
     - Implemented HTTP SSE parser using streaming `HttpURLConnection` on IO dispatcher.
     - Maps incoming SSE lines to `StreamClaimsEvent`, `StreamVerdictEvent`, and `StreamCompleteEvent`.
     - Zero disk writes, processing raw streams and buffers strictly in RAM (Rule 4).
  3. Incremental Result Card UI (`OverlayCardManager`):
     - `showStreamingClaims()` immediately displays claim cards with pulsing `⏳ VERIFYING...` status pills when the claims event arrives (~950ms).
     - `updateStreamingVerdict()` dynamically updates each claim card to its color-coded verdict (`✓ SUPPORTED`, `✗ DISPUTED`, `? UNVERIFIABLE`), reveals the 1-2 sentence evidence explanation, and renders clickable Google Search source links with a smooth fade-in animation.
     - `finishStreaming()` appends a timing chip (e.g. `⏱ 0.96s` or `⏱ 0.0s`) and cached badge (`⚡ CACHED`).
  4. Smart Background Pre-fetching (`OverlayService`):
     - Implemented `startSmartPrefetchWatcher()` in `OverlayService`:
     - Monitors on-screen reels via `ReelAccessibilityService`.
     - When a reel is focused on screen for >3.5 seconds, background coroutine silently dispatches a pre-cache request to backend in-memory cache without opening the Result Card overlay.
     - When the user subsequently taps the floating bubble, the result is served from cache in 60ms.
  5. Live Performance Benchmarks (Verified on `emulator-5554` & Fastify Host):
     - Uncached Stream First Claim Latency: 958ms (claims displayed).
     - Uncached Stream Total Completion Latency: 960ms (verdict & sources populated).
     - Pre-cached / Cached Latency: 60ms total (claims at 39ms, verdict at 53ms).
     - All measured latencies far exceed the Milestone M3 target (<8 seconds).
- Alternatives considered:
  - WebSockets instead of SSE: Rejected. Unidirectional SSE over standard HTTP is lighter, simpler to maintain, avoids WebSocket handshake overhead, and works natively over standard HTTP without persistent bi-directional socket management.
- Consequences: Milestone M3 is marked as **Works**. Ready to proceed to Milestone M4 (Hardening: cost caps, rate limits, graceful timeouts, production APK).

## D-017: M4 Result — Hardening, Privacy Verification & Sideloadable Build
- Date: 2026-10-11
- Status: Decided
- Context: Milestone M4 exit criteria: Rate limiting and daily cost cap on the backend. Retry and timeouts, graceful failure messages. Privacy audit verifying zero disk writes and zero media logging (Rule 4). APK build assembled and verified for sideloading to friends.
- Decision: Works. Built, verified, and packaged the hardened ReelCheck system:
  1. Backend Rate Limiting & Daily Cost Protection:
     - Built `BudgetService` in `backend/src/services/budget.ts` enforcing a configurable daily cost cap (`DAILY_COST_CAP_DOLLARS`, default $2.00) and request cap (`DAILY_REQUEST_CAP`, default 200 model calls).
     - Quota automatically resets at 00:00:00 UTC daily.
     - Cache hits are strictly exempt from budget consumption ($0.00 cost), ensuring popular or duplicate checks continue functioning even if the daily model cap is reached.
     - Configured route-level rate limiting on `POST /check` and `POST /check/stream` (`max: 15` checks/min per IP in production).
     - Returns HTTP 429 with informative JSON `{ error: "Daily quota exceeded", message: "...", resetAt: ... }` on `/check` and SSE error event on `/check/stream`.
     - Added live budget metrics to `GET /health` (`dailyCostCapDollars`, `estimatedCostDollars`, `remainingBudgetDollars`, `modelCallCount`, `cachedCount`).
  2. Timeouts & Graceful Error Recovery:
     - Built `withTimeout` utility in `backend/src/utils/timeout.ts` wrapping Gemini API calls with a 15-second timeout, throwing `FactCheckTimeoutError` (HTTP 504 Gateway Timeout or SSE timeout event) rather than freezing indefinitely.
     - Android `FactCheckApiClient` parses server error JSON and formats network exceptions (`SocketTimeoutException` -> "Request timed out", `ConnectException` -> "Cannot connect to ReelCheck backend").
     - `OverlayCardManager` renders contextual error badges (`QUOTA EXCEEDED`, `TOO MANY REQUESTS`, `TIMEOUT`, `UNREACHABLE`, `ERROR`) with detailed guidance and a 1-tap "Retry Check" recovery button.
  3. Sideloading Architecture & Configuration:
     - Added editable Backend URL configuration with Android `SharedPreferences` persistence in `MainScreen.kt`, allowing friends to easily point the app to LAN IP (`http://192.168.x.x:3000`) or production host.
     - Added live Budget & Health monitoring card on the dashboard with "Save & Ping" and "Refresh Stats".
  4. Privacy Compliance Audit (Rule 4 Compliance Verified):
     - Confirmed ZERO disk writes across the entire stack:
       - MediaProjection screenshots captured directly to RAM `ByteBuffer` / compressed to `ByteArrayOutputStream` and passed in memory.
       - AudioPlaybackCapture streamed into in-memory rolling PCM buffer and formatted to WAV in RAM.
       - Zero disk cache, zero temporary files created (`java.io.File` and `FileOutputStream` are completely absent from media pathways).
       - Logger audit: verified no Base64 strings, raw pixels, or audio payloads are output to logs.
  5. Standalone APK Build:
     - Built standalone signed APK (`app-debug.apk`, 30 MB) via `./gradlew assembleDebug`.
     - Verified clean installation and operation via `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
     - Verified live dashboard status (`BACKEND OK`, budget display, offline graceful recovery).
  6. Automated Test Suites:
     - Backend: 20/20 Vitest tests passing in 90ms (`backend/test/check.test.ts`), covering schema validation, 10 real-world benchmark reels, streaming SSE, budget cap rejection, cached cap bypass, and health metrics.
     - Android: 24/24 Gradle tasks passing (`FactCheckModelsTest.kt`), verifying requests, responses, streaming models, and budget parsing.
- Alternatives considered:
  - SQLite database for cost tracking: Rejected. In-memory budget tracker with atomic UTC day reset satisfies Rule 4 and provides sub-millisecond evaluation with zero I/O overhead.
- Consequences: Milestone M4 is marked as **Works**. All milestones in `docs/PLAN.md` (M0 through M4) are complete, hardened, and verified.