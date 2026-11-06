package org.kockpit.backend.security.local;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The security package as the backend scans it (SecurityConfig, Me, local accounts) plus one protected endpoint. */
@SpringBootApplication(scanBasePackages = "org.kockpit.backend.security")
class LocalAuthTestApplication {

    @RestController
    static class ProtectedEndpoint {
        @GetMapping("/insurance/dev/audits/_search")
        String audits(Authentication authentication) {
            return "audits for " + authentication.getName();
        }
    }
}
