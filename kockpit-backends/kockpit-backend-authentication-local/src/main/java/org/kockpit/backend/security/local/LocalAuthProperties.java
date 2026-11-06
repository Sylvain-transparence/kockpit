package org.kockpit.backend.security.local;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** {@code kockpit.security.local.*}: local user accounts, see {@link LocalAuthConfiguration}. */
@Data
@ConfigurationProperties("kockpit.security.local")
public class LocalAuthProperties {

    private boolean enabled;

    /** HMAC key the login tokens are signed with: at least 32 bytes, the same on every backend replica. */
    private String jwtSecret;

    /** How long a login lasts (token and cookie lifetime). */
    private Duration tokenTtl = Duration.ofHours(8);

    /** Secure cookie flag: keep it on anywhere but plain-HTTP local runs. */
    private boolean cookieSecure = true;

    private int passwordMinLength = 10;

    private Datasource datasource = new Datasource();

    private Admin admin = new Admin();

    private Signup signup = new Signup();

    private Login login = new Login();

    @Data
    public static class Datasource {
        private String url = "jdbc:postgresql://localhost:5432/kockpit";
        private String username = "kockpit";
        private String password;
        private int maximumPoolSize = 5;
    }

    /** Account created at startup when absent (email and password set), and given {@link #group}. */
    @Data
    public static class Admin {
        private String email;
        private String password;
        /** List it in kockpit.security.access-control.admin-groups to make it an administrator. */
        private String group = "kockpit-admins";
    }

    @Data
    public static class Signup {
        private boolean enabled = true;
        /** Accounts one client IP may create per hour. */
        private int perIpPerHour = 5;
        /** Accounts created per hour overall, whatever the IP. */
        private int perHour = 50;
    }

    @Data
    public static class Login {
        /** Failed logins on one email before it is locked for {@link #lockout}. */
        private int maxFailuresPerEmail = 10;
        /** Failed logins from one client IP before it is locked for {@link #lockout}. */
        private int maxFailuresPerIp = 50;
        private Duration lockout = Duration.ofMinutes(15);
    }
}
