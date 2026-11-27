package vn.nhom17.banhang.platform.internal.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReplicaDataSourceTest {
    @Test
    void selectsOnlyCaughtUpReplicasAndReturnsToPrimaryOutsideMarkedRequest() throws SQLException {
        var primary = mock(HikariDataSource.class);
        var source = mock(Connection.class);
        var command = mock(Statement.class);
        var lsn = mock(ResultSet.class);
        when(primary.getConnection()).thenReturn(source);
        when(source.createStatement()).thenReturn(command);
        when(command.executeQuery(anyString())).thenReturn(lsn);
        when(lsn.next()).thenReturn(true);
        when(lsn.getString(1)).thenReturn("0/100");

        var first = new Standby(true);
        var second = new Standby(false);
        try (var router = new ReplicaDataSource(primary, List.of(first.pool, second.pool))) {
            router.beginRead();
            assertThat(router.getConnection()).isSameAs(source);
            router.endRead();

            router.checkReplication();
            router.beginRead();
            assertThat(router.getConnection()).isSameAs(first.connection);
            assertThat(router.getConnection()).isSameAs(first.connection);
            router.endRead();
            assertThat(router.getConnection()).isSameAs(source);

            when(second.rows.getBoolean(1)).thenReturn(true);
            router.checkReplication();
            router.beginRead();
            assertThat(router.getConnection()).isSameAs(first.connection);
            router.endRead();
            router.beginRead();
            assertThat(router.getConnection()).isSameAs(second.connection);
            when(second.pool.getConnection()).thenThrow(new SQLException("Connection lost"));
            assertThat(router.getConnection()).isSameAs(source);
            router.endRead();
        }
    }

    private static class Standby {
        private final HikariDataSource pool = mock(HikariDataSource.class);
        private final Connection connection = mock(Connection.class);
        private final ResultSet rows = mock(ResultSet.class);

        Standby(boolean caughtUp) throws SQLException {
            var command = mock(PreparedStatement.class);
            when(pool.getConnection()).thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(command);
            when(command.executeQuery()).thenReturn(rows);
            when(rows.next()).thenReturn(true);
            when(rows.getBoolean(1)).thenReturn(caughtUp);
        }
    }
}
