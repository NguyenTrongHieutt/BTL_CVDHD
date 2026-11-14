package vn.nhom17.banhang.inventory.internal.stock;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import vn.nhom17.banhang.inventory.StockQuery;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.PageResponse;

@Repository
public class JdbcStockQuery implements StockQuery {

    private static final RowMapper<StockView> STOCK_ROW_MAPPER = (rs, rowNum) ->
        new StockView(
            rs.getObject("variant_id", UUID.class),
            rs.getInt("quantity"),
            rs.getInt("min_quantity"),
            rs.getLong("avg_unit_cost"),
            rs.getBoolean("is_active")
        );

    @Transactional(readOnly = true)
    public PageResponse<StockView> search(Collection<UUID> variantIds, Collection<UUID> activeVariantIds, Boolean belowMin, boolean includeInactive,
                                          int page, int size) {
        if (variantIds.isEmpty()) {
            return new PageResponse<>(List.of(), 0, page, size);
        }

        String where = """
                FROM (
                    SELECT variant_id, quantity, min_quantity, avg_unit_cost, store_id,
                           is_active AND variant_id = ANY(string_to_array(?, ',')::uuid[]) AS is_active
                    FROM app.stock
                ) stock
                WHERE store_id = ? AND variant_id = ANY(string_to_array(?, ',')::uuid[])
                """;
        if (!includeInactive) {
            where += " AND is_active";
        }
        if (belowMin != null) {
            where += belowMin ? " AND (is_active AND quantity < min_quantity)"
                             : " AND NOT (is_active AND quantity < min_quantity)";
        }
        UUID storeId = context.storeId();
        String ids = String.join(",", variantIds.stream().map(UUID::toString).toList());
        String activeIds = String.join(",", activeVariantIds.stream().map(UUID::toString).toList());
        Long total = jdbc.queryForObject("SELECT count(*) " + where, Long.class, activeIds, storeId, ids);
        List<StockView> items = jdbc.query(
                "SELECT variant_id, quantity, min_quantity, avg_unit_cost, is_active " + where
                        + " ORDER BY variant_id LIMIT ? OFFSET ?",
                STOCK_ROW_MAPPER, activeIds, storeId, ids, size, (long) page * size);
        return new PageResponse<>(items, total, page, size);
    }

    private final JdbcTemplate jdbc;
    private final CurrentContext context;

    public JdbcStockQuery(JdbcTemplate jdbc, CurrentContext context) {
        this.jdbc = jdbc;
        this.context = context;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockView> lowStock() {
        return jdbc.query(
            """
            SELECT variant_id, quantity, min_quantity, avg_unit_cost, is_active
            FROM app.stock
            WHERE store_id = ?
              AND quantity < min_quantity
              AND is_active = true
            """,
            STOCK_ROW_MAPPER,
            context.storeId()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockView> find(Collection<UUID> variantIds) {
        if (variantIds == null || variantIds.isEmpty()) {
            return List.of();
        }

        String placeholders = String.join(
            ", ",
            Collections.nCopies(variantIds.size(), "?")
        );

        List<Object> params = new ArrayList<>();
        params.add(context.storeId());
        params.addAll(variantIds);

        return jdbc.query(
            """
            SELECT variant_id, quantity, min_quantity, avg_unit_cost, is_active
            FROM app.stock
            WHERE store_id = ?
              AND variant_id IN (%s)
            """.formatted(placeholders),
            STOCK_ROW_MAPPER,
            params.toArray()
        );
    }
}