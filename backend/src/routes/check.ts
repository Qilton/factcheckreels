import type { FastifyPluginAsync } from "fastify";
import { CheckRequestSchema } from "../schemas/request.js";
import { geminiService } from "../services/gemini.js";
import { checkCache } from "../services/cache.js";
import { budgetService } from "../services/budget.js";
import { FactCheckTimeoutError } from "../utils/timeout.js";

export const checkRoutes: FastifyPluginAsync = async (fastify) => {
  const checkRateLimitMax = process.env.CHECK_RATE_LIMIT_MAX
    ? parseInt(process.env.CHECK_RATE_LIMIT_MAX, 10)
    : process.env.NODE_ENV === "test" || process.env.VITEST
      ? 5000
      : 15;

  fastify.post(
    "/check",
    {
      config: {
        rateLimit: {
          max: checkRateLimitMax,
          timeWindow: "1 minute",
          errorResponseBuilder: (request, context) => ({
            statusCode: 429,
            error: "Too Many Requests",
            message: `Rate limit exceeded: maximum ${context.max} checks per ${context.after}. Please wait before verifying more reels.`,
            code: "RATE_LIMIT_EXCEEDED",
          }),
        },
      },
    },
    async (request, reply) => {
      // 1. Validate incoming payload against schema
      const parseResult = CheckRequestSchema.safeParse(request.body);
      if (!parseResult.success) {
        return reply.status(400).send({
          error: "Invalid request payload",
          details: parseResult.error.format(),
        });
      }

      const checkRequest = parseResult.data;

      // 2. Check cache by hash of username + caption + onScreenText
      const cacheKey = checkCache.computeKey(
        checkRequest.username,
        checkRequest.caption,
        checkRequest.onScreenText
      );

      const cached = checkCache.get(cacheKey);
      if (cached) {
        fastify.log.info({ cacheKey, username: checkRequest.username }, "Cache hit for reel check (no budget consumed)");
        budgetService.recordCachedCall();
        return reply.status(200).send({
          ...cached,
          cached: true,
        });
      }

      // 3. Enforce Daily Cost & Request Cap before model invocation
      const budgetCheck = budgetService.canExecuteCheck();
      if (!budgetCheck.allowed) {
        fastify.log.warn({ reason: budgetCheck.reason }, "Fact check blocked by daily budget cap");
        return reply.status(429).send({
          error: "Daily quota exceeded",
          message: budgetCheck.reason,
          resetAt: budgetCheck.resetAt,
          code: "DAILY_QUOTA_EXCEEDED",
        });
      }

      // 4. Perform fact check via Gemini Flash
      try {
        fastify.log.info(
          { username: checkRequest.username, hasAudio: !!checkRequest.audioWavBase64 },
          "Processing fact-check request via Gemini"
        );

        const response = await geminiService.checkReel(checkRequest);

        // Cache result in memory
        checkCache.set(cacheKey, response);

        return reply.status(200).send({
          ...response,
          cached: false,
        });
      } catch (error) {
        if (error instanceof FactCheckTimeoutError) {
          fastify.log.error(error, "Gemini fact check timed out");
          return reply.status(504).send({
            error: "Gateway Timeout",
            message: (error as Error).message,
            code: "GATEWAY_TIMEOUT",
          });
        }

        fastify.log.error(error, "Fact-check processing failed");
        return reply.status(500).send({
          error: "Fact checking failed",
          message: (error as Error).message,
        });
      }
    }
  );

  // Streaming endpoint (Server-Sent Events) with rate limiting and budget protection
  fastify.post(
    "/check/stream",
    {
      config: {
        rateLimit: {
          max: checkRateLimitMax,
          timeWindow: "1 minute",
          errorResponseBuilder: (request, context) => ({
            statusCode: 429,
            error: "Too Many Requests",
            message: `Rate limit exceeded: maximum ${context.max} checks per ${context.after}. Please wait before verifying more reels.`,
            code: "RATE_LIMIT_EXCEEDED",
          }),
        },
      },
    },
    async (request, reply) => {
      const parseResult = CheckRequestSchema.safeParse(request.body);
      if (!parseResult.success) {
        return reply.status(400).send({
          error: "Invalid request payload",
          details: parseResult.error.format(),
        });
      }

      const checkRequest = parseResult.data;

      // Set Server-Sent Events headers
      reply.raw.setHeader("Content-Type", "text/event-stream");
      reply.raw.setHeader("Cache-Control", "no-cache");
      reply.raw.setHeader("Connection", "keep-alive");
      reply.raw.setHeader("Access-Control-Allow-Origin", "*");
      reply.raw.flushHeaders?.();

      const sendEvent = (event: string, data: any) => {
        reply.raw.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
      };

      // Check cache first
      const cacheKey = checkCache.computeKey(
        checkRequest.username,
        checkRequest.caption,
        checkRequest.onScreenText
      );
      const cached = checkCache.get(cacheKey);
      if (cached) {
        budgetService.recordCachedCall();
        const { streamService } = await import("../services/stream.js");
        await streamService.streamCheck(checkRequest, sendEvent);
        reply.raw.end();
        return;
      }

      // Check daily budget cap if cache miss
      const budgetCheck = budgetService.canExecuteCheck();
      if (!budgetCheck.allowed) {
        sendEvent("error", {
          error: "Daily quota exceeded",
          message: budgetCheck.reason,
          resetAt: budgetCheck.resetAt,
          code: "DAILY_QUOTA_EXCEEDED",
        });
        reply.raw.end();
        return;
      }

      try {
        const { streamService } = await import("../services/stream.js");
        await streamService.streamCheck(checkRequest, sendEvent);
      } catch (error) {
        const isTimeout = error instanceof FactCheckTimeoutError;
        sendEvent("error", {
          error: isTimeout ? "Gateway Timeout" : "Fact checking failed",
          message: (error as Error).message,
          code: isTimeout ? "GATEWAY_TIMEOUT" : "INTERNAL_ERROR",
        });
      } finally {
        reply.raw.end();
      }
    }
  );
};

