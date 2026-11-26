package vn.nhom17.banhang.platform.internal.user;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.UserNameLookup;

@Component
class JdbcUserNameLookup implements UserNameLookup {
    private final JdbcTemplate jdbc;
    private final CurrentContext context;

    JdbcUserNameLookup(JdbcTemplate jdbc, CurrentContext context) {
        this.jdbc = jdbc;
        this.context = context;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> findNames(Collection<UUID> userIds) {
        if (userIds.isEmpty()) return Map.of();

        return jdbc.query(connection -> {
            var statement = connection.prepareStatement("""
                SELECT id, display_name FROM app.app_user
                WHERE store_id = ? AND id = ANY(?)
                """);
            statement.setObject(1, context.storeId());
            statement.setArray(2, connection.createArrayOf("uuid", userIds.toArray()));
            return statement;
        }, rs -> {
            Map<UUID, String> names = new HashMap<>();
            while (rs.next()) {
                names.put(rs.getObject("id", UUID.class), rs.getString("display_name"));
            }
            return names;
        });
    }
}
