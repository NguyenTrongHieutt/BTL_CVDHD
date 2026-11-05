/**
 * Module reporting (chủ: Lân): Truy vấn mẫu Q1–Q9 ({@code GET /api/v1/reporting/q/<Qn>}), API báo cáo, xuất file.
 * Chỉ đọc; là nơi DUY NHẤT được đọc thẳng bảng của module khác (AD-3, AD-10).
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"platform"})
package vn.nhom17.banhang.reporting;
