import "dotenv/config";
import Fastify, { type FastifyInstance } from "fastify";
import cors from "@fastify/cors";
import rateLimit from "@fastify/rate-limit";
import { checkRoutes } from "./routes/check.js";
import { geminiService } from "./services/gemini.js";
import { budgetService } from "./services/budget.js";

export async function buildServer(): Promise<FastifyInstance> {
  const fastify = Fastify({
    logger: {
      level: process.env.LOG_LEVEL || "info",
    },
    bodyLimit: 15 * 1024 * 1024, // 15MB to allow base64 images and audio buffers in RAM
  });

  // Enable CORS
  await fastify.register(cors, {
    origin: true,
  });

  // Rate limiting (60 requests per minute per IP globally in prod; high in tests)
  const globalMax = process.env.RATE_LIMIT_MAX
    ? parseInt(process.env.RATE_LIMIT_MAX, 10)
    : process.env.NODE_ENV === "test" || process.env.VITEST
      ? 5000
      : 60;

  await fastify.register(rateLimit, {
    max: globalMax,
    timeWindow: "1 minute",
  });

  // Health check endpoint
  fastify.get("/health", async () => {
    return {
      status: "ok",
      service: "reelcheck-backend",
      geminiConfigured: geminiService.isConfigured(),
      budget: budgetService.getStats(),
      timestamp: Date.now(),
    };
  });

  // Register fact checking routes
  await fastify.register(checkRoutes);

  return fastify;
}

export async function startServer() {
  const server = await buildServer();
  const port = parseInt(process.env.PORT || "3000", 10);
  const host = process.env.HOST || "0.0.0.0";

  try {
    await server.listen({ port, host });
    console.log(`ReelCheck backend listening on http://${host}:${port}`);
    console.log(`Gemini API key configured: ${geminiService.isConfigured() ? "YES" : "NO (using fallback mode)"}`);
  } catch (err) {
    server.log.error(err);
    process.exit(1);
  }
}

// Start server if executed directly
if (process.argv[1]?.endsWith("server.ts") || process.argv[1]?.endsWith("server.js")) {
  startServer();
}
