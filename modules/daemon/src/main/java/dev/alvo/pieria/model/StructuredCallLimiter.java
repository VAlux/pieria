package dev.alvo.pieria.model;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Fair process-wide admission gate for structured extraction-tier HTTP attempts.
 */
public final class StructuredCallLimiter {

  private final Semaphore permits;

  public StructuredCallLimiter(int maxConcurrentCalls) {
    this.permits = new Semaphore(Math.max(1, maxConcurrentCalls), true);
  }

  public <T> T execute(Supplier<T> attempt) {
    try {
      permits.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ModelUnavailableException("structured model call interrupted while queued", e);
    }

    try {
      return attempt.get();
    } finally {
      permits.release();
    }
  }

  int availablePermits() {
    return permits.availablePermits();
  }
}
