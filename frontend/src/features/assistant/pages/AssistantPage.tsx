// Trợ lý AI: Hỏi đáp dữ liệu (FR-37 – FR-42). Chỉ Chủ cửa hàng.
import {
  BarChartOutlined,
  BulbOutlined,
  CheckCircleOutlined,
  DatabaseOutlined,
  RobotOutlined,
  SendOutlined,
} from '@ant-design/icons';
import { useMutation } from '@tanstack/react-query';
import { Alert, Button, Card, Input, Skeleton, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import type { FormEvent, KeyboardEvent } from 'react';
import { Link } from 'react-router';
import { isApiError } from '../../../shared/api/client';
import { ErrorAlert, useDocumentTitle } from '../../../shared/components';
import type { AiQuota, AskResponse } from '../../../shared/types/assistant';
import { askAssistant } from '../api/ask';
import { AssistantAnswer } from '../components/AssistantAnswer';
import { QuotaCard } from '../components/QuotaCard';
import { ASSISTANT_SUGGESTIONS } from '../suggestions';
import '../assistant.css';

const QUESTION_MAX_LENGTH = 500;
const QUOTA_ERROR_TYPE = '/errors/ai/quota-exceeded';

const supportedTopics = [
  'Doanh thu, số đơn và lợi nhuận gộp',
  'Sản phẩm bán chạy hoặc bán chậm',
  'Tồn kho và gợi ý nhập hàng',
] as const;

interface AnswerResult {
  question: string;
  response: AskResponse;
}

export function AssistantPage() {
  useDocumentTitle('Trợ lý AI');
  const [question, setQuestion] = useState('');
  const [localQuestionError, setLocalQuestionError] = useState<string>();
  const [submittedQuestion, setSubmittedQuestion] = useState('');
  const [result, setResult] = useState<AnswerResult | null>(null);
  const [quota, setQuota] = useState<AiQuota | null>(null);
  const [quotaBlocked, setQuotaBlocked] = useState(false);
  const resultHeadingRef = useRef<HTMLHeadingElement>(null);

  const mutation = useMutation({
    mutationFn: askAssistant,
    onSuccess: (response, askedQuestion) => {
      setResult({ question: askedQuestion, response });
      setQuota(response.quota);
      setQuotaBlocked((response.quota.remaining ?? response.quota.limit - response.quota.used) <= 0);
      setQuestion('');
    },
    onError: (error) => {
      if (isApiError(error) && (error.type === QUOTA_ERROR_TYPE || error.status === 429)) setQuotaBlocked(true);
    },
  });

  useEffect(() => {
    if (result) resultHeadingRef.current?.focus();
  }, [result]);

  const remoteQuestionError = isApiError(mutation.error)
    ? mutation.error.errors.find(({ field }) => field === 'question' || field.endsWith('.question'))?.message
    : undefined;
  const questionError = localQuestionError ?? remoteQuestionError;
  const quotaError = isApiError(mutation.error)
    && (mutation.error.type === QUOTA_ERROR_TYPE || mutation.error.status === 429);
  const askingDisabled = mutation.isPending || quotaBlocked;

  function submitQuestion(rawQuestion: string) {
    if (askingDisabled) return;
    const normalized = rawQuestion.trim();
    if (!normalized) {
      setLocalQuestionError('Nhập câu hỏi về dữ liệu cửa hàng.');
      document.getElementById('assistant-question')?.focus();
      return;
    }
    setLocalQuestionError(undefined);
    setSubmittedQuestion(normalized);
    setResult(null);
    mutation.reset();
    mutation.mutate(normalized);
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    submitQuestion(question);
  }

  function handleQuestionKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key !== 'Enter' || event.shiftKey || event.nativeEvent.isComposing) return;
    event.preventDefault();
    submitQuestion(question);
  }

  function askSuggestion(suggestion: string) {
    setQuestion(suggestion);
    submitQuestion(suggestion);
  }

  const retryAllowed = mutation.isError && !quotaError && !remoteQuestionError
    && (!isApiError(mutation.error) || mutation.error.status === 0 || mutation.error.status >= 500);

  return (
    <div className="assistant-page">
      <header className="assistant-hero">
        <div className="assistant-hero-icon"><RobotOutlined aria-hidden="true" /></div>
        <div>
          <Typography.Title level={1}>Trợ lý AI</Typography.Title>
          <Typography.Paragraph>
            Hỏi bằng tiếng Việt để xem nhanh tình hình kinh doanh từ dữ liệu của cửa hàng.
          </Typography.Paragraph>
        </div>
      </header>

      <div className="assistant-layout">
        <div className="assistant-main-column">
          <Card className="assistant-composer-card">
            <section aria-labelledby="assistant-suggestions-title">
              <div className="assistant-section-heading">
                <BulbOutlined aria-hidden="true" />
                <div>
                  <Typography.Title level={2} id="assistant-suggestions-title">Gợi ý câu hỏi</Typography.Title>
                  <Typography.Text type="secondary">Chọn một câu để hỏi ngay hoặc tự nhập ở bên dưới.</Typography.Text>
                </div>
              </div>
              <div className="assistant-suggestions">
                {ASSISTANT_SUGGESTIONS.map((suggestion) => (
                  <Button
                    key={suggestion}
                    className="assistant-suggestion"
                    disabled={askingDisabled}
                    onClick={() => askSuggestion(suggestion)}
                  >
                    {suggestion}
                  </Button>
                ))}
              </div>
            </section>

            <div className="assistant-composer-divider" />

            <form onSubmit={handleSubmit} aria-busy={mutation.isPending} noValidate>
              <label className="assistant-question-label" htmlFor="assistant-question">Câu hỏi của bạn</label>
              <Input.TextArea
                id="assistant-question"
                value={question}
                onChange={(event) => {
                  setQuestion(event.target.value);
                  if (localQuestionError) setLocalQuestionError(undefined);
                }}
                onKeyDown={handleQuestionKeyDown}
                placeholder="Ví dụ: Doanh thu hôm nay là bao nhiêu?"
                autoComplete="off"
                autoSize={{ minRows: 3, maxRows: 7 }}
                maxLength={QUESTION_MAX_LENGTH}
                showCount
                disabled={askingDisabled}
                aria-invalid={Boolean(questionError)}
                aria-describedby={`assistant-question-help${questionError ? ' assistant-question-error' : ''}`}
              />
              <div className="assistant-composer-actions">
                <div>
                  <Typography.Text id="assistant-question-help" type="secondary">
                    Nhấn Enter để hỏi, Shift + Enter để xuống dòng.
                  </Typography.Text>
                  {questionError && <div id="assistant-question-error" className="assistant-field-error" role="alert">{questionError}</div>}
                </div>
                <Button
                  className="assistant-submit"
                  type="primary"
                  htmlType="submit"
                  icon={<SendOutlined aria-hidden="true" />}
                  loading={mutation.isPending}
                  disabled={mutation.isPending || quotaBlocked || question.trim().length === 0}
                >
                  {mutation.isPending ? 'Đang phân tích' : 'Hỏi Trợ lý'}
                </Button>
              </div>
            </form>
          </Card>

          {quotaError && (
            <Alert
              className="assistant-feedback"
              type="warning"
              showIcon
              title="Cửa hàng đã dùng hết lượt hỏi Trợ lý AI hôm nay"
              description="Hạn mức được làm mới lúc 0 giờ theo giờ Việt Nam. Các báo cáo thông thường vẫn dùng được."
              action={<Link className="assistant-alert-link" to="/reporting/dashboard"><BarChartOutlined aria-hidden="true" /> Xem báo cáo</Link>}
            />
          )}

          {mutation.isError && !quotaError && (
            <div className="assistant-feedback">
              <ErrorAlert
                error={mutation.error}
                onClose={() => mutation.reset()}
                onRetry={retryAllowed ? () => submitQuestion(submittedQuestion) : undefined}
                retrying={mutation.isPending}
              />
            </div>
          )}

          {mutation.isPending && (
            <Card className="assistant-loading" aria-live="polite" aria-busy="true">
              <div className="assistant-loading-title" role="status">
                <RobotOutlined aria-hidden="true" />
                <span>Đang phân tích dữ liệu cửa hàng…</span>
              </div>
              <Typography.Text type="secondary">Câu hỏi: “{submittedQuestion}”</Typography.Text>
              <Skeleton active paragraph={{ rows: 4 }} title={false} />
            </Card>
          )}

          {result && <AssistantAnswer question={result.question} response={result.response} headingRef={resultHeadingRef} />}
        </div>

        <aside className="assistant-sidebar" aria-label="Thông tin về Trợ lý AI">
          <QuotaCard quota={quota} exhausted={quotaBlocked} />
          <Card size="small" title="Trợ lý hỗ trợ">
            <ul className="assistant-topic-list">
              {supportedTopics.map((topic) => (
                <li key={topic}><CheckCircleOutlined aria-hidden="true" /><span>{topic}</span></li>
              ))}
            </ul>
            <div className="assistant-scope-note">
              <DatabaseOutlined aria-hidden="true" />
              <span>Trợ lý chỉ đọc dữ liệu, không tự tạo hay sửa chứng từ.</span>
            </div>
          </Card>
        </aside>
      </div>
    </div>
  );
}
