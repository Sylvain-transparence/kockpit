package org.kockpit.gateway;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.OptionalLong;
import java.util.UUID;

/** Audits accepted per key and UTC day (usage_daily), shown in the console against the quota. */
@Component
public class UsageRepository {

    private final JdbcClient jdbc;

    public UsageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Adds {@code count} to the key's usage of {@code day} if that stays within {@code quota}, in
     * one statement: concurrent requests (or gateway replicas) can't overshoot the quota.
     *
     * @return the new total, or empty when the whole batch doesn't fit (nothing is counted)
     */
    public OptionalLong tryConsume(UUID keyId, LocalDate day, int count, int quota) {
        return jdbc.sql("""
                        insert into usage_daily (api_key_id, day, audits)
                        select :id, :day, :count where :count <= :quota
                        on conflict (api_key_id, day) do update
                            set audits = usage_daily.audits + excluded.audits
                            where usage_daily.audits + excluded.audits <= :quota
                        returning audits""")
                .param("id", keyId).param("day", day).param("count", count).param("quota", quota)
                .query(Long.class)
                .optional()
                .map(OptionalLong::of)
                .orElse(OptionalLong.empty());
    }

    /** Gives back audits counted for a batch that couldn't be delivered. */
    public void refund(UUID keyId, LocalDate day, int count) {
        jdbc.sql("update usage_daily set audits = greatest(audits - :count, 0) where api_key_id = :id and day = :day")
                .param("id", keyId).param("day", day).param("count", count)
                .update();
    }

    public long used(UUID keyId, LocalDate day) {
        return jdbc.sql("select audits from usage_daily where api_key_id = :id and day = :day")
                .param("id", keyId).param("day", day)
                .query(Long.class).optional().orElse(0L);
    }

    /** At most once a minute per key, so a busy key doesn't rewrite its row on every call. */
    public void touch(UUID keyId) {
        jdbc.sql("""
                        update api_keys set last_used_at = now()
                        where id = :id and (last_used_at is null or last_used_at < now() - interval '1 minute')""")
                .param("id", keyId)
                .update();
    }
}
