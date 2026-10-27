package com.agrosphere.operations;

import java.sql.DriverManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Only disposable Testcontainers databases; no login or password is provisioned. */
final class CollectionTestSupport {
  private CollectionTestSupport() {}

  static void provisionNoLoginRuntimeRole(PostgreSQLContainer postgres) throws Exception {
    try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("""
          DO $$ BEGIN
           IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='agrosphere_operations_app') THEN
            CREATE ROLE agrosphere_operations_app NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
           END IF;
          END $$
          """);
    }
  }
}
