package vn.nhom17.banhang.catalog.internal.category;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nhom17.banhang.catalog.internal.Constraints;
import vn.nhom17.banhang.catalog.internal.FieldErrors;
import vn.nhom17.banhang.catalog.internal.TextFields;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.PageResponse;
import vn.nhom17.banhang.platform.ProblemException;

/**
 * Quản lý Danh mục (FR-4, SP-04): xem, thêm, đổi tên, xóa.
 *
 * <ul>
 *   <li>Trùng tên trong Cửa hàng (không phân biệt hoa thường) do unique {@code category_name_uq} bắt, không SELECT
 *       kiểm trước: {@code 409 /errors/catalog/category-name-taken}.</li>
 *   <li>Xóa Danh mục còn Sản phẩm (mọi Sản phẩm thuộc Danh mục đều tính, kể cả Sản phẩm đã ngừng kinh doanh) bị khóa
 *       ngoại {@code RESTRICT} chặn: {@code 409 /errors/catalog/category-in-use}.</li>
 *   <li>Đổi tên thì cập nhật {@code product.changed_at} của mọi Sản phẩm thuộc Danh mục trong cùng transaction, vì
 *       {@code searchText} của change-feed có tên Danh mục (AD-12).</li>
 *   <li>Id của Cửa hàng khác hoặc không tồn tại, kể cả Danh mục bị xóa ở yêu cầu khác giữa lúc đọc và lúc ghi:
 *       {@code 404 /errors/common/not-found} (AD-5).</li>
 * </ul>
 */
@Service
class CategoryService {

    static final int NAME_MAX = 100;
    static final String NAME_UNIQUE = "category_name_uq";
    private static final TextFields.Rule NAME_RULE = TextFields.Rule.required("Tên Danh mục", NAME_MAX);

    private final CategoryRepository categories;
    private final JdbcTemplate jdbc;
    private final CurrentContext context;
    private final Clock clock;

    CategoryService(CategoryRepository categories, JdbcTemplate jdbc, CurrentContext context, Clock clock) {
        this.categories = categories;
        this.jdbc = jdbc;
        this.context = context;
        this.clock = clock;
    }

    /** Sắp theo tên không dấu, không phân biệt hoa thường; {@code page} bắt đầu từ 0 (AD-19). */
    @Transactional(readOnly = true)
    PageResponse<CategoryView> list(Integer page, Integer size) {
        int pageNo = page == null || page < 0 ? 0 : page;
        int pageSize = PageResponse.clampSize(size);
        UUID storeId = context.storeId();
        Long total = jdbc.queryForObject("select count(*) from app.category where store_id = ?", Long.class, storeId);
        List<CategoryView> items = jdbc.query("""
                select c.id, c.name,
                       (select count(*) from app.product p
                         where p.store_id = c.store_id and p.category_id = c.id) as product_count
                from app.category c
                where c.store_id = ?
                order by app.f_unaccent(lower(c.name)), lower(c.name), c.id
                limit ? offset ?
                """,
                (rs, i) -> new CategoryView(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getLong("product_count")),
                storeId, pageSize, (long) pageNo * pageSize);
        return new PageResponse<>(items, total == null ? 0 : total, pageNo, pageSize);
    }

    @Transactional
    CategoryView create(String rawName) {
        String name = normalizeName(rawName);
        CategoryEntity category = new CategoryEntity(name, clock.instant());
        try {
            categories.saveAndFlush(category);
        } catch (DataIntegrityViolationException e) {
            throw isNameTaken(e) ? nameTaken() : e;
        }
        return new CategoryView(category.id(), category.name(), 0);
    }

    @Transactional
    CategoryView rename(UUID id, String rawName) {
        String name = normalizeName(rawName);
        CategoryEntity category = categories.findById(id).orElseThrow(CategoryService::notFound);
        if (!category.name().equals(name)) {
            Instant now = clock.instant();
            category.rename(name, now);
            try {
                categories.flush();
            } catch (OptimisticLockingFailureException removedMeanwhile) {
                throw notFound();
            } catch (DataIntegrityViolationException e) {
                throw isNameTaken(e) ? nameTaken() : e;
            }
            // AD-12: searchText của Sản phẩm có tên Danh mục, nên change-feed phải thấy các Sản phẩm này đã đổi.
            jdbc.update("update app.product set changed_at = ? where store_id = ? and category_id = ?",
                    OffsetDateTime.ofInstant(now, ZoneOffset.UTC), category.storeId(), id);
        }
        return new CategoryView(id, category.name(), productCount(category.storeId(), id));
    }

    @Transactional
    void delete(UUID id) {
        CategoryEntity category = categories.findById(id).orElseThrow(CategoryService::notFound);
        try {
            categories.delete(category);
            categories.flush();
        } catch (OptimisticLockingFailureException removedMeanwhile) {
            throw notFound();
        } catch (DataIntegrityViolationException stillReferenced) {
            // Khóa ngoại product.category_id ON DELETE RESTRICT; cả transaction rollback, không xóa gì.
            throw ProblemException.conflict("/errors/catalog/category-in-use", "Danh mục còn Sản phẩm",
                    "Danh mục này còn Sản phẩm nên không xóa được (mọi Sản phẩm thuộc Danh mục đều tính, kể cả "
                            + "Sản phẩm đã ngừng kinh doanh). Hãy chuyển các Sản phẩm sang Danh mục khác rồi xóa lại.");
        }
    }

    private long productCount(UUID storeId, UUID categoryId) {
        Long count = jdbc.queryForObject("select count(*) from app.product where store_id = ? and category_id = ?",
                Long.class, storeId, categoryId);
        return count == null ? 0 : count;
    }

    /**
     * Chuẩn hóa bằng {@link TextFields} (bỏ ký tự định dạng vô hình để không lách được {@code category_name_uq} bằng
     * tên trông giống hệt, NFC, cắt khoảng trắng Unicode, từ chối ký tự điều khiển), rồi kiểm bắt buộc và tối đa
     * {@value #NAME_MAX} ký tự. Sai thì {@code 400 /errors/common/validation} trên trường {@code name}.
     */
    static String normalizeName(String raw) {
        FieldErrors errors = new FieldErrors();
        String name = TextFields.check(raw, "name", NAME_RULE, errors);
        errors.throwIfAny();
        return name;
    }

    /** Lỗi unique đúng là {@code category_name_uq} (xem {@link Constraints}). */
    private static boolean isNameTaken(Throwable error) {
        return Constraints.violated(error, NAME_UNIQUE);
    }

    private static ProblemException nameTaken() {
        return ProblemException.conflict("/errors/catalog/category-name-taken", "Tên Danh mục đã có",
                "Cửa hàng đã có Danh mục tên này (không phân biệt chữ hoa, chữ thường). Hãy chọn tên khác.");
    }

    private static ProblemException notFound() {
        return ProblemException.notFound("/errors/common/not-found", "Không tìm thấy",
                "Không tìm thấy Danh mục này. Có thể Danh mục đã bị xóa.");
    }

}
