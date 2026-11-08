package dev.alvo.pieria.harvester.protocol;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Reads newline-terminated UTF-8 lines and never buffers more than one line's limit, so a process
 * writing endless output with no newline can't exhaust the reader's memory.
 */
public final class BoundedLineReader implements Closeable {

  private final InputStream in;
  private final int maxLineBytes;

  public BoundedLineReader(InputStream in, int maxLineBytes) {
    this.in = new BufferedInputStream(in);
    this.maxLineBytes = maxLineBytes;
  }

  /**
   * The next line without its terminator, or null at a clean end of stream.
   *
   * @throws ProtocolViolation on an oversize line, invalid UTF-8, a read failure, or a final line
   *                           with no newline, which means the writer died mid-record
   */
  public String readLine() {
    ByteArrayOutputStream line = new ByteArrayOutputStream();
    try {
      while (true) {
        int next = in.read();
        if (next == -1) {
          if (line.size() == 0) {
            return null;
          }
          throw new ProtocolViolation("stream truncated in the middle of a line");
        }
        if (next == '\n') {
          return decode(line.toByteArray());
        }
        if (line.size() > maxLineBytes) {
          throw new ProtocolViolation("line is over the limit of " + maxLineBytes + " bytes");
        }
        line.write(next);
      }
    } catch (IOException e) {
      throw new ProtocolViolation("failed to read the stream", e);
    }
  }

  private String decode(byte[] bytes) {
    int length = bytes.length;
    if (length > 0 && bytes[length - 1] == '\r') {
      length--;
    }
    if (length > maxLineBytes) {
      throw new ProtocolViolation("line is over the limit of " + maxLineBytes + " bytes");
    }
    try {
      return StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes, 0, length))
        .toString();
    } catch (CharacterCodingException e) {
      throw new ProtocolViolation("line is not valid UTF-8", e);
    }
  }

  @Override
  public void close() throws IOException {
    in.close();
  }
}
