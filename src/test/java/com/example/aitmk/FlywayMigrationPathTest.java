package com.example.aitmk;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationPathTest {
    private static final String H2_MIGRATIONS = "classpath:db/migration-h2";

    @Test void emptyDatabaseMigratesThroughV1AndV2() throws Exception {
        String url="jdbc:h2:mem:flyway_empty;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway flyway=Flyway.configure().dataSource(url,"sa","").locations(H2_MIGRATIONS).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(11);
        assertV2(url);
    }

    @Test void legacyV1SchemaWithoutHistoryCanBeBaselinedThenUpgraded() throws Exception {
        String url="jdbc:h2:mem:flyway_legacy;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").locations(H2_MIGRATIONS).target("1").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement()){s.execute("drop table flyway_schema_history");}
        Flyway flyway=Flyway.configure().dataSource(url,"sa","").locations(H2_MIGRATIONS)
                .baselineOnMigrate(true).baselineVersion(MigrationVersion.fromVersion("1")).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(10);
        assertV2(url);
    }

    private void assertV2(String url)throws Exception{try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement()){var columns=s.executeQuery("select count(*) from information_schema.columns where table_name='conversation' and column_name='version'");columns.next();assertThat(columns.getInt(1)).isEqualTo(1);var tables=s.executeQuery("select count(*) from information_schema.tables where table_name='realtime_event'");tables.next();assertThat(tables.getInt(1)).isEqualTo(1);var quickReplies=s.executeQuery("select count(*) from information_schema.tables where table_name='agent_quick_replies'");quickReplies.next();assertThat(quickReplies.getInt(1)).isEqualTo(1);}}
}
