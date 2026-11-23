package com.eonmux.chatllm.lifecycle;

import com.eonmux.chatllm.config.AppConfig;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.UUID;

/**
 * Quarkus application lifecycle bean.
 * Replaces MainVerticle startup/shutdown logic.
 *
 * <p>Quarkus handles the HTTP server, GraphQL endpoint, health checks,
 * metrics, and datasource/Redis connections automatically via extensions.
 * This bean handles application-specific startup tasks such as seeding
 * the debug user when auth-bypass is enabled.</p>
 */
@Slf4j
@ApplicationScoped
public class ApplicationLifecycle {

    private static final UUID DEBUG_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    // Environment names that trigger production-grade validation.
    // Any value NOT in the dev/test set is treated as production-like.
    private static final Set<String> DEV_ENVIRONMENTS = Set.of("development", "dev", "test", "testing", "local");
    private static final String DEFAULT_SENTINEL_SECRET = "change-this-secret-in-production";

    @Inject
    io.vertx.mutiny.mysqlclient.MySQLPool pool;

    @Inject
    AppConfig config;

    /**
     * Called when Quarkus has finished starting all extensions and the
     * application is ready to serve requests.
     */
    void onStart(@Observes StartupEvent ev) {
        log.info("Veyloria Quarkus backend starting...");
        log.info("  Version   : {}", config.getAppVersion());
        log.info("  Env       : {}", config.getEnvironment());
        log.info("  HTTP port : {}", config.getHttpPort());
        log.info("  Dev mode  : {}", config.isDevelopmentMode());
        log.info("  LLM       : enabled={}, mock={}, external={}",
                config.isLlmEnabled(), config.isLlmMockMode(), config.isLlmExternalServer());

        validateProductionConfig();
        ensureUploadsDirectory();
        ensureRelationshipSchema();
        seedDebugUser();

        log.info("Veyloria backend started successfully");
    }

    /** Additive relationship and alignment tables for existing installations and fresh databases. */
    private void ensureRelationshipSchema() {
        runMigration("/db/migrations/character-affinity.sql", "character relationships");
        runMigration("/db/migrations/character-alignment.sql", "character alignments");
        runMigration("/db/migrations/character-groups.sql", "character groups");
        runMigration("/db/migrations/local-maps.sql", "local maps");
        runMigration("/db/migrations/inventory-activity.sql", "inventory activity");
        runMigration("/db/migrations/journey-encounters.sql", "journey encounters");
        ensureColumn("character", "initialAffinityToUser",
                "ALTER TABLE `character` ADD COLUMN initialAffinityToUser INT NOT NULL DEFAULT 0");
        ensureColumn("character", "initialAffinityFromUser",
                "ALTER TABLE `character` ADD COLUMN initialAffinityFromUser INT NOT NULL DEFAULT 0");
        ensureColumn("conversation_character", "isIntroduced",
                "ALTER TABLE conversation_character ADD COLUMN isIntroduced BOOLEAN NOT NULL DEFAULT TRUE");
        ensureColumn("conversation_character", "introducedAt",
                "ALTER TABLE conversation_character ADD COLUMN introducedAt TIMESTAMP NULL");
        ensureColumn("world_state", "currentArea",
                "ALTER TABLE world_state ADD COLUMN currentArea VARCHAR(255) NULL");
        ensureColumn("world_state", "currentAreaId",
                "ALTER TABLE world_state ADD COLUMN currentAreaId VARCHAR(36) NULL");
        for (String[] column : new String[][]{
                {"gender", "ALTER TABLE `character` ADD COLUMN gender VARCHAR(60) NULL"},
                {"pronouns", "ALTER TABLE `character` ADD COLUMN pronouns VARCHAR(40) NULL"},
                {"ageYears", "ALTER TABLE `character` ADD COLUMN ageYears INT NULL"},
                {"height", "ALTER TABLE `character` ADD COLUMN height VARCHAR(60) NULL"},
                {"build", "ALTER TABLE `character` ADD COLUMN build VARCHAR(80) NULL"},
                {"hair", "ALTER TABLE `character` ADD COLUMN hair VARCHAR(120) NULL"},
                {"eyes", "ALTER TABLE `character` ADD COLUMN eyes VARCHAR(80) NULL"},
                {"strength", "ALTER TABLE `character` ADD COLUMN strength INT NULL"},
                {"dexterity", "ALTER TABLE `character` ADD COLUMN dexterity INT NULL"},
                {"constitution", "ALTER TABLE `character` ADD COLUMN constitution INT NULL"},
                {"intelligence", "ALTER TABLE `character` ADD COLUMN intelligence INT NULL"},
                {"wisdom", "ALTER TABLE `character` ADD COLUMN wisdom INT NULL"},
                {"charisma", "ALTER TABLE `character` ADD COLUMN charisma INT NULL"},
        }) {
            ensureColumn("character", column[0], column[1]);
        }
    }

