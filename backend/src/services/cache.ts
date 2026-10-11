import { createHash } from "node:crypto";
import type { CheckResponse } from "../schemas/response.js";

interface CacheEntry {
  response: CheckResponse;
  timestamp: number;
}

export class ReelCheckCache {
  private cache = new Map<string, CacheEntry>();
  private readonly ttlMs: number;
  private readonly maxEntries: number;

  constructor(ttlMs: number = 24 * 60 * 60 * 1000, maxEntries: number = 1000) {
    this.ttlMs = ttlMs;
    this.maxEntries = maxEntries;
  }

  computeKey(username: string, caption: string, onScreenText: string = ""): string {
    const normUser = username.trim().toLowerCase();
    const normCaption = caption.trim().toLowerCase();
    const normOnScreen = onScreenText.trim().toLowerCase();

    // Use username + caption; if caption is very short or empty, also include on-screen text
    const contentToHash = normCaption.length > 10
      ? `${normUser}:${normCaption}`
      : `${normUser}:${normCaption}:${normOnScreen}`;

    return createHash("sha256").update(contentToHash).digest("hex");
  }

  get(key: string): CheckResponse | null {
    const entry = this.cache.get(key);
    if (!entry) return null;

    if (Date.now() - entry.timestamp > this.ttlMs) {
      this.cache.delete(key);
      return null;
    }

    return entry.response;
  }

  set(key: string, response: CheckResponse): void {
    if (this.cache.size >= this.maxEntries) {
      // Evict oldest entry
      const oldestKey = this.cache.keys().next().value;
      if (oldestKey) this.cache.delete(oldestKey);
    }

    this.cache.set(key, {
      response,
      timestamp: Date.now(),
    });
  }

  size(): number {
    return this.cache.size;
  }

  clear(): void {
    this.cache.clear();
  }
}

export const checkCache = new ReelCheckCache();
