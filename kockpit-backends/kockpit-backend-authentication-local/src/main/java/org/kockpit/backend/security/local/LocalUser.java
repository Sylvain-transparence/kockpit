package org.kockpit.backend.security.local;

import java.util.UUID;

public record LocalUser(UUID id, String email, String passwordHash, boolean enabled) {
}
