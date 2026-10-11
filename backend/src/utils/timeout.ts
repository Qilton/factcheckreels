export class FactCheckTimeoutError extends Error {
  constructor(message: string = "Fact check operation timed out after 15 seconds") {
    super(message);
    this.name = "FactCheckTimeoutError";
  }
}

/**
 * Wraps a promise with a timeout in milliseconds.
 * Cleans up the timer on resolution or rejection to prevent event-loop leaks.
 */
export async function withTimeout<T>(
  promise: Promise<T>,
  timeoutMs: number = 15000,
  customMessage?: string
): Promise<T> {
  let timerId: NodeJS.Timeout | null = null;

  const timeoutPromise = new Promise<never>((_, reject) => {
    timerId = setTimeout(() => {
      reject(new FactCheckTimeoutError(customMessage));
    }, timeoutMs);
  });

  try {
    const result = await Promise.race([promise, timeoutPromise]);
    return result;
  } finally {
    if (timerId) {
      clearTimeout(timerId);
    }
  }
}
