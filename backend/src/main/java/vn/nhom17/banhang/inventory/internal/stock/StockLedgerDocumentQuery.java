package vn.nhom17.banhang.inventory.internal.stock;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.nhom17.banhang.catalog.SellableVariant;
import vn.nhom17.banhang.catalog.SupplierLookup;
import vn.nhom17.banhang.catalog.VariantLookup;
import vn.nhom17.banhang.inventory.StockDocumentType;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.ProblemException;
import vn.nhom17.banhang.platform.UserNameLookup;

/** Read the complete source document independently of the ledger's current filters. */
@Repository
public class StockLedgerDocumentQuery {
    private final JdbcTemplate jdbc;
    private final CurrentContext context;
    private final VariantLookup variants;
    private final SupplierLookup suppliers;
    private final UserNameLookup users;

    public StockLedgerDocumentQuery(JdbcTemplate jdbc, CurrentContext context, VariantLookup variants,
            SupplierLookup suppliers, UserNameLookup users) {
        this.jdbc = jdbc;
        this.context = context;
        this.variants = variants;
        this.suppliers = suppliers;
        this.users = users;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Document find(StockDocumentType type, UUID id) {
        UUID storeId = context.storeId();
        return switch (type) {
            case OPENING -> opening(storeId, id);
            case PURCHASE_RECEIPT -> receipt(storeId, id);
            case STOCKTAKE -> stocktake(storeId, id);
            default -> throw notFound();
        };
    }

    private Document opening(UUID storeId, UUID id) {
        var rows = jdbc.query("""
                SELECT line_no, variant_id, delta, unit_cost, created_at, created_by
                FROM app.stock_ledger
                WHERE store_id = ? AND document_type = 'OPENING' AND document_id = ? AND variant_id = ?
                ORDER BY line_no
                """, (rs, row) -> new OpeningRow(rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("created_by", UUID.class), new LineRow(rs.getInt("line_no"),
                rs.getObject("variant_id", UUID.class), rs.getInt("delta"), rs.getLong("unit_cost"),
                null, null, null)), storeId, id, id);
        if (rows.isEmpty()) throw notFound();

        List<Line> lines = enrich(rows.stream().map(OpeningRow::line).toList());
        long totalAmount = 0;
        for (Line line : lines) totalAmount = Math.addExact(totalAmount, line.amount());
        OpeningRow header = rows.getFirst();
        return new Document(StockDocumentType.OPENING, id, null, header.recordedAt(), actorName(header.actorId()),
                null, null, totalAmount, lines);
    }

    private Document receipt(UUID storeId, UUID id) {
        var header = jdbc.query("""
                SELECT code, received_at, created_by, supplier_id, note, total_amount
                FROM app.purchase_receipt WHERE store_id = ? AND id = ?
                """, (rs, row) -> new ReceiptHeader(rs.getString("code"),
                rs.getObject("received_at", OffsetDateTime.class), rs.getObject("created_by", UUID.class),
                rs.getObject("supplier_id", UUID.class), rs.getString("note"), rs.getLong("total_amount")),
                storeId, id).stream().findFirst().orElseThrow(StockLedgerDocumentQuery::notFound);
        var rows = jdbc.query("""
                SELECT l.line_no, l.variant_id, l.quantity, l.unit_cost
                FROM app.purchase_receipt_line l
                JOIN app.purchase_receipt r ON r.store_id = l.store_id AND r.id = l.receipt_id
                WHERE r.store_id = ? AND r.id = ?
                ORDER BY l.line_no
                """, (rs, row) -> new LineRow(rs.getInt("line_no"), rs.getObject("variant_id", UUID.class),
                rs.getInt("quantity"), rs.getLong("unit_cost"), null, null, null), storeId, id);

        String supplierName = suppliers.findById(header.supplierId()).map(SupplierLookup.SupplierSummary::name)
                .orElse(null);
        return new Document(StockDocumentType.PURCHASE_RECEIPT, id, header.code(), header.recordedAt(),
                actorName(header.actorId()), supplierName, header.note(), header.totalAmount(), enrich(rows));
    }

    private Document stocktake(UUID storeId, UUID id) {
        var header = jdbc.query("""
                SELECT created_at, created_by, note
                FROM app.stocktake WHERE store_id = ? AND id = ?
                """, (rs, row) -> new StocktakeHeader(rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("created_by", UUID.class), rs.getString("note")),
                storeId, id).stream().findFirst().orElseThrow(StockLedgerDocumentQuery::notFound);
        var rows = jdbc.query("""
                SELECT l.line_no, l.variant_id, l.counted_quantity, l.system_quantity,
                       l.counted_quantity - l.system_quantity AS delta, l.reason
                FROM app.stocktake_line l
                JOIN app.stocktake s ON s.store_id = l.store_id AND s.id = l.stocktake_id
                WHERE s.store_id = ? AND s.id = ?
                ORDER BY l.line_no
                """, (rs, row) -> new LineRow(rs.getInt("line_no"), rs.getObject("variant_id", UUID.class),
                rs.getInt("counted_quantity"), null, rs.getInt("system_quantity"), rs.getInt("delta"),
                rs.getString("reason")), storeId, id);
        return new Document(StockDocumentType.STOCKTAKE, id, null, header.recordedAt(),
                actorName(header.actorId()), null, header.note(), null, enrich(rows));
    }

    private List<Line> enrich(List<LineRow> rows) {
        var variantIds = rows.stream().map(LineRow::variantId).collect(Collectors.toSet());
        Map<UUID, SellableVariant> byId = variants.findForInventory(null, null, true).stream()
                .filter(variant -> variantIds.contains(variant.variantId()))
                .collect(Collectors.toMap(SellableVariant::variantId, Function.identity()));
        return rows.stream().map(row -> {
            SellableVariant variant = byId.get(row.variantId());
            Long amount = row.unitAmount() == null ? null : Math.multiplyExact(row.quantity(), row.unitAmount());
            return new Line(row.lineNo(), variant == null ? null : variant.productName(),
                    variant == null ? null : variant.variantName(), variant == null ? null : variant.sku(),
                    row.quantity(), row.unitAmount(), amount, row.systemQuantity(), row.delta(), row.reason());
        }).toList();
    }

    private String actorName(UUID actorId) {
        return actorId == null ? null : users.findNames(List.of(actorId)).get(actorId);
    }

    private static ProblemException notFound() {
        return ProblemException.notFound("/errors/common/not-found", "Không tìm thấy chứng từ",
                "Chứng từ không tồn tại trong cửa hàng hiện tại.");
    }

    private record OpeningRow(OffsetDateTime recordedAt, UUID actorId, LineRow line) {}
    private record ReceiptHeader(String code, OffsetDateTime recordedAt, UUID actorId, UUID supplierId,
                                 String note, long totalAmount) {}
    private record StocktakeHeader(OffsetDateTime recordedAt, UUID actorId, String note) {}
    private record LineRow(int lineNo, UUID variantId, int quantity, Long unitAmount,
                           Integer systemQuantity, Integer delta, String reason) {}

    public record Document(StockDocumentType documentType, UUID documentId, String code, OffsetDateTime recordedAt,
                           String actorName, String supplierName, String note, Long totalAmount, List<Line> lines) {}

    public record Line(int lineNo, String productName, String variantName, String sku, int quantity,
                       Long unitAmount, Long amount, Integer systemQuantity, Integer delta, String reason) {}
}
