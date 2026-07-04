package dev.alvo.pieria.model.usage;

/**
 * Thread-bound bridge that lets the singleton, profile-unaware {@code ModelGateway} report real
 * token usage back to the service that drives the operation, without threading an accumulator
 * through every gateway method signature.
 *
 * <p>The service binds an {@link InferenceUsageAccumulator} to a thread for the duration of the
 * work; the gateway writes through {@link #current()} on whatever thread runs the model call. Because
 * ingestion fans calls out across {@code Executors.newVirtualThreadPerTaskExecutor()} — whose worker
 * threads do <em>not</em> inherit thread-locals — the binding must happen on the worker thread itself
 * (done at the {@code bounded(...)} choke point). When nothing is bound (tests, the eval harness),
 * {@link #current()} returns a shared no-op sink and writes are discarded cheaply.
 */
public final class InferenceUsageSink {

  /**
   * Shared sink for unbound threads; never read back, so its contents are irrelevant.
   */
  private static final InferenceUsageAccumulator NOOP = new InferenceUsageAccumulator();

  private static final ThreadLocal<InferenceUsageAccumulator> BOUND = new ThreadLocal<>();

  private InferenceUsageSink() {
  }

  /**
   * Bind {@code accumulator} to the current thread. The returned {@link Binding} restores the prior
   * binding when closed, so it is safe to use with try-with-resources and to nest.
   */
  public static Binding bind(InferenceUsageAccumulator accumulator) {
    InferenceUsageAccumulator previous = BOUND.get();
    BOUND.set(accumulator);
    return () -> {
      if (previous == null) {
        BOUND.remove();
      } else {
        BOUND.set(previous);
      }
    };
  }

  /**
   * The accumulator bound to this thread, or a shared no-op sink when nothing is bound.
   */
  public static InferenceUsageAccumulator current() {
    InferenceUsageAccumulator accumulator = BOUND.get();
    return accumulator == null ? NOOP : accumulator;
  }

  /**
   * A non-throwing {@link AutoCloseable} for try-with-resources binding.
   */
  public interface Binding extends AutoCloseable {
    @Override
    void close();
  }
}
