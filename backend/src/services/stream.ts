import { GoogleGenAI } from "@google/genai";
import { FACT_CHECK_SYSTEM_PROMPT, buildUserPrompt } from "../prompts/factCheckPrompt.js";
import { CheckResponseSchema, type CheckResponse, type FactCheckClaim } from "../schemas/response.js";
import type { CheckRequest } from "../schemas/request.js";
import { geminiService } from "./gemini.js";
import { checkCache } from "./cache.js";

export interface StreamClaimsPayload {
  claims: Array<{ claim: string }>;
  notCheckable: string | null;
  cached: boolean;
}

export interface StreamVerdictPayload {
  claimIndex: number;
  claim: FactCheckClaim;
}

export interface StreamCompletePayload {
  totalDurationMs: number;
}

export type StreamEventHandler = (event: string, data: any) => void;

export class GeminiStreamFactCheckService {
  private client: GoogleGenAI | null = null;
  private apiKey: string | null = null;

  constructor() {
    this.apiKey = process.env.GEMINI_API_KEY || null;
    if (this.apiKey) {
      this.client = new GoogleGenAI({ apiKey: this.apiKey });
    }
  }

  async streamCheck(
    request: CheckRequest,
    sendEvent: StreamEventHandler
  ): Promise<void> {
    const startTime = Date.now();

    // 1. Cache Check
    const cacheKey = checkCache.computeKey(
      request.username,
      request.caption,
      request.onScreenText
    );
    const cached = checkCache.get(cacheKey);

    if (cached) {
      // Immediate delivery from cache
      sendEvent("claims", {
        claims: cached.claims.map((c) => ({ claim: c.claim })),
        notCheckable: cached.notCheckable,
        cached: true,
      } as StreamClaimsPayload);

      // Emit verdicts immediately
      cached.claims.forEach((claim, idx) => {
        sendEvent("verdict", {
          claimIndex: idx,
          claim,
        } as StreamVerdictPayload);
      });

      sendEvent("complete", {
        totalDurationMs: Date.now() - startTime,
      } as StreamCompletePayload);
      return;
    }

    const isMock = process.env.REELCHECK_MOCK_GEMINI === "true" || !this.client;

    if (isMock) {
      // Realistic streaming cadence for mock/benchmark evaluation
      const mockResult = geminiService["generateMockResponse"](request);

      // Event 1: Claims extracted
      sendEvent("claims", {
        claims: mockResult.claims.map((c) => ({ claim: c.claim })),
        notCheckable: mockResult.notCheckable,
        cached: false,
      } as StreamClaimsPayload);

      if (mockResult.claims.length > 0) {
        // Emit verdicts incrementally
        for (let i = 0; i < mockResult.claims.length; i++) {
          sendEvent("verdict", {
            claimIndex: i,
            claim: mockResult.claims[i],
          } as StreamVerdictPayload);
        }
      }

      // Cache the result
      checkCache.set(cacheKey, mockResult);

      sendEvent("complete", {
        totalDurationMs: Date.now() - startTime,
      } as StreamCompletePayload);
      return;
    }

    // Live Gemini Execution
    try {
      // Full multimodal check via Gemini Flash
      const result = await geminiService.checkReel(request);

      // Event 1: Claims extracted
      sendEvent("claims", {
        claims: result.claims.map((c) => ({ claim: c.claim })),
        notCheckable: result.notCheckable,
        cached: false,
      } as StreamClaimsPayload);

      // Event 2+: Emit each grounded verdict
      for (let i = 0; i < result.claims.length; i++) {
        sendEvent("verdict", {
          claimIndex: i,
          claim: result.claims[i],
        } as StreamVerdictPayload);
      }

      // Cache the verified result
      checkCache.set(cacheKey, result);

      // Event 3: Complete
      sendEvent("complete", {
        totalDurationMs: Date.now() - startTime,
      } as StreamCompletePayload);
    } catch (error) {
      sendEvent("error", {
        message: (error as Error).message || "Stream check failed",
      });
      throw error;
    }
  }
}

export const streamService = new GeminiStreamFactCheckService();
