# ReelCheck: Plan

One-tap fact-checking for Instagram reels. A floating bubble sits over the phone screen. Tap it, and within about 5-8 seconds a small card shows the checkable claims in the current reel, with a verdict and sources for each.

Goal: counter the echo-chamber feeling of the feed. Show sources, not just a verdict.
Non-goals: monetization, iOS, Play Store release, grading opinions.

## Honest scope

This is not a few-hours project. The idea is simple but it touches several Android system APIs that behave differently across devices. Rough effort, part-time alongside college:

- Spikes (risk checks): 1-2 days
- Working MVP: about a week
- Polished and fast: another week

Do the spikes first. If audio capture is blocked, the design changes (see M0).

## Architecture

```
[Android app, Kotlin]                        [Backend, Node + TypeScript (Fastify)]
 - Overlay bubble + result card   --POST-->   /check
 - AccessibilityService (caption,             - validate + rate limit
   username, on-screen text)                  - cache lookup (hash of username+caption)
 - MediaProjection (screenshot on tap)        - call Gemini Flash (multimodal + Google Search grounding)
 - AudioPlaybackCapture (rolling buffer)      - return structured JSON
```

The Gemini API key lives only on the backend, never in the app.

### Request (app to backend)

```json
{
  "username": "string",
  "caption": "string",
  "onScreenText": "string",
  "screenshotJpegBase64": "string",
  "audioWavBase64": "string | null"
}
```

### Response (backend to app)

```json
{
  "claims": [
    {
      "claim": "string",
      "verdict": "supported | disputed | unverifiable",
      "explanation": "one or two sentences",
      "sources": [{ "title": "string", "url": "string" }]
    }
  ],
  "notCheckable": "string | null"
}
```

Rules for the model prompt:
- Extract only specific, checkable factual claims. Ignore opinions, jokes, vibes.
- Every verdict must be backed by at least one source from search. Otherwise use `unverifiable`.
- Never fabricate URLs.
- Return JSON only.

## Milestones

### M0: Spikes (go/no-go, do these first)
Each spike is a tiny throwaway app or screen.
1. Floating bubble draws over Instagram and stays on top (overlay permission + foreground service).
2. AccessibilityService reads username and caption text from the reel screen. Log what is readable.
3. MediaProjection captures one screenshot when the bubble is tapped.
4. AudioPlaybackCapture records Instagram's audio. **This is the biggest risk.** Apps can opt out of being captured. If blocked, fall back to caption + on-screen text + screenshot only, and record that in decisions.md.

Exit criteria: for each spike, write the result (works / partial / blocked) in decisions.md.

### M1: Backend
- Fastify + TypeScript, one route `/check`.
- Gemini Flash call with search grounding, JSON output validated with a schema.
- Test with curl and saved sample inputs (screenshots + captions + audio clips from real reels).
- Check accuracy on about 10 reels with known true and false claims.

### M2: Wire up
- App collects caption + screenshot (+ audio if M0 allows), calls backend, shows a result card over the reel.
- Loading state, error state, dismiss gesture.

### M3: Speed
- Stream partial results (claims first, then verdicts and sources).
- Rolling audio buffer so nothing has to be recorded after the tap.
- Cache by hash of username + caption.
- Optional: start the check after the reel has been on screen for a few seconds.
- Target: card visible in under 8 seconds.

### M4: Hardening
- Rate limiting and a daily cost cap on the backend.
- Retry and timeouts, graceful failure messages.
- Privacy: screenshots and audio are processed in memory and never stored or logged.
- APK build for sideloading to friends.

## Tech choices (proposed, confirm in decisions.md)
- App: native Kotlin (overlay, accessibility, and projection APIs need it).
- Backend: Node + TypeScript + Fastify, deployed somewhere cheap.
- Model: Gemini Flash with Google Search grounding.
- Distribution: sideloaded APK only.

## Known risks
- Instagram blocks audio capture or changes its screen structure. Mitigation: M0 spikes, keep extraction code isolated in one module so it is easy to patch.
- Model confidently wrong. Mitigation: grounding, forced sources, `unverifiable` as a valid answer.
- Terms of service. Only read what is on the user's own screen, do not scrape or store media, keep it non-commercial.
- Cost. Cache and cap. Switch to bring-your-own-key if it grows.