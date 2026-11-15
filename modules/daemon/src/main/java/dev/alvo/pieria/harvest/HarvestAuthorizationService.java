package dev.alvo.pieria.harvest;

import dev.alvo.pieria.harvester.manifest.Capability;
import dev.alvo.pieria.harvester.process.HarvesterProcess;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import dev.alvo.pieria.harvester.protocol.HarvestRequest;
import dev.alvo.pieria.harvester.protocol.ProtocolViolation;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a harvester's {@code authorize} command in the background, so a user can be shown its sign-in
 * code and the harvester can wait for them to finish. One sign-in runs per source at a time.
 */
@Service
public class HarvestAuthorizationService {

  private static final Logger log = LoggerFactory.getLogger(HarvestAuthorizationService.class);
  /** How long the harvester has to produce a code. */
  static final Duration CODE_TIMEOUT = Duration.ofSeconds(60);
  /** How long past the code's expiry the harvester may take to finish. */
  static final Duration EXPIRY_GRACE = Duration.ofSeconds(30);

  private final HarvestStore store;
  private final HarvesterRegistry registry;
  private final HarvestSecretStore secretStore;
  private final Map<String, Session> sessions = new ConcurrentHashMap<>();

  public HarvestAuthorizationService(HarvestStore store, HarvesterRegistry registry, HarvestSecretStore secretStore) {
    this.store = store;
    this.registry = registry;
    this.secretStore = secretStore;
  }

