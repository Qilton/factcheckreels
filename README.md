# ReelCheck 🔍

> **Real-time fact-checking overlay for Instagram Reels, powered by Gemini 2.5 Flash and Google Search Grounding.**

ReelCheck runs as a lightweight, floating overlay over Instagram Reels. When tapped, it extracts on-screen text, username, and caption via accessibility, captures an in-memory frame with MediaProjection, and streams real-time, source-grounded verdicts in under 1 second.

---

## ✨ Features

- **⚡ Sub-Second Streaming Results**: Server-Sent Events (SSE) stream extracted claims immediately, followed by progressive grounded verdicts with verified Google Search sources (<1s latency uncached, ~60ms cached).
- **🪟 Interactive Floating Card**: Dark glassmorphic bottom-sheet card with color-coded verdict badges (`✓ SUPPORTED`, `✗ DISPUTED`, `? UNVERIFIABLE`), clickable sources opening in browser, and swipe-down dismiss gesture.
- **🛡️ Strict Zero-Disk Privacy (Rule 4)**: Screenshots and audio are processed purely in volatile RAM buffers (`ByteArrayOutputStream`, `ByteBuffer`). Zero temporary files or images are ever written to disk or logged.
- **🧠 Smart Background Pre-Fetching**: Detects when a reel is watched for >3.5 seconds and pre-caches the fact-check silently in the background.
- **🔒 Hardened Backend Protection**: In-memory daily cost cap (`$2.00/day` default with UTC midnight rollover), IP rate limiting (15 req/min), and 15s execution timeouts.
- **📱 Sideload Ready**: Configurable backend URL with Android `SharedPreferences` persistence for running locally, over LAN Wi-Fi, or on a remote server.

---

## 🏗️ Architecture

```
reelcheck/
├── app/                  # Native Android App (Kotlin, Jetpack Compose, WindowManager Overlay)
│   ├── app/src/main/     # Services, Accessibility, Capture, Networking, UI
│   └── app/src/test/     # Unit test suite
├── backend/              # Fastify 5 + TypeScript Backend
│   ├── src/services/     # Gemini Flash, Google Search Grounding, SSE Stream, Budget Tracker
│   ├── src/routes/       # /check, /check/stream, /health
│   └── test/             # Vitest test suite (20 tests covering 10 real-world benchmark reels)
└── docs/                 # Engineering documentation (PLAN.md, decisions.md D-010 to D-017)
```

---

## 🚀 Getting Started

### 1. Prerequisites
- **Node.js**: v20+ with `pnpm`
- **Android SDK**: API 29+ (Android 10+)
- **Google Gemini API Key**: [Google AI Studio](https://aistudio.google.com/)

### 2. Backend Setup
```bash
cd backend
pnpm install

# Set your Gemini API key (or leave empty to test against built-in realistic mock benchmarks)
export GEMINI_API_KEY="your-gemini-api-key"

# Run tests
pnpm test

# Start the dev server
pnpm dev
```
The server will start listening at `http://0.0.0.0:3000`.

### 3. Android App Setup & Sideloading

#### Option A: Build and Install from Source
```bash
cd app
./gradlew assembleDebug

# Install via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

#### Option B: Download Standalone APK
Download `app-debug.apk` directly from the [GitHub Releases](https://github.com/Qilton/factcheckreels/releases) page on your Android phone and install it (enable "Install unknown apps" when prompted).

---

## 📱 Connecting Mobile Phone to Backend

When running on a **physical Android phone**:
1. Ensure your phone and computer are on the **same Wi-Fi network**.
2. Find your computer's local IP address (e.g. `192.168.1.50`).
3. Open the ReelCheck app dashboard on your phone.
4. In the **Backend URL** field, change `http://10.0.2.2:3000` to `http://192.168.1.50:3000`.
5. Tap **Save & Ping** — you will see the badge turn green to **`BACKEND OK`** with daily budget stats.
6. Grant required permissions (Floating overlay, Accessibility Service, Screen Capture) and tap **Start Bubble**.
7. Open Instagram and tap the bubble on any reel!

*(Alternatively, run `ngrok http 3000` or deploy the backend to Render/Fly.io and enter your HTTPS URL in the app).*

---

## 🧪 Testing

### Backend Unit & Benchmark Tests
```bash
cd backend
pnpm test
```
Tests 20 automated cases including 10 real-world benchmark reels (astronomy, medical hoaxes, outer core physics, historical myths, and non-checkable lifestyle reels).

### Android Unit Tests
```bash
cd app
./gradlew testDebugUnitTest
```

---

## 📜 Decisions Log
All major architectural decisions and milestone exit criteria (M0 through M4) are documented chronologically in [`docs/decisions.md`](docs/decisions.md).

## 📄 License
MIT
