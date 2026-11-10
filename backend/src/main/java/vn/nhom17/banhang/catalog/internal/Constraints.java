package vn.nhom17.banhang.catalog.internal;

/**
 * Nhận ra lỗi ghi là do đúng một ràng buộc (unique, khóa ngoại) để đổi thành lỗi nghiệp vụ, không SELECT kiểm trước.
 *
 * <p>Tin tên constraint mà Hibernate đã tách ({@code ConstraintViolationException.getConstraintName()}). Chỉ khi không
 * có tên đó mới xét câu lỗi, và chỉ dòng đầu (câu lỗi chính của PostgreSQL), vì dòng {@code Detail} chứa dữ liệu người
 * dùng nhập: SKU hay tên có thể trùng tên một constraint khác.
 */
public final class Constraints {

    private Constraints() {
    }

    public static boolean violated(Throwable error, String constraint) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException violation
                    && violation.getConstraintName() != null) {
                return constraint.equalsIgnoreCase(violation.getConstraintName());
            }
        }
        String quoted = '"' + constraint + '"';
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.lines().findFirst().orElse("").contains(quoted)) {
                return true;
            }
        }
        return false;
    }
}
