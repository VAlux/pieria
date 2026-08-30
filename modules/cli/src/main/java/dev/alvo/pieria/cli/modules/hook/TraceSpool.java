package dev.alvo.pieria.cli.modules.hook;

import dev.alvo.pieria.api.request.TraceEventDto;
import dev.alvo.pieria.tools.os.AppDirs;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * A per-session, append-only NDJSON buffer of captured tool calls.
 *
 * <p>It exists because {@code PostToolUse} fires inside the agent's loop after every tool call.
 * Anything that touches the network there is paid dozens of times per turn; appending a line and
 * exiting is not. The turn-end hooks drain it and ship one batch, which also keeps a failure and
 * the fix that followed it inside a single extraction window.
 *
 * <p>Lives under the app-data root, not {@code PIERIA_HOME}: that is the install root, and
 * {@link AppDirs} exists precisely to keep the two apart.
 *
 * <h2>Locking</h2>
 *
 * <p>Every touch of a session's file — {@code append} (and the trim it can trigger), {@code
 * drain}, and the delete {@code sweepStale} performs — takes the same JVM-local
 * {@link ReentrantLock} for that file (see {@link #lockFor}) first, so none of the three can
 * interleave with another on the same file within this process. {@code append} and {@code drain}
 * additionally hold a {@link FileLock} on the {@code FileChannel} for their whole critical
 * section: the write, size check, and trim all happen under that one acquisition, so a concurrent
 * writer can never land in the gap between "trim read the file" and "trim rewrote it". At most one
 * {@code FileChannel}/{@code FileLock} pair is ever open per call; nothing here re-acquires or
 * nests a second lock while the first is held.
 *
 * <p>The two tiers guard different things. {@link FileChannel} locks are held on behalf of the
 * whole JVM: if this JVM already holds a lock overlapping a region, a second overlapping
 * {@code lock()} call from another thread does not queue — it throws
 * {@link java.nio.channels.OverlappingFileLockException} immediately. A harness that runs tool
 * calls in parallel triggers exactly that from multiple threads in one process, so the
 * {@code ReentrantLock} serializes same-process contenders before any thread attempts the
 * OS-level lock, which is what then covers a concurrent {@code pieria hook} process (a separate
 * JVM). That coverage is {@code append}/{@code drain} only: {@code sweepStale}'s delete holds the
 * {@code ReentrantLock} but not a {@code FileLock}, so it is ordered against a same-process
 * append or drain, not against one running in a different process.
 */
public final class TraceSpool {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String SUFFIX = ".ndjson";
  private static final long DEFAULT_MAX_BYTES = 4L * 1024 * 1024;
  private static final long MIN_MAX_BYTES = 4096L;

  // Static and keyed by normalized absolute path: correctness must hold regardless of how many
  // TraceSpool instances exist in this JVM, since nothing stops two instances from being
  // constructed against the same root.
  private static final ConcurrentHashMap<Path, ReentrantLock> FILE_LOCKS = new ConcurrentHashMap<>();

  private final Path root;
  private final long maxBytes;

  public TraceSpool(Path root) {
    this(root, DEFAULT_MAX_BYTES);
  }

  public TraceSpool(Path root, long maxBytes) {
    this.root = root;
    this.maxBytes = Math.max(MIN_MAX_BYTES, maxBytes);
  }

  /** {@code <data-root>/spool/traces}. */
  public static Path defaultRoot() {
    return AppDirs.defaultDataRoot().resolve("spool").resolve("traces");
  }

  /**
   * Append one event. Takes an exclusive file lock rather than trusting {@code O_APPEND}: a
   * redacted line can exceed {@code PIPE_BUF}, and a harness may run tool calls in parallel. The
   * post-write size check and any resulting trim happen inside the same lock acquisition — see the
   * class javadoc.
   *
   * <p>Never throws on a spool problem — a hook that fails here would break the session it is
   * embedded in, and a lost trace is not worth that.
   */
  public void append(String sessionId, TraceEventDto event) {
    Path file = spoolFile(sessionId);
    ReentrantLock guard = lockFor(file);
    guard.lock();
    try {
      Files.createDirectories(file.getParent());
      byte[] line = (MAPPER.writeValueAsString(event) + "\n").getBytes(StandardCharsets.UTF_8);
      try (FileChannel channel = FileChannel.open(
        file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
           FileLock ignored = channel.lock()) {
        channel.position(channel.size());
        channel.write(ByteBuffer.wrap(line));
        if (channel.size() > maxBytes) {
          dropOldestHalf(channel);
        }
      }
    } catch (IOException | RuntimeException e) {
      // Deliberately swallowed: see the class javadoc.
    } finally {
      guard.unlock();
    }
  }

  /** Read every parseable event and empty the spool. Unparseable lines are skipped, not fatal. */
  public List<TraceEventDto> drain(String sessionId) {
    Path file = spoolFile(sessionId);
    if (!Files.isRegularFile(file)) {
      return List.of();
    }
    ReentrantLock guard = lockFor(file);
    guard.lock();
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ,
      StandardOpenOption.WRITE);
         FileLock ignored = channel.lock()) {
      List<TraceEventDto> events = parse(readAll(channel));
      channel.truncate(0);
      return events;
    } catch (IOException | RuntimeException e) {
      return List.of();
    } finally {
      guard.unlock();
    }
  }

  /** Current spool size in bytes; {@code 0} when there is no spool. */
  public long sizeBytes(String sessionId) {
    Path file = spoolFile(sessionId);
    try {
      return Files.isRegularFile(file) ? Files.size(file) : 0L;
    } catch (IOException e) {
      return 0L;
    }
  }

  /** Number of buffered lines; {@code 0} when there is no spool. */
  public int eventCount(String sessionId) {
    Path file = spoolFile(sessionId);
    if (!Files.isRegularFile(file)) {
      return 0;
    }
    try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
      return (int) lines.filter(line -> !line.isBlank()).count();
    } catch (IOException | RuntimeException e) {
      return 0;
    }
  }

  /** Delete spools older than {@code retentionDays}; returns how many were removed. */
  public int sweepStale(int retentionDays) {
    if (!Files.isDirectory(root)) {
      return 0;
    }
    Instant cutoff = Instant.now().minusSeconds(Math.max(1, retentionDays) * 86_400L);
    List<Path> candidates;
    try (Stream<Path> files = Files.list(root)) {
      candidates = files.filter(Files::isRegularFile).toList();
    } catch (IOException | RuntimeException e) {
      return 0;
    }
    int swept = 0;
    for (Path file : candidates) {
      if (deleteIfStale(file, cutoff)) {
        swept++;
      }
    }
    return swept;
  }

  /**
   * Delete one spool file if it is older than {@code cutoff}, under the same per-file
   * {@link ReentrantLock} that guards {@link #append} and {@link #drain} for this file — a
   * concurrent append or drain on this file within this process cannot interleave with the
   * delete. Never throws: a failure to stat or delete one file must not abort the rest of the
   * sweep.
   */
  private static boolean deleteIfStale(Path file, Instant cutoff) {
    ReentrantLock guard = lockFor(file);
    guard.lock();
    try {
      FileTime modified = Files.getLastModifiedTime(file);
      return modified.toInstant().isBefore(cutoff) && Files.deleteIfExists(file);
    } catch (IOException | RuntimeException e) {
      return false;
    } finally {
      guard.unlock();
    }
  }

  /**
   * Keep the newest half. The newest events are the ones worth shipping, and a session that
   * overruns the cap has already produced more than one batch can usefully carry.
   *
   * <p>Runs on the {@link FileChannel} the caller already has open and locked — it neither opens
   * a new channel nor acquires a new lock, so this can never nest a second lock acquisition on top
   * of the caller's.
   */
  private static void dropOldestHalf(FileChannel channel) throws IOException {
    List<String> lines = new ArrayList<>();
    for (String line : readAll(channel).split("\n", -1)) {
      if (!line.isBlank()) {
        lines.add(line);
      }
    }
    List<String> kept = lines.subList(lines.size() / 2, lines.size());
    byte[] rewritten = (String.join("\n", kept) + (kept.isEmpty() ? "" : "\n"))
      .getBytes(StandardCharsets.UTF_8);
    channel.truncate(0);
    channel.position(0);
    channel.write(ByteBuffer.wrap(rewritten));
  }

  private static String readAll(FileChannel channel) throws IOException {
    channel.position(0);
    ByteBuffer buffer = ByteBuffer.allocate((int) channel.size());
    while (buffer.hasRemaining() && channel.read(buffer) > 0) {
      // keep reading
    }
    return new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
  }

  private static List<TraceEventDto> parse(String body) {
    List<TraceEventDto> events = new ArrayList<>();
    for (String line : body.split("\n")) {
      String trimmed = line.strip();
      if (trimmed.isEmpty()) {
        continue;
      }
      try {
        events.add(MAPPER.readValue(trimmed, TraceEventDto.class));
      } catch (RuntimeException e) {
        // Skip an unparseable line rather than losing the batch, matching TranscriptParser.
      }
    }
    return List.copyOf(events);
  }

  /**
   * A session id arrives from a harness and ends up in a file name, so everything outside
   * {@code [a-z0-9._-]} is replaced. This is a containment rule, not cosmetics.
   */
  private Path spoolFile(String sessionId) {
    String raw = sessionId == null || sessionId.isBlank() ? "default" : sessionId;
    String safe = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
    if (safe.isBlank() || safe.chars().allMatch(c -> c == '.' || c == '-')) {
      safe = "default";
    }
    return root.resolve(safe + SUFFIX);
  }

  /**
   * The JVM-local guard for a spool file, keyed by its normalized absolute path so that
   * correctness does not depend on how many {@code TraceSpool} instances exist in this process or
   * on how each one's {@code root} was spelled.
   */
  private static ReentrantLock lockFor(Path file) {
    return FILE_LOCKS.computeIfAbsent(file.toAbsolutePath().normalize(), ignored -> new ReentrantLock());
  }
}
