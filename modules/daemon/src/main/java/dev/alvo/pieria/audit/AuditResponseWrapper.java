package dev.alvo.pieria.audit;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** Response wrapper that streams normally while retaining a bounded audit prefix. */
final class AuditResponseWrapper extends HttpServletResponseWrapper {
  private final BoundedCapture capture;
  private ServletOutputStream stream;
  private PrintWriter writer;

  AuditResponseWrapper(HttpServletResponse response, int limit) {
    super(response);
    capture = new BoundedCapture(limit);
  }

  @Override
  public ServletOutputStream getOutputStream() throws IOException {
    if (writer != null) {
      throw new IllegalStateException("getWriter() has already been called");
    }
    if (stream == null) {
      ServletOutputStream delegate = super.getOutputStream();
      stream = new ServletOutputStream() {
        @Override public boolean isReady() { return delegate.isReady(); }
        @Override public void setWriteListener(WriteListener listener) { delegate.setWriteListener(listener); }
        @Override public void write(int value) throws IOException {
          delegate.write(value);
          capture.accept(value);
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
          delegate.write(bytes, offset, length);
          capture.accept(bytes, offset, length);
        }
        @Override public void flush() throws IOException { delegate.flush(); }
      };
    }
    return stream;
  }

  @Override
  public PrintWriter getWriter() throws IOException {
    if (stream != null) {
      throw new IllegalStateException("getOutputStream() has already been called");
    }
    if (writer == null) {
      String encoding = getCharacterEncoding();
      Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
      writer = new PrintWriter(new OutputStreamWriter(getOutputStreamForWriter(), charset));
    }
    return writer;
  }

  private ServletOutputStream getOutputStreamForWriter() throws IOException {
    ServletOutputStream delegate = super.getOutputStream();
    return new ServletOutputStream() {
      @Override public boolean isReady() { return delegate.isReady(); }
      @Override public void setWriteListener(WriteListener listener) { delegate.setWriteListener(listener); }
      @Override public void write(int value) throws IOException { delegate.write(value); capture.accept(value); }
      @Override public void write(byte[] bytes, int offset, int length) throws IOException {
        delegate.write(bytes, offset, length); capture.accept(bytes, offset, length);
      }
      @Override public void flush() throws IOException { delegate.flush(); }
    };
  }

  CapturedPayload captured() {
    if (writer != null) {
      writer.flush();
    }
    return capture.snapshot();
  }
}
