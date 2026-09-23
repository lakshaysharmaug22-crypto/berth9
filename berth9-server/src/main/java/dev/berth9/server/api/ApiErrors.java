package dev.berth9.server.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.NoSuchElementException;

/** Consistent JSON errors: 404 for unknown ids, 400 for bad input, 401 for failed partner signatures. */
@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, String>> unauthorized(SecurityException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