    /** Adds a column only when it is absent, so repeated startups stay idempotent. */
    private void ensureColumn(String table, String column, String ddl) {
        try {
            var rows = pool.preparedQuery(
                            "SELECT COUNT(*) AS found FROM information_schema.columns "
                                    + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?")
                    .execute(io.vertx.mutiny.sqlclient.Tuple.of(table, column))
                    .await().atMost(java.time.Duration.ofSeconds(30));
            long found = rows.iterator().next().getLong("found");
            if (found == 0) {
                pool.query(ddl).execute().await().atMost(java.time.Duration.ofSeconds(30));
                log.info("Added column {}.{}", table, column);
            }
        } catch (Exception error) {
            throw new IllegalStateException("Unable to add column " + table + "." + column, error);
        }
    }

    private void runMigration(String resource, String description) {
        try (var stream = getClass().getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("Missing schema resource: " + resource);
            String sql = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            sql = sql.replaceAll("(?m)^--.*$", "");
            for (String statement : sql.split(";")) {
                if (!statement.isBlank()) pool.query(statement).execute().await().atMost(java.time.Duration.ofSeconds(30));
            }
        } catch (Exception error) {
            throw new IllegalStateException("Unable to initialize " + description, error);
        }
    }

    private void validateProductionConfig() {
        String env = config.getEnvironment();
        if (env == null || DEV_ENVIRONMENTS.contains(env.toLowerCase())) {
            return;
        }

        log.info("Production-like environment '{}' detected — running config validation", env);

        String secret = config.getJwtSecret();
        if (DEFAULT_SENTINEL_SECRET.equals(secret) || secret.length() < 32) {
            throw new IllegalStateException(
                "FATAL: chatllm.jwt.secret must be at least 32 characters and must not use the default sentinel value in a production-like environment (env=" + env + ").");
        }
        if (config.isAuthBypassEnabled()) {
            throw new IllegalStateException(
                "FATAL: chatllm.auth-bypass-enabled must be false in a production-like environment (env=" + env + ").");
        }
        if ("*".equals(config.getAllowedOrigins())) {
            log.warn("WARNING: chatllm.allowed-origins is set to '*' — tighten this for production.");
        }
        log.info("Production config validation passed");
    }

    /**
     * Called when Quarkus is shutting down.
     */
    void onStop(@Observes ShutdownEvent ev) {
        log.info("Veyloria backend shutting down...");
        // Quarkus automatically closes the MySQLPool and Redis connections
        // managed by its extensions, so no manual cleanup is needed here.
        log.info("Veyloria backend stopped");
    }

    /**
     * Ensure the uploads directory exists with restrictive permissions (owner-only).
     */
    private void ensureUploadsDirectory() {
        try {
            java.nio.file.Path uploadsDir = java.nio.file.Paths.get(config.getUploadsDirectory());
            if (!java.nio.file.Files.exists(uploadsDir)) {
                java.nio.file.Files.createDirectories(uploadsDir);
            }
            // Set permissions to owner-only (rwx------ = 700)
            try {
                java.nio.file.Files.setPosixFilePermissions(uploadsDir,
                    java.util.Set.of(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                        java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
            } catch (UnsupportedOperationException e) {
                // Non-POSIX filesystem (e.g. Windows) — skip permission setting
            }
            log.info("Uploads directory: {}", uploadsDir);
        } catch (Exception e) {
            log.warn("Failed to create/set uploads directory permissions: {}", e.getMessage());
        }
    }

    /**
     * When AUTH_BYPASS_ENABLED is true, ensure the synthetic debug user exists
     * in the database so FK constraints on ownerId do not fail.
     */
    private void seedDebugUser() {
        if (!config.isAuthBypassEnabled()) {
            return;
        }

        log.info("Auth bypass enabled -- seeding debug user {}", DEBUG_USER_ID);

        String sql = "INSERT IGNORE INTO user (userUUID, username, passwordHash, displayName, role, admin) "
                + "VALUES (?, 'debug', '', 'Debug User', 'ADMIN', TRUE)";

        pool.preparedQuery(sql)
                .execute(io.vertx.mutiny.sqlclient.Tuple.of(DEBUG_USER_ID.toString()))
                .onFailure().invoke(err ->
                        log.error("Failed to seed debug user: {}", err.getMessage(), err))
                .subscribe().with(
                        rows -> log.info("Debug user seeded (rows affected: {})", rows.rowCount()),
                        err -> log.error("Failed to seed debug user: {}", err.getMessage(), err));
    }
}
