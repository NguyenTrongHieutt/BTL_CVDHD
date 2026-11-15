package vn.nhom17.banhang.inventory.internal.stock;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import vn.nhom17.banhang.catalog.SellableVariant;
import vn.nhom17.banhang.catalog.VariantLookup;
import vn.nhom17.banhang.inventory.StockDocumentType;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.PageResponse;
import vn.nhom17.banhang.platform.ProblemException;
import vn.nhom17.banhang.platform.TimeZones;
import vn.nhom17.banhang.platform.UserNameLookup;

@Repository
public class StockLedgerQuery {
    private final JdbcTemplate jdbc;
    private final CurrentContext context;
    private final VariantLookup variants;
    private final UserNameLookup users;

    public StockLedgerQuery(JdbcTemplate jdbc, CurrentContext context, VariantLookup variants, UserNameLookup users) {
        this.jdbc = jdbc;
        this.context = context;
        this.variants = variants;
        this.users = users;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<Entry> find(UUID variantId, StockDocumentType documentType, LocalDate fromDate,
                                    LocalDate toDate, int page, Integer size) {
        validateRange(fromDate, toDate);
        int pageNo = Math.max(0, page);
        int pageSize = PageResponse.clampSize(size);
        StringBuilder where = new StringBuilder(" FROM app.stock_ledger WHERE store_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(context.storeId());

        if (variantId != null) {
            where.append(" AND variant_id = ?");
            args.add(variantId);
        }
        if (documentType != null) {
            where.append(" AND document_type = ?");
            args.add(documentType.name());
        }
        if (fromDate != null) {
            where.append(" AND created_at >= ?");
            args.add(OffsetDateTime.ofInstant(fromDate.atStartOfDay(TimeZones.VIETNAM).toInstant(), ZoneOffset.UTC));
        }
        if (toDate != null) {
            where.append(" AND created_at < ?");
            args.add(OffsetDateTime.ofInstant(toDate.plusDays(1).atStartOfDay(TimeZones.VIETNAM).toInstant(), ZoneOffset.UTC));
        }

        long total = jdbc.queryForObject("SELECT count(*)" + where, Long.class, args.toArray());
        long offset = (long) pageNo * pageSize;
        if (offset >= total) return new PageResponse<>(List.of(), total, pageNo, pageSize);

        args.add(pageSize);
        args.add(offset);
        List<Row> rows = jdbc.query("""
                SELECT created_at, document_type, document_id, variant_id, delta, quantity_after, created_by
                """ + where + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?", (rs, index) -> new Row(
                rs.getObject("created_at", OffsetDateTime.class),
                StockDocumentType.valueOf(rs.getString("document_type")),
                rs.getObject("document_id", UUID.class),
                rs.getObject("variant_id", UUID.class),
                rs.getInt("delta"),
                rs.getInt("quantity_after"),
                rs.getObject("created_by", UUID.class)
        ), args.toArray());

        var variantIds = rows.stream().map(Row::variantId).collect(Collectors.toSet());
        Map<UUID, SellableVariant> variantsById = variants.findForInventory(null, null, true).stream()
                .filter(variant -> variantIds.contains(variant.variantId()))
                .collect(Collectors.toMap(SellableVariant::variantId, Function.identity()));
        Map<UUID, String> actorNames = users.findNames(
                rows.stream().map(Row::actorId).filter(Objects::nonNull).distinct().toList());

        List<Entry> entries = rows.stream().map(row -> {
            SellableVariant variant = variantsById.get(row.variantId());
            return new Entry(row.createdAt(), row.documentType(), row.documentId(), row.variantId(),
                    variant == null ? null : variant.productName(), variant == null ? null : variant.variantName(),
                    row.delta(), row.quantityAfter(), row.actorId() == null ? null : actorNames.get(row.actorId()));
        }).toList();
        return new PageResponse<>(entries, total, pageNo, pageSize);
    }

    private static void validateRange(LocalDate fromDate, LocalDate toDate) {
        if (fromDate == null || toDate == null) return;
        long days = ChronoUnit.DAYS.between(fromDate, toDate);
        if (days < 0 || days >= 366) {
            String message = "Khoảng ngày phải theo thứ tự và tối đa 366 ngày.";
            throw new ProblemException(HttpStatus.BAD_REQUEST, "/errors/common/validation",
                    "Dữ liệu không hợp lệ", message,
                    Map.of("errors", List.of(Map.of("field", "toDate", "message", message))));
        }
    }

    private record Row(OffsetDateTime createdAt, StockDocumentType documentType, UUID documentId,
                       UUID variantId, int delta, int quantityAfter, UUID actorId) {}

    public record Entry(OffsetDateTime createdAt, StockDocumentType documentType, UUID documentId,
                        UUID variantId, String productName, String variantName, int delta,
                        int quantityAfter, String actorName) {}
}
