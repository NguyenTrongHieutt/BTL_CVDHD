package vn.nhom17.banhang.inventory.internal.stock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import vn.nhom17.banhang.inventory.StockDocumentType;
import vn.nhom17.banhang.inventory.StockService;
import vn.nhom17.banhang.platform.CurrentContext;

@Repository
public class JdbcStockService implements StockService {

    private final JdbcTemplate jdbc;
    private final CurrentContext context;
    public JdbcStockService(JdbcTemplate jdbc, CurrentContext context) {
        this.jdbc = jdbc;
        this.context = context;
    }
    
    // ham cap nhap ton kho
    @Override  
    @Transactional
    public List<AppliedLine> apply(
            StockDocumentType documentType,
            UUID documentId,
            List<StockLine> lines) {
        
        // kiem tra chung tu
        UUID storeId = context.storeId();
        if (documentType == null || documentId == null)
            throw new IllegalArgumentException("Thông tin chứng từ không hợp lệ");
        Integer count = jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM app.stock_ledger
            WHERE store_id = ?
            AND document_type = ?
            AND document_id = ?
            """,
            Integer.class,
            storeId,
            documentType.name(),
            documentId
        );
        if (count > 0)
            throw new IllegalStateException("Chứng từ đã được lưu trước đó");


        // kiem tra danh sach
        if(lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("lines không được để trống");
        }

        // kiem tra cac dieu kien cua tung dong
        Set<Integer> visited = new HashSet<>();
        for (StockLine line : lines) {
                 if (line == null)
                    throw new IllegalArgumentException("Dòng hàng không được null");
                 if(visited.contains(line.lineNo()))
                 {
                    throw new IllegalArgumentException("Trùng lineNo");
                 } else if(line.lineNo() <=0) {
                    throw new IllegalArgumentException("lineNo phải là số dương");
                 } else if(line.variantId() == null) {
                    throw new IllegalArgumentException("variantId không được để trống");
                 }
                 visited.add(line.lineNo());
        }

        // duyệt danh sách các dòng để cập nhật tồn kho  
        List<AppliedLine> results = new ArrayList<>();    
        record StockData(int quantity,long avgUnitCost){}   
        for (StockLine line : lines) {
             
             StockData stock = jdbc.queryForObject(
                 """
                 SELECT quantity, avg_unit_cost
                 FROM app.stock
                 WHERE store_id = ? AND variant_id = ?
                 FOR UPDATE
                 """,
                 (rs, rowNum) -> new StockData(
                 rs.getInt("quantity"),
                 rs.getLong("avg_unit_cost")
                 ),
                 storeId,
                 line.variantId()
             );

            long newQuantity;
            long newCost;
            switch (documentType) {

                case OPENING:
                    if (line.delta() < 0)
                       throw new IllegalArgumentException("Số lượng khởi tạo không được âm");
                    if (stock.quantity() != 0)
                       throw new IllegalArgumentException("Kho đã có hàng, không thể khởi tạo");
                    if (line.unitCost() == null || line.unitCost() < 0)
                       throw new IllegalArgumentException("Giá vốn khởi tạo không hợp lệ");
                    newQuantity = line.delta();
                    newCost = line.unitCost();
                    break;

                case SALE:
                    if (line.delta() >= 0)
                       throw new IllegalArgumentException("Số lượng bán phải có delta âm");
                    newQuantity = (long) stock.quantity() + line.delta();  
                    newCost = stock.avgUnitCost();
                    break;
                
                case SALE_CANCEL:
                case SALE_RETURN:
                     if (line.delta() <= 0)
                     throw new IllegalArgumentException("Số lượng hoàn phải dương");
                     newQuantity = (long) stock.quantity() + line.delta(); 
                     newCost = stock.avgUnitCost();
                     break;
                
                case PURCHASE_RECEIPT:
                     if (line.delta() <= 0)
                     throw new IllegalArgumentException("Số lượng hàng nhập phải dương");
                     if (line.unitCost() == null || line.unitCost() <= 0) 
                     throw new IllegalArgumentException("Giá vốn hiện tại không hợp lệ");
                     newQuantity = (long) stock.quantity() + line.delta(); 
                     newCost = (line.delta() * line.unitCost() + (long) stock.quantity() * stock.avgUnitCost()) / newQuantity;
                     break;

                case STOCKTAKE:
                     newQuantity = (long) stock.quantity() + line.delta(); 
                     newCost = stock.avgUnitCost();
                     break;

                default:
                    throw new IllegalArgumentException("Loại chứng từ không hợp lệ");

            }

            if (newQuantity < 0 || newQuantity > Integer.MAX_VALUE)
                throw new IllegalArgumentException("Số lượng tồn kho không hợp lệ");

            int rows = jdbc.update(
                """
                UPDATE app.stock
                SET quantity = ?,
                avg_unit_cost = ?,
                updated_at = NOW()
                WHERE store_id = ? AND variant_id = ?
                """,
                (int) newQuantity,
                newCost,
                storeId,
                line.variantId()
            );
            
            if (rows != 1)
                throw new IllegalStateException("Cập nhật tồn kho thất bại");

            long appliedCost = (documentType == StockDocumentType.OPENING
                || documentType == StockDocumentType.PURCHASE_RECEIPT)
                ? line.unitCost()
                : stock.avgUnitCost();


            jdbc.update(
                """
                INSERT INTO app.stock_ledger (
                store_id,
                variant_id,
                document_type,
                document_id,
                line_no,
                delta,
                unit_cost,
                quantity_after,
                avg_cost_after,
                created_by
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                storeId,
                line.variantId(),
                documentType.name(),
                documentId,
                line.lineNo(),
                line.delta(),
                appliedCost,
                (int) newQuantity,
                newCost,
                context.userIdIfPresent().orElse(null)
            );

            results.add(
                    new AppliedLine(
                        line.lineNo(),
                        line.variantId(),
                        appliedCost,
                        (int) newQuantity
                    )
                );
    }
    
    return results;

}

}
