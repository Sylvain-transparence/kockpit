package org.kockpit.gateway;

import lombok.Getter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/** A refused call, answered as {@code {error, message}} with its status and headers. */
@Getter
public class GatewayException extends RuntimeException {

    private final HttpStatus status;

    private final String error;

    private final HttpHeaders headers;

    public GatewayException(HttpStatus status, String error, String message, HttpHeaders headers) {
        super(message);
        this.status = status;
        this.error = error;
        this.headers = headers;
    }

    public GatewayException(HttpStatus status, String error, String message) {
        this(status, error, message, new HttpHeaders());
    }

    static GatewayException badRequest(String message) {
        return new GatewayException(HttpStatus.BAD_REQUEST, "bad_request", message);
    }
}
