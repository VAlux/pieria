package dev.alvo.pieria.tools;

import java.util.function.Supplier;

/**
 * A value paired with the wall-clock time it took to produce, in milliseconds.
 *
 * <p>Use it to time a stage and carry its result and duration together, instead of threading
 * {@code System.nanoTime()} bookkeeping through the calling code:
 *
 * <pre>{@code
 * Timed<List<Chunk>> chunked = Timed.measure(() -> chunker.chunk(messages));
 * List<Chunk> chunks = chunked.value();
 * log.info("chunkMs={}", chunked.millis());
 * }</pre>
 *
 * <p>For work that produces no value, use the {@link Runnable} overload, which yields a
 * {@code Timed<Void>}. Timing uses {@link System#nanoTime()} and is wall-clock, so a stage that
 * blocks (I/O, model calls) reports the elapsed time including the wait, not CPU time.
 *
 * @param value  the result of the timed work (may be {@code null})
 * @param millis the elapsed wall-clock time in milliseconds
 * @param <T>    the type of the produced value
 */
public record Timed<T>(T value, long millis) {

  /**
   * Run value-producing work and return its result together with the elapsed milliseconds.
   */
  public static <T> Timed<T> measure(Supplier<T> work) {
    long start = System.nanoTime();
    T value = work.get();
    return new Timed<>(value, elapsedMillis(start));
  }

  /**
   * Run side-effecting work that produces no value and return the elapsed milliseconds as a
   * {@code Timed<Void>}.
   */
  public static Timed<Void> measure(Runnable work) {
    long start = System.nanoTime();
    work.run();
    return new Timed<>(null, elapsedMillis(start));
  }

  /**
   * Milliseconds elapsed since a {@link System#nanoTime()} reading. Useful for timing a span that
   * does not fit the {@link #measure} closure shape, such as a running total across several stages.
   */
  public static long elapsedMillis(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000L;
  }
}
