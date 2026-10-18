package dev.alvo.pieria.wiki;

/** Safe machine-readable failures: never includes source/model/provider content. */
public final class WikiException extends RuntimeException {
  private final int status;
  public WikiException(int status,String code) { super(code); this.status=status; }
  public int status() { return status; }
}
