package com.eonmux.chatllm.config;

import io.quarkus.reactive.mysql.client.MySQLPoolCreator;
import io.vertx.core.Future;
import io.vertx.mysqlclient.MySQLPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlConnection;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/** Keeps SQL defaults and NOW() consistent with the backend's UTC date handling. */
@Singleton
@Slf4j
public class UtcMySQLPoolCreator implements MySQLPoolCreator {
    @Override
    public Pool create(Input input) {
        return MySQLPool.pool(input.vertx(), input.mySQLConnectOptionsList(), input.poolOptions())
                .connectHandler(connection -> initialize(connection)
                        .onFailure(error -> log.error("Failed to initialize MySQL connection in UTC", error)));
    }

    // A connectHandler owns a connection lease until close() releases it to the
    // pool. Do not release it until the session timezone has been initialized.
    static Future<Void> initialize(SqlConnection connection) {
        return connection.query("SET time_zone = '+00:00'").execute()
                .compose(ignored -> connection.close(),
                        failure -> connection.close().compose(ignored -> Future.failedFuture(failure)));
    }
}