  /**
   * Starts a sign-in, replacing any earlier one for the source, and returns once the harvester has a
   * code for the user or has failed.
   */
  public AuthorizationState start(String sourceId) {
    HarvestSource source = store.findSource(sourceId)
      .orElseThrow(() -> new IllegalArgumentException("unknown harvest source: " + sourceId));
    Session session = new Session(source);
    RegisteredHarvester harvester = registry.find(source.harvesterId()).orElse(null);
    if (harvester == null) {
      replace(sourceId, session);
      return session.finish(ErrorClass.CONFIG,
        "harvester " + source.harvesterId() + " is not installed or does not match its pinned checksum");
    }
    if (!harvester.manifest().has(Capability.OAUTH_DEVICE_CODE)) {
      throw new IllegalArgumentException("harvester " + source.harvesterId()
        + " has no sign-in; set its credentials with 'pieria source secret'");
    }
    replace(sourceId, session);
    session.declared.addAll(HarvestEnvelope.declaredSecrets(harvester));
    try {
      HarvestRequest request = HarvestEnvelope.build(secretStore, source, session.declared, session.secretValues, new HashMap<>());
      registry.requirePinned(harvester);
      session.process = HarvesterProcess.start(harvester.command(), "authorize", harvester.directory(), request);
    } catch (HarvestEnvelope.InvalidSource | HarvesterIntegrityException e) {
      return session.finish(ErrorClass.CONFIG, e.getMessage());
    } catch (IOException e) {
      return session.finish(ErrorClass.FATAL, "could not start the harvester: " + e.getMessage());
    }
    if (session.cancelled) {
      session.process.destroy();
    }
    session.reader = Thread.ofVirtual().name("harvest-authorize-" + sourceId).start(() -> read(session));
    try {
      session.ready.await(CODE_TIMEOUT.plusSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    AuthorizationState state = session.state.get();
    return state != null ? state : AuthorizationState.failed(ErrorClass.TRANSIENT, "the harvester sent no sign-in code in time");
  }

  /** The source's latest sign-in, kept after it finishes until the next start or a cancel. */
  public Optional<AuthorizationState> status(String sourceId) {
    return Optional.ofNullable(sessions.get(sourceId)).map(session -> session.state.get());
  }

  /** Stops a running sign-in and forgets its state. */
  public void cancel(String sourceId) {
    Session session = sessions.remove(sourceId);
    if (session != null) {
      session.cancel();
    }
  }

  @PreDestroy
  public void shutdown() {
    List.copyOf(sessions.keySet()).forEach(this::cancel);
  }

  /** Swapped in atomically, so two starts at once can't leave a process running with nobody watching it. */
  private void replace(String sourceId, Session session) {
    Session previous = sessions.put(sourceId, session);
    if (previous != null) {
      previous.cancel();
    }
  }

  private void read(Session session) {
    HarvesterProcess process = session.process;
    Instant deadline = Instant.now().plus(CODE_TIMEOUT);
    boolean saved = false;
    HarvestRecord.Failure reported = null;
    try {
      for (HarvestRecord record = process.next(deadline); record != null; record = process.next(deadline)) {
        switch (record) {
          case HarvestRecord.DeviceCode code -> {
            if (session.state.get() != null) {
              throw new ProtocolViolation("authorize may emit one device_code");
            }
            Instant expiresAt = Instant.now().plusSeconds(code.expiresInSeconds());
            session.state.set(new AuthorizationState(AuthorizationState.Status.PENDING, code.verificationUri(), code.userCode(),
              code.message(), expiresAt, null, null));
            deadline = expiresAt.plus(EXPIRY_GRACE);
            session.ready.countDown();
          }
          case HarvestRecord.SecretUpdate update -> saved |= session.save(update);
          case HarvestRecord.Failure failure -> reported = failure;
          default -> throw new ProtocolViolation("authorize may only emit device_code and secret_update");
        }
      }
      if (!process.awaitExit(deadline)) {
        throw new TimeoutException();
      }
      if (reported != null) {
        session.finish(reported.errorClass(), reported.message());
      } else if (process.exitCode() != 0) {
        session.finish(ErrorClass.FATAL, "the harvester exited with code " + process.exitCode());
      } else if (!saved) {
        session.finish(ErrorClass.FATAL, "the harvester finished without saving a sign-in");
      } else {
        session.authorized();
      }
    } catch (ProtocolViolation e) {
      session.finish(ErrorClass.FATAL, "protocol violation: " + e.getMessage());
    } catch (TimeoutException e) {
      session.finish(ErrorClass.TRANSIENT, session.state.get() == null
        ? "the harvester sent no sign-in code in time" : "the sign-in timed out");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException e) {
      log.warn("harvest sign-in failed source={}", session.source.id(), e);
      session.finish(ErrorClass.FATAL, e.toString());
    } finally {
      process.destroy();
      session.ready.countDown();
    }
  }

  private final class Session {
    final HarvestSource source;
    final Set<String> declared = ConcurrentHashMap.newKeySet();
    final Set<String> secretValues = ConcurrentHashMap.newKeySet();
    final AtomicReference<AuthorizationState> state = new AtomicReference<>();
    final CountDownLatch ready = new CountDownLatch(1);
    volatile HarvesterProcess process;
    volatile Thread reader;
    volatile boolean cancelled;

    Session(HarvestSource source) {
      this.source = source;
    }

    /** Returns whether the secret was kept. Nothing is kept once cancelled, as the source may be gone. */
    boolean save(HarvestRecord.SecretUpdate update) {
      // A source removed mid-sign-in must not get its credential back as an orphan in the store.
      if (cancelled || store.findSource(source.id()).isEmpty()) {
        return false;
      }
      return HarvestEnvelope.saveSecret(secretStore, source, declared, secretValues, null, update);
    }

    AuthorizationState finish(ErrorClass errorClass, String error) {
      String clean = HarvestEnvelope.scrub(error, secretValues);
      AuthorizationState current = state.get();
      AuthorizationState failed = current == null ? AuthorizationState.failed(errorClass, clean) : current.withFailure(errorClass, clean);
      state.set(failed);
      ready.countDown();
      return failed;
    }

    void authorized() {
      if (cancelled) {
        return;
      }
      AuthorizationState signedIn = state.get();
      // A harvester may save a credential without needing the user, so there may be no code.
      state.set(signedIn == null
        ? new AuthorizationState(AuthorizationState.Status.AUTHORIZED, null, null, null, null, null, null)
        : signedIn.authorized());
      store.findSource(source.id())
        .filter(current -> current.status() == SourceStatus.NEEDS_AUTH)
        .ifPresent(current -> {
          store.setStatus(source.id(), SourceStatus.ACTIVE);
          store.scheduleNext(source.id(), Instant.now(), 0);
        });
    }

    void cancel() {
      cancelled = true;
      HarvesterProcess running = process;
      if (running != null) {
        running.destroy();
      }
      Thread thread = reader;
      if (thread != null) {
        thread.interrupt();
      }
    }
  }
}
