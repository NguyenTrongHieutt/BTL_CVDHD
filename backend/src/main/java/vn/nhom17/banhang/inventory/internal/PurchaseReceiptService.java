package vn.nhom17.banhang.inventory.internal;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.nhom17.banhang.catalog.SupplierLookup;
import vn.nhom17.banhang.inventory.StockDocumentType;
import vn.nhom17.banhang.inventory.StockService;
import vn.nhom17.banhang.inventory.StockService.StockLine;
import vn.nhom17.banhang.inventory.internal.PurchaseReceiptController.CreateReceipt;
import vn.nhom17.banhang.inventory.internal.PurchaseReceiptController.LineRequest;
import vn.nhom17.banhang.inventory.internal.PurchaseReceiptController.Receipt;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.ProblemException;

/** Shared receipt rules and persistence for manual entry and mixed file import. */
@Service
class PurchaseReceiptService {
    static final long MAX_MONEY = 9_007_199_254_740_991L;
    private final JdbcTemplate jdbc;
    private final CurrentContext context;
    private final SupplierLookup suppliers;
    private final StockService stock;
    private final Clock clock;

    PurchaseReceiptService(JdbcTemplate jdbc, CurrentContext context, SupplierLookup suppliers,
            StockService stock, Clock clock) {
        this.jdbc = jdbc;
        this.context = context;
        this.suppliers = suppliers;
        this.stock = stock;
        this.clock = clock;
    }

    record ValidatedReceipt(UUID supplierId, OffsetDateTime receivedAt, String note,
            List<StockLine> lines, long totalAmount) {}

    @Transactional(propagation = Propagation.MANDATORY)
    UUID save(ValidatedReceipt input) {
        if (suppliers.findById(input.supplierId()).isEmpty()) {
            throw validation("Nhà cung cấp không hợp lệ trong cửa hàng hiện tại.");
        }
        UUID storeId = context.storeId();
        UUID receiptId = UUID.randomUUID();
        String code = "PN-" + receiptId.toString().toUpperCase(Locale.ROOT);
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        OffsetDateTime receivedAt = input.receivedAt() == null ? now : input.receivedAt();
        jdbc.update("""
                INSERT INTO app.purchase_receipt
                    (id, store_id, code, supplier_id, received_at, note, total_amount, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, receiptId, storeId, code, input.supplierId(), receivedAt, input.note(), input.totalAmount(),
                context.userId(), now);
        stock.apply(StockDocumentType.PURCHASE_RECEIPT, receiptId,
                input.lines().stream().sorted(Comparator.comparing(StockLine::variantId)).toList());
        jdbc.batchUpdate("""
                INSERT INTO app.purchase_receipt_line
                    (store_id, receipt_id, line_no, variant_id, quantity, unit_cost)
                VALUES (?, ?, ?, ?, ?, ?)
                """, input.lines(), input.lines().size(), (statement, line) -> {
            statement.setObject(1, storeId);
            statement.setObject(2, receiptId);
            statement.setInt(3, line.lineNo());
            statement.setObject(4, line.variantId());
            statement.setInt(5, line.delta());
            statement.setLong(6, line.unitCost());
        });
        return receiptId;
    }

    Receipt load(UUID receiptId) {
        return jdbc.queryForObject("""
                SELECT id, code, supplier_id, received_at, total_amount
                FROM app.purchase_receipt WHERE store_id = ? AND id = ?
                """, (rs, row) -> new Receipt(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getObject("supplier_id", UUID.class), rs.getObject("received_at", OffsetDateTime.class),
                rs.getLong("total_amount")), context.storeId(), receiptId);
    }

    static ValidatedReceipt validate(CreateReceipt request) {
        if (request.supplierId() == null) {
            throw validation("Chọn nhà cung cấp.");
        }
        if (request.lines() == null || request.lines().isEmpty()) {
            throw validation("Thêm ít nhất một dòng hàng.");
        }
        String note = request.note() == null ? null : request.note().strip();
        if (note != null && note.length() > 500) {
            throw validation("Ghi chú tối đa 500 ký tự.");
        }
        List<StockLine> lines = new ArrayList<>();
        long total = 0;
        for (LineRequest line : request.lines()) {
            int lineNo = lines.size() + 1;
            if (line == null || line.variantId() == null) {
                throw validation("Chọn biến thể sản phẩm cho dòng " + lineNo + ".");
            }
            int quantity;
            long unitCost;
            try {
                quantity = line.quantity() == null ? 0 : line.quantity().intValueExact();
                unitCost = line.unitCost() == null ? 0 : line.unitCost().longValueExact();
            } catch (ArithmeticException ex) {
                throw validation("Số lượng và giá nhập của dòng " + lineNo + " phải là số nguyên hợp lệ.");
            }
            if (quantity <= 0 || unitCost <= 0) {
                throw validation("Số lượng và giá nhập của dòng " + lineNo + " phải lớn hơn 0.");
            }
            if (unitCost > (MAX_MONEY - total) / quantity) {
                throw validation("Tổng tiền phiếu nhập vượt giới hạn hỗ trợ.");
            }
            total += unitCost * quantity;
            lines.add(new StockLine(lineNo, line.variantId(), quantity, unitCost));
        }
        return new ValidatedReceipt(request.supplierId(), request.receivedAt(), note == null || note.isEmpty() ? null : note,
                List.copyOf(lines), total);
    }

    private static ProblemException validation(String detail) {
        return new ProblemException(HttpStatus.BAD_REQUEST, "/errors/common/validation", "Phiếu nhập không hợp lệ", detail);
    }
}
