package dev.alvo.pieria.harvesters.html;

/** A host the crawler may not contact, because of where it resolves. */
public final class BlockedAddressException extends RuntimeException {

  public BlockedAddressException(String message) {
    super(message);
  }
}
