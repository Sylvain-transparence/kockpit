package org.kockpit.backend.tenants;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** A refused tenant/API key operation, answered as {@code {error, message}} with its status. */
@Getter
public class TenantException extends RuntimeException {

    private final HttpStatus status;

    private final String error;

    public TenantException(HttpStatus status, String error, String message) {
        super(message);
        this.status = status;
        this.error = error;
    }

    static TenantException badRequest(String message) {
        return new TenantException(HttpStatus.BAD_REQUEST, "bad_request", message);
    }

    static TenantException notFound(String message) {
        return new TenantException(HttpStatus.NOT_FOUND, "not_found", message);
    }

    static TenantException conflict(String error, String message) {
        return new TenantException(HttpStatus.CONFLICT, error, message);
    }

    static TenantException forbidden(String message) {
        return new TenantException(HttpStatus.FORBIDDEN, "forbidden", message);
    }
}
