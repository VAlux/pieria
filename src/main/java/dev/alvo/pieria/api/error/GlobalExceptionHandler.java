package dev.alvo.pieria.api.error;

import org.springframework.context.annotation.Profile;

import dev.alvo.pieria.api.response.ErrorResponse;
import dev.alvo.pieria.domain.NotFoundException;
import dev.alvo.pieria.model.ModelUnavailableException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain/validation/model failures to sanitized JSON error bodies. Messages must never leak
 * filesystem paths or provider secrets.
 */
@RestControllerAdvice
@Profile("!shim")
public class GlobalExceptionHandler {

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleInvalidBody(MethodArgumentNotValidException ex) {
    String detail = ex.getBindingResult().getFieldErrors().stream()
      .findFirst()
      .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
      .orElse("request validation failed");
    return badRequest(detail);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ErrorResponse> handleConstraint(ConstraintViolationException ex) {
    return badRequest("request validation failed");
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
    return badRequest(ex.getMessage());
  }

  @ExceptionHandler(NotFoundException.class)
  public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
      .body(new ErrorResponse("not_found", ex.getMessage()));
  }

  @ExceptionHandler(ModelUnavailableException.class)
  public ResponseEntity<ErrorResponse> handleModelUnavailable(ModelUnavailableException ex) {
    // Do not echo the provider's message; it may carry hosts/secrets.
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
      .body(new ErrorResponse("model_unavailable", "The model provider is currently unavailable"));
  }

  private ResponseEntity<ErrorResponse> badRequest(String message) {
    return ResponseEntity.badRequest().body(new ErrorResponse("bad_request", message));
  }
}
