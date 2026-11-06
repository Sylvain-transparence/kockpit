package org.kockpit.backend.security.local;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Users and their groups (tables of db/kockpit-accounts). Emails are compared lower-cased. */
public class UserRepository {

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates the user, or returns empty when the email is already taken. */
    public Optional<LocalUser> create(String email, String passwordHash) {
        UUID id = UUID.randomUUID();
        String normalized = normalize(email);
        try {
            jdbc.sql("insert into users (id, email, password_hash) values (:id, :email, :hash)")
                    .param("id", id)
                    .param("email", normalized)
                    .param("hash", passwordHash)
                    .update();
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
        return Optional.of(new LocalUser(id, normalized, passwordHash, true));
    }

    public Optional<LocalUser> findByEmail(String email) {
        return jdbc.sql("select id, email, password_hash, enabled from users where lower(email) = :email")
                .param("email", normalize(email))
                .query(LocalUser.class)
                .optional();
    }

    public Optional<LocalUser> findById(UUID id) {
        return jdbc.sql("select id, email, password_hash, enabled from users where id = :id")
                .param("id", id)
                .query(LocalUser.class)
                .optional();
    }

    public List<String> groups(UUID userId) {
        return jdbc.sql("select group_name from user_groups where user_id = :id order by group_name")
                .param("id", userId)
                .query(String.class)
                .list();
    }

    public void addGroup(UUID userId, String group) {
        jdbc.sql("insert into user_groups (user_id, group_name) values (:id, :group) on conflict do nothing")
                .param("id", userId)
                .param("group", group)
                .update();
    }

    public void setEnabled(UUID userId, boolean enabled) {
        jdbc.sql("update users set enabled = :enabled where id = :id")
                .param("id", userId)
                .param("enabled", enabled)
                .update();
    }

    public void touchLogin(UUID userId) {
        jdbc.sql("update users set last_login_at = now() where id = :id")
                .param("id", userId)
                .update();
    }

    static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
