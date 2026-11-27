import { ClockCircleOutlined } from '@ant-design/icons';
import { Card, Progress, Tag, Typography } from 'antd';
import type { AiQuota } from '../../../shared/types/assistant';

interface QuotaCardProps {
  quota: AiQuota | null;
  exhausted: boolean;
}

const DEFAULT_DAILY_LIMIT = 50;

export function QuotaCard({ quota, exhausted }: QuotaCardProps) {
  const limit = quota?.limit ?? DEFAULT_DAILY_LIMIT;
  const used = quota?.used ?? (exhausted ? limit : 0);
  const remaining = quota
    ? Math.max(0, Math.min(limit, quota.remaining ?? limit - used))
    : exhausted ? 0 : null;
  const percent = limit > 0 ? Math.min(100, Math.round((used / limit) * 100)) : 0;

  return (
    <Card className="assistant-quota-card" size="small" title="Hạn mức hôm nay">
      <div className="assistant-quota-summary" aria-live="polite" aria-atomic="true">
        <div>
          <Typography.Text strong className="assistant-quota-number">
            {remaining === null ? `${limit} lượt/ngày` : `Còn ${remaining}/${limit} lượt`}
          </Typography.Text>
          <Typography.Paragraph type="secondary" className="assistant-quota-copy">
            {remaining === null
              ? 'Số lượt còn lại sẽ hiện sau câu hỏi đầu tiên.'
              : exhausted
                ? 'Bạn vẫn có thể xem các báo cáo thông thường.'
                : `Đã dùng ${used.toLocaleString('vi-VN')} lượt hôm nay.`}
          </Typography.Paragraph>
        </div>
        <Tag color={exhausted ? 'red' : 'cyan'}>{exhausted ? 'Đã hết lượt' : 'Theo cửa hàng'}</Tag>
      </div>
      {remaining !== null && (
        <Progress
          percent={percent}
          showInfo={false}
          status={exhausted ? 'exception' : 'normal'}
          aria-label={`Đã dùng ${used} trên ${limit} lượt hỏi hôm nay`}
        />
      )}
      <div className="assistant-quota-reset">
        <ClockCircleOutlined aria-hidden="true" />
        <span>Làm mới lúc 0 giờ theo giờ Việt Nam</span>
      </div>
    </Card>
  );
}
