package org.kockpit.backend.tenants;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** API key format: "kpk_" + 43 base64url chars (256 random bits). Stored as its SHA-256. */
public final class ApiKeys {

    static final String PREFIX = "kpk_";

    // what the console shows to tell keys apart: "kpk_" + 8 chars
    static final int DISPLAY_PREFIX_LENGTH = 12;

    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeys() {
    }

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String displayPrefix(String key) {
        return key.substring(0, DISPLAY_PREFIX_LENGTH);
    }

    /** Lower-case hex SHA-256: a random 256-bit key needs no salt or slow hash. */
    public static String hash(String key) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
