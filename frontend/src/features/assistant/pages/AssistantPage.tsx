// Trợ lý AI: Hỏi đáp dữ liệu (FR-37 – FR-42). Người giữ: Lân — features/assistant (AD-20). Chỉ Chủ cửa hàng.
// Route là `/assistant`, không phải `/ai/…`: tiền tố `/ai/` đã được nginx và Vite proxy chuyển sang ai-service.
// Gọi `POST /ai/ask` qua shared/api; hết hạn mức thì 429 `/errors/ai/quota-exceeded` (messages.vi.ts).
// Tìm kiếm ngữ nghĩa không ở đây mà ở màn bán hàng (PRD 7.4).
import { PagePlaceholder } from '../../../shared/components';

export function AssistantPage() {
  return (
    <PagePlaceholder
      title="Trợ lý AI"
      frs={['FR-37', 'FR-38', 'FR-39', 'FR-40', 'FR-41', 'FR-42']}
      phase="GĐ2"
      owner="Lân"
      description="Khung chat hỏi đáp dữ liệu bằng tiếng Việt, câu hỏi gợi ý nhanh, bảng và biểu đồ kèm liên kết tới báo cáo; hiện Hạn mức AI còn lại."
    />
  );
}
