export interface BudgetStats {
  date: string;
  dailyCostCapDollars: number;
  dailyRequestCap: number;
  estimatedCostDollars: number;
  remainingBudgetDollars: number;
  modelCallCount: number;
  cachedCount: number;
  resetAt: number; // Unix timestamp for next midnight UTC
}

export interface BudgetCheckResult {
  allowed: boolean;
  reason?: string;
  resetAt: number;
}

export class BudgetService {
  private dailyCostCapDollars: number;
  private dailyRequestCap: number;
  private estimatedCostPerCheck: number;

  private currentDate: string = "";
  private modelCallCount: number = 0;
  private cachedCount: number = 0;
  private estimatedCostDollars: number = 0;

  constructor() {
    this.dailyCostCapDollars = parseFloat(process.env.DAILY_COST_CAP_DOLLARS || "2.00");
    this.dailyRequestCap = parseInt(process.env.DAILY_REQUEST_CAP || "200", 10);
    this.estimatedCostPerCheck = parseFloat(process.env.ESTIMATED_COST_PER_CHECK || "0.0015");
    this.checkAndResetForNewDay();
  }

  private getUtcDateString(d: Date = new Date()): string {
    return d.toISOString().split("T")[0];
  }

  private getNextUtcMidnightTimestamp(d: Date = new Date()): number {
    const nextMidnight = new Date(d);
    nextMidnight.setUTCHours(24, 0, 0, 0);
    return nextMidnight.getTime();
  }

  private checkAndResetForNewDay() {
    const today = this.getUtcDateString();
    if (this.currentDate !== today) {
      this.currentDate = today;
      this.modelCallCount = 0;
      this.cachedCount = 0;
      this.estimatedCostDollars = 0;
    }
  }

  /**
   * Evaluates if a new Gemini model call can be executed under the daily budget caps.
   * Cache hits should NOT call this or should bypass it.
   */
  canExecuteCheck(): BudgetCheckResult {
    this.checkAndResetForNewDay();
    const resetAt = this.getNextUtcMidnightTimestamp();

    if (this.modelCallCount >= this.dailyRequestCap) {
      return {
        allowed: false,
        reason: `Daily request cap reached (${this.modelCallCount}/${this.dailyRequestCap} requests). Resets at 00:00 UTC.`,
        resetAt,
      };
    }

    if (this.estimatedCostDollars >= this.dailyCostCapDollars) {
      return {
        allowed: false,
        reason: `Daily cost cap reached ($${this.estimatedCostDollars.toFixed(4)}/$${this.dailyCostCapDollars.toFixed(2)}). Resets at 00:00 UTC.`,
        resetAt,
      };
    }

    return { allowed: true, resetAt };
  }

  /**
   * Record a Gemini model invocation and its estimated cost.
   */
  recordModelCall(customCost?: number): void {
    this.checkAndResetForNewDay();
    this.modelCallCount += 1;
    const cost = customCost ?? this.estimatedCostPerCheck;
    this.estimatedCostDollars = parseFloat((this.estimatedCostDollars + cost).toFixed(6));
  }

  /**
   * Record a cache hit (consumes $0.00).
   */
  recordCachedCall(): void {
    this.checkAndResetForNewDay();
    this.cachedCount += 1;
  }

  getStats(): BudgetStats {
    this.checkAndResetForNewDay();
    const remaining = Math.max(0, this.dailyCostCapDollars - this.estimatedCostDollars);
    return {
      date: this.currentDate,
      dailyCostCapDollars: this.dailyCostCapDollars,
      dailyRequestCap: this.dailyRequestCap,
      estimatedCostDollars: parseFloat(this.estimatedCostDollars.toFixed(4)),
      remainingBudgetDollars: parseFloat(remaining.toFixed(4)),
      modelCallCount: this.modelCallCount,
      cachedCount: this.cachedCount,
      resetAt: this.getNextUtcMidnightTimestamp(),
    };
  }

  /**
   * Force manual reset or configuration override (useful for testing).
   */
  reset(dailyCostCap?: number, dailyRequestCap?: number): void {
    if (dailyCostCap !== undefined) this.dailyCostCapDollars = dailyCostCap;
    if (dailyRequestCap !== undefined) this.dailyRequestCap = dailyRequestCap;
    this.currentDate = this.getUtcDateString();
    this.modelCallCount = 0;
    this.cachedCount = 0;
    this.estimatedCostDollars = 0;
  }
}

export const budgetService = new BudgetService();
