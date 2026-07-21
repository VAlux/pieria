package dev.alvo.pieria.audit;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** Request wrapper that tees consumed bytes into a bounded audit capture. */
final class AuditRequestWrapper extends HttpServletRequestWrapper {
  private final BoundedCapture capture;
  private ServletInputStream stream;
  private BufferedReader reader;

  AuditRequestWrapper(HttpServletRequest request, int limit) {
    super(request);
    capture = new BoundedCapture(limit);
  }

  @Override
  public ServletInputStream getInputStream() throws IOException {
    if (reader != null) {
      throw new IllegalStateException("getReader() has already been called");
    }
    if (stream == null) {
      ServletInputStream delegate = super.getInputStream();
      stream = new ServletInputStream() {
        @Override public boolean isFinished() { return delegate.isFinished(); }
        @Override public boolean isReady() { return delegate.isReady(); }
        @Override public void setReadListener(ReadListener listener) { delegate.setReadListener(listener); }
        @Override public int read() throws IOException {
          int value = delegate.read();
          if (value >= 0) capture.accept(value);
          return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
          int read = delegate.read(bytes, offset, length);
          if (read > 0) capture.accept(bytes, offset, read);
          return read;
        }
      };
    }
    return stream;
  }

  @Override
  public BufferedReader getReader() throws IOException {
    if (reader != null) {
      return reader;
    }
    if (stream != null) {
      throw new IllegalStateException("getInputStream() has already been called");
    }
    String encoding = getCharacterEncoding();
    Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
    ServletInputStream delegate = super.getInputStream();
    stream = new ServletInputStream() {
        @Override public boolean isFinished() { return delegate.isFinished(); }
        @Override public boolean isReady() { return delegate.isReady(); }
        @Override public void setReadListener(ReadListener listener) { delegate.setReadListener(listener); }
        @Override public int read() throws IOException {
          int value = delegate.read();
          if (value >= 0) capture.accept(value);
          return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
          int read = delegate.read(bytes, offset, length);
          if (read > 0) capture.accept(bytes, offset, read);
          return read;
        }
    };
    reader = new BufferedReader(new InputStreamReader(stream, charset));
    return reader;
  }

  CapturedPayload captured() {
    return capture.snapshot();
  }
}
