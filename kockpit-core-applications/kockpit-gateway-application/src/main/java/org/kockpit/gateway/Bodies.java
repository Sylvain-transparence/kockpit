package org.kockpit.gateway;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/** Request bodies, read up to a size limit (after gzip decompression: no zip bombs). */
final class Bodies {

    private Bodies() {
    }

    static byte[] read(HttpServletRequest request, int maxBytes) throws IOException {
        if (request.getContentLengthLong() > maxBytes && !isGzip(request)) {
            throw tooLarge(maxBytes);
        }
        InputStream in = request.getInputStream();
        try {
            if (isGzip(request)) {
                in = new GZIPInputStream(in);
            }
            byte[] body = in.readNBytes(maxBytes + 1);
            if (body.length > maxBytes) {
                throw tooLarge(maxBytes);
            }
            return body;
        } catch (ZipException e) {
            throw GatewayException.badRequest("Content-Encoding is gzip but the body isn't");
        }
    }

    private static boolean isGzip(HttpServletRequest request) {
        String encoding = request.getHeader(HttpHeaders.CONTENT_ENCODING);
        return encoding != null && encoding.trim().equalsIgnoreCase("gzip");
    }

    private static GatewayException tooLarge(int maxBytes) {
        return new GatewayException(HttpStatus.CONTENT_TOO_LARGE, "too_large",
                "The body may be %d bytes at most (uncompressed)".formatted(maxBytes));
    }
}
