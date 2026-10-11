import { describe, it, expect, beforeAll, afterAll } from "vitest";
import type { FastifyInstance } from "fastify";
import { buildServer } from "../src/server.js";
import { CheckResponseSchema } from "../src/schemas/response.js";
import {
  SAMPLE_REEL_ASTRONOMY,
  SAMPLE_REEL_DISPUTED,
  SAMPLE_REEL_OPINION,
  BENCHMARK_10_REELS,
  TINY_JPEG_BASE64,
  TINY_WAV_BASE64,
} from "./fixtures/reels.js";

describe("ReelCheck Backend API", () => {
  let app: FastifyInstance;

  beforeAll(async () => {
    // Force mock mode for automated deterministic tests unless live key is explicitly tested
    process.env.REELCHECK_MOCK_GEMINI = "true";
    app = await buildServer();
    await app.ready();
  });

  afterAll(async () => {
    await app.close();
  });

  describe("GET /health", () => {
    it("returns 200 OK with service status and budget metrics", async () => {
      const response = await app.inject({
        method: "GET",
        url: "/health",
      });

      expect(response.statusCode).toBe(200);
      const body = JSON.parse(response.payload);
      expect(body.status).toBe("ok");
      expect(body.service).toBe("reelcheck-backend");
      expect(typeof body.timestamp).toBe("number");
      expect(body.budget).toBeDefined();
      expect(typeof body.budget.dailyCostCapDollars).toBe("number");
      expect(typeof body.budget.remainingBudgetDollars).toBe("number");
      expect(typeof body.budget.modelCallCount).toBe("number");
    });
  });

  describe("POST /check Schema Validation", () => {
    it("rejects empty body with 400 Bad Request", async () => {
      const response = await app.inject({
        method: "POST",
        url: "/check",
        headers: { "content-type": "application/json" },
        payload: {},
      });

      expect(response.statusCode).toBe(400);
      const body = JSON.parse(response.payload);
      expect(body.error).toBe("Invalid request payload");
    });

    it("rejects missing username and caption with 400 Bad Request", async () => {
      const response = await app.inject({
        method: "POST",
        url: "/check",
        headers: { "content-type": "application/json" },
        payload: {
          screenshotJpegBase64: TINY_JPEG_BASE64,
        },
      });

      expect(response.statusCode).toBe(400);
    });
  });

  describe("POST /check Caching Behavior", () => {
    it("returns cached: false on first call, and cached: true on duplicate call", async () => {
      const uniquePayload = {
        username: `caching_test_${Date.now()}`,
        caption: "Testing in-memory SHA256 cache performance for ReelCheck backend.",
        onScreenText: "Cache test",
        screenshotJpegBase64: TINY_JPEG_BASE64,
        audioWavBase64: TINY_WAV_BASE64,
      };

      // First call -> Cache Miss
      const firstResponse = await app.inject({
        method: "POST",
        url: "/check",
        headers: { "content-type": "application/json" },
        payload: uniquePayload,
      });

      expect(firstResponse.statusCode).toBe(200);
      const firstBody = JSON.parse(firstResponse.payload);
      expect(firstBody.cached).toBe(false);
      expect(CheckResponseSchema.safeParse(firstBody).success).toBe(true);

      // Second call -> Cache Hit
      const secondResponse = await app.inject({
        method: "POST",
        url: "/check",
        headers: { "content-type": "application/json" },
        payload: uniquePayload,
      });

      expect(secondResponse.statusCode).toBe(200);
      const secondBody = JSON.parse(secondResponse.payload);
      expect(secondBody.cached).toBe(true);
      expect(secondBody.claims).toEqual(firstBody.claims);
    });
  });

  describe("POST /check Benchmark: 10 Real-world Reels", () => {
    for (const testCase of BENCHMARK_10_REELS) {
      it(`evaluates ${testCase.id}: [${testCase.expectedVerdict}] - ${testCase.topic}`, async () => {
        const response = await app.inject({
          method: "POST",
          url: "/check",
          headers: { "content-type": "application/json" },
          payload: testCase.request,
        });

        expect(response.statusCode).toBe(200);
        const body = JSON.parse(response.payload);

        // 1. Strict schema validation
        const parseCheck = CheckResponseSchema.safeParse(body);
        expect(parseCheck.success).toBe(true);

        // 2. Verdict checks
        if (testCase.expectedVerdict === "not_checkable") {
          expect(body.notCheckable).not.toBeNull();
          expect(body.claims.length).toBe(0);
        } else {
          expect(body.claims.length).toBeGreaterThan(0);
          const firstClaim = body.claims[0];
          expect(firstClaim.verdict).toBe(testCase.expectedVerdict);
          expect(firstClaim.explanation.length).toBeGreaterThan(15);
          expect(firstClaim.sources.length).toBeGreaterThan(0);

          for (const source of firstClaim.sources) {
            expect(source.url.startsWith("http")).toBe(true);
            expect(source.title.length).toBeGreaterThan(0);
          }
        }
      });
    }
  });

  describe("POST /check/stream Streaming SSE Behavior", () => {
    it("rejects empty body with 400 Bad Request", async () => {
      const response = await app.inject({
        method: "POST",
        url: "/check/stream",
        headers: { "content-type": "application/json" },
        payload: {},
      });

      expect(response.statusCode).toBe(400);
    });

    it("streams events: claims -> verdict -> complete with SSE format", async () => {
      const response = await app.inject({
        method: "POST",
        url: "/check/stream",
        headers: { "content-type": "application/json" },
        payload: SAMPLE_REEL_ASTRONOMY,
      });

      expect(response.statusCode).toBe(200);
      expect(response.headers["content-type"]).toContain("text/event-stream");

      const rawPayload = response.payload;
      expect(rawPayload).toContain("event: claims");
      expect(rawPayload).toContain("event: verdict");
      expect(rawPayload).toContain("event: complete");

      // Parse SSE frames
      const lines = rawPayload.split("\n");
      const events: Array<{ event: string; data: any }> = [];
      let currentEvent = "";

      for (const line of lines) {
        if (line.startsWith("event: ")) {
          currentEvent = line.replace("event: ", "").trim();
        } else if (line.startsWith("data: ")) {
          const dataJson = line.replace("data: ", "").trim();
          events.push({ event: currentEvent, data: JSON.parse(dataJson) });
        }
      }

      // 1. Verify claims event
      const claimsEvent = events.find((e) => e.event === "claims");
      expect(claimsEvent).toBeDefined();
      expect(claimsEvent!.data.claims.length).toBeGreaterThan(0);
      expect(claimsEvent!.data.claims[0].claim).toContain("WASP-39b");

      // 2. Verify verdict event
      const verdictEvent = events.find((e) => e.event === "verdict");
      expect(verdictEvent).toBeDefined();
      expect(verdictEvent!.data.claim.verdict).toBe("supported");
      expect(verdictEvent!.data.claim.sources.length).toBeGreaterThan(0);

      // 3. Verify complete event
      const completeEvent = events.find((e) => e.event === "complete");
      expect(completeEvent).toBeDefined();
      expect(typeof completeEvent!.data.totalDurationMs).toBe("number");
    });

    it("delivers cached result instantly over stream on duplicate request", async () => {
      const streamPayload = {
        username: `stream_cache_${Date.now()}`,
        caption: "Testing stream caching for ReelCheck milestone M3.",
        onScreenText: "Stream cache",
        screenshotJpegBase64: TINY_JPEG_BASE64,
        audioWavBase64: TINY_WAV_BASE64,
      };

      // Call 1
      await app.inject({
        method: "POST",
        url: "/check/stream",
        headers: { "content-type": "application/json" },
        payload: streamPayload,
      });

      // Call 2
      const res2 = await app.inject({
        method: "POST",
        url: "/check/stream",
        headers: { "content-type": "application/json" },
        payload: streamPayload,
      });

      expect(res2.statusCode).toBe(200);
      expect(res2.payload).toContain('"cached":true');
      expect(res2.payload).toContain("event: complete");
    });
  });

  describe("Milestone M4 Hardening: Budget Caps, Timeouts & Error Recovery", () => {
    it("enforces daily request cap and rejects non-cached checks with 429", async () => {
      const { budgetService } = await import("../src/services/budget.js");

      // Temporarily set cap to 1 request
      budgetService.reset(2.0, 1);

      const payload1 = {
        username: `budget_user_1_${Date.now()}`,
        caption: "James Webb discovery of sulfur dioxide WASP-39b.",
        onScreenText: "First check",
        screenshotJpegBase64: TINY_JPEG_BASE64,
        audioWavBase64: TINY_WAV_BASE64,
      };

      const payload2 = {
        username: `budget_user_2_${Date.now()}`,
        caption: "A completely different reel about history.",
        onScreenText: "Second check",
        screenshotJpegBase64: TINY_JPEG_BASE64,
        audioWavBase64: TINY_WAV_BASE64,
      };

      // First check succeeds and consumes budget
      const res1 = await app.inject({
        method: "POST",
        url: "/check",
        headers: { "content-type": "application/json" },
        payload: payload1,
      });
      expect(res1.statusCode).toBe(200);

      // Second check should be blocked by 429
      const res2 = await app.inject({
        method: "POST",
        url: "/check",
        headers: { "content-type": "application/json" },
        payload: payload2,
      });
      expect(res2.statusCode).toBe(429);
      const body2 = JSON.parse(res2.payload);
      expect(body2.code).toBe("DAILY_QUOTA_EXCEEDED");
      expect(body2.error).toBe("Daily quota exceeded");
      expect(typeof body2.resetAt).toBe("number");

      // Cached request (payload1 again) should STILL succeed even when budget is full!
      const resCached = await app.inject({
        method: "POST",
        url: "/check",
        headers: { "content-type": "application/json" },
        payload: payload1,
      });
      expect(resCached.statusCode).toBe(200);
      const bodyCached = JSON.parse(resCached.payload);
      expect(bodyCached.cached).toBe(true);

      // Reset budget to default
      budgetService.reset();
    });

    it("enforces daily quota on streaming endpoint via SSE error event", async () => {
      const { budgetService } = await import("../src/services/budget.js");

      // Set cap to 0 requests
      budgetService.reset(2.0, 0);

      const streamPayload = {
        username: `budget_stream_${Date.now()}`,
        caption: "Testing stream quota exceeded error.",
        onScreenText: "Quota check",
        screenshotJpegBase64: TINY_JPEG_BASE64,
        audioWavBase64: TINY_WAV_BASE64,
      };

      const res = await app.inject({
        method: "POST",
        url: "/check/stream",
        headers: { "content-type": "application/json" },
        payload: streamPayload,
      });

      expect(res.statusCode).toBe(200);
      expect(res.payload).toContain("event: error");
      expect(res.payload).toContain("DAILY_QUOTA_EXCEEDED");

      // Reset budget
      budgetService.reset();
    });

    it("calculates remaining budget dollars and resets correctly", async () => {
      const { budgetService } = await import("../src/services/budget.js");
      budgetService.reset(5.0, 100);

      const initialStats = budgetService.getStats();
      expect(initialStats.dailyCostCapDollars).toBe(5.0);
      expect(initialStats.remainingBudgetDollars).toBe(5.0);
      expect(initialStats.modelCallCount).toBe(0);

      budgetService.recordModelCall(0.005);
      const afterCall = budgetService.getStats();
      expect(afterCall.modelCallCount).toBe(1);
      expect(afterCall.estimatedCostDollars).toBe(0.005);
      expect(afterCall.remainingBudgetDollars).toBe(4.995);

      budgetService.recordCachedCall();
      const afterCached = budgetService.getStats();
      expect(afterCached.cachedCount).toBe(1);
      expect(afterCached.remainingBudgetDollars).toBe(4.995); // No money consumed!

      // Reset
      budgetService.reset();
    });
  });
});


