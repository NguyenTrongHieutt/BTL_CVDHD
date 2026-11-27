/**
 * Nhập sản phẩm từ CSV hoặc Excel (FR-6, SP-06). Người giữ: Hiếu — features/catalog.
 * Backend trả nhóm và lỗi ở bước xem trước. Chỉ gửi yêu cầu lưu sau khi người dùng xác nhận.
 */
import { DownloadOutlined, FileTextOutlined, UploadOutlined } from '@ant-design/icons';
import { useMutation } from '@tanstack/react-query';
import { Alert, Button, Flex, Modal, Typography, Upload } from 'antd';
import { useEffect, useId, useRef, useState } from 'react';
import { isApiError, newIdempotencyKey, shouldReuseIdempotencyKey } from '../../../shared/api/client';
import { downloadBlob } from '../../../shared/api/downloadBlob';
import { ErrorAlert } from '../../../shared/components';
import { describeError } from '../../../shared/messages.vi';
import {
  IDEMPOTENCY_KEY_REUSE,
  IMPORT_INVALID_FILE,
  IMPORT_PREVIEW_STALE,
  PRODUCT_IMPORT_MAX_BYTES,
  PRODUCT_IMPORT_MAX_ROWS,
  fetchProductImportTemplate,
  previewProductImport,
  useImportProducts,
} from '../api/products';
import type { ProductImportResult } from '../api/products';
import { ImportFailedRows, ProductImportPreviewView } from './ProductImportPreview';
import { useCatalogDialog } from './useCatalogDialog';

const XLSX_TYPE = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
const ACCEPT = `.xlsx,.csv,${XLSX_TYPE},text/csv`;
const count = new Intl.NumberFormat('vi-VN');

export interface ProductImportModalProps {
  onClose: () => void;
  /** Gọi sau mỗi lần nhập xong (kể cả khi không dòng nào nhập được), trước khi hộp thoại hiện kết quả. */
  onImported?: (result: ProductImportResult) => void;
}

/** Lỗi của file vừa chọn, kiểm phía client; `null` khi gửi được (backend kiểm tiếp nội dung). */
function fileProblem(file: File): string | null {
  if (!/\.(xlsx|csv)$/i.test(file.name)) {
    return 'Chọn file Excel (.xlsx) hoặc CSV (.csv).';
  }
  if (file.size > PRODUCT_IMPORT_MAX_BYTES) return 'File tối đa 2 MB. Hãy tách thành nhiều file nhỏ hơn.';
  return null;
}

/** `detail` của backend nêu lý do cụ thể cho lỗi cả file (cột thiếu, quá số dòng, dòng trùng mã khi ghi). */
function importProblemDetail(error: unknown): string | undefined {
  if (!isApiError(error) || !error.detail) return undefined;
  const wholeFile = error.type === IMPORT_INVALID_FILE || (error.status === 409 && error.type !== IDEMPOTENCY_KEY_REUSE);
  return wholeFile ? error.detail : undefined;
}

function ImportError({ error }: { error: unknown }) {
  const detail = importProblemDetail(error);
  if (detail === undefined) return <ErrorAlert error={error} />;
  const described = describeError(error);
  const lines = [detail, described.hint, described.requestId ? `Mã yêu cầu: ${described.requestId}` : undefined].filter(
    (line): line is string => Boolean(line),
  );
  return (
    <Alert
      type="error"
      showIcon
      title={described.message}
      description={lines.map((line) => (
        <div key={line}>{line}</div>
      ))}
      style={{ marginBottom: 16 }}
    />
  );
}

function ImportResultView({ result }: { result: ProductImportResult }) {
  const failedRows = new Set(result.failed.map((f) => f.row)).size;
  const empty = result.imported === 0 && failedRows === 0;
  const type = empty ? 'info' : failedRows === 0 ? 'success' : result.imported > 0 ? 'warning' : 'error';
  const title =
    failedRows === 0
      ? `Đã nhập ${result.imported} Sản phẩm.`
      : `Đã nhập ${result.imported} Sản phẩm. ${failedRows} dòng lỗi chưa được nhập.`;
  const lines = [
    empty ? 'File không có dòng dữ liệu nào (dữ liệu bắt đầu từ dòng 2).' : null,
    result.createdCategories.length > 0 ? `Danh mục mới: ${result.createdCategories.join(', ')}.` : null,
    failedRows > 0
      ? 'Sửa và nhập lại các sản phẩm bị lỗi. Giữ đủ các biến thể của từng nhóm khi nhập lại.'
      : null,
  ].filter((line): line is string => line !== null);
  return (
    <>
      <Alert
        type={type}
        showIcon
        title={title}
        description={lines.length > 0 ? lines.map((line) => <div key={line}>{line}</div>) : undefined}
        style={{ marginBottom: 16 }}
      />
      {result.failed.length > 0 && <ImportFailedRows errors={result.failed} />}
    </>
  );
}

export function ProductImportModal({ onClose, onImported }: ProductImportModalProps) {
  const { close, modalProps } = useCatalogDialog(onClose);
  const [file, setFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const fileErrorId = useId();
  const previewRegion = useRef<HTMLDivElement>(null);
  const previewAttempt = useRef<{ file: File; key: string } | null>(null);
  const importing = useImportProducts();
  const preview = useMutation({
    mutationFn: previewProductImport,
    onSuccess: () => {
      previewAttempt.current = null;
    },
    onError: (error) => {
      if (!shouldReuseIdempotencyKey(error)) previewAttempt.current = null;
    },
  });
  const template = useMutation({
    mutationFn: fetchProductImportTemplate,
    onSuccess: (blob, format) => downloadBlob(blob, `mau-nhap-san-pham.${format}`),
  });
  const pending = importing.isPending || preview.isPending;
  const stale = importing.isError && isApiError(importing.error) && importing.error.type === IMPORT_PREVIEW_STALE;
  const retryingCommit = importing.isError && shouldReuseIdempotencyKey(importing.error);
  const retryingPreview = preview.isError && shouldReuseIdempotencyKey(preview.error);
  const ready = preview.data;
  const confirm = ready !== undefined && !stale;

  useEffect(() => {
    if (ready) previewRegion.current?.focus();
  }, [ready]);

  const chooseFile = (picked: File) => {
    importing.reset();
    preview.reset();
    previewAttempt.current = null;
    const problem = fileProblem(picked);
    setFileError(problem);
    setFile(problem === null ? picked : null);
  };

  const goBack = () => {
    preview.reset();
    importing.reset();
    previewAttempt.current = null;
  };

  const startOver = () => {
    goBack();
    setFile(null);
    setFileError(null);
  };

  const submit = () => {
    if (file === null || pending) return;
    if (confirm) {
      if (ready.validProducts > 0) {
        importing.mutate({ file, previewToken: ready.previewToken }, { onSuccess: (result) => onImported?.(result) });
      }
      return;
    }
    importing.reset();
    if (previewAttempt.current?.file !== file) {
      previewAttempt.current = { file, key: newIdempotencyKey() };
    }
    preview.mutate(previewAttempt.current);
  };

  const footer = importing.isSuccess
    ? [
        <Button key="again" onClick={startOver}>
          Nhập file khác
        </Button>,
        <Button key="close" type="primary" onClick={close}>
          Đóng
        </Button>,
      ]
    : [
        <Button key="back" disabled={pending || retryingCommit} onClick={ready ? goBack : close}>
          {ready ? 'Quay lại' : 'Hủy'}
        </Button>,
        <Button key="import" type="primary" loading={pending}
          disabled={file === null || (confirm && ready.validProducts === 0)} onClick={submit}>
          {confirm ? `Lưu ${count.format(ready.validProducts)} sản phẩm` : stale ? 'Xem trước lại' : 'Xem trước'}
        </Button>,
      ];

  return (
    <Modal
      {...modalProps}
      title={importing.isSuccess ? 'Kết quả nhập sản phẩm' : ready ? 'Xem trước dữ liệu nhập' : 'Nhập sản phẩm từ file'}
      width={ready ? 900 : 680}
      styles={{ body: { maxHeight: '68dvh', overflowY: 'auto' } }}
      footer={footer}
      onCancel={() => {
        if (!pending) close();
      }}
      closable={!pending}
      keyboard={!pending}
      mask={{ closable: false }}
    >
      {importing.isSuccess ? (
        <ImportResultView result={importing.data} />
      ) : (
        <Flex vertical gap={16}>
          {importing.isError && (
            <div>
              <ImportError error={importing.error} />
              {retryingCommit && (
                <Typography.Paragraph type="secondary" style={{ marginTop: -8 }}>
                  Có thể máy chủ đã lưu trước khi mất kết nối. Bấm Lưu để gửi lại cùng yêu cầu và nhận kết quả.
                </Typography.Paragraph>
              )}
            </div>
          )}
          {ready ? (
            <div className="import-preview" ref={previewRegion} tabIndex={-1}
              role="region" aria-label="Kết quả xem trước">
              <Typography.Text type="secondary">{file?.name}</Typography.Text>
              <ProductImportPreviewView key={ready.previewToken} preview={ready} />
            </div>
          ) : (
            <>
              <div>
                <Typography.Paragraph style={{ marginBottom: 8 }}>
                  Dùng mẫu CSV hoặc Excel và giữ dòng tiêu đề. Điền cùng mã sản phẩm cha vào các dòng biến thể thuộc
                  một sản phẩm. Để trống mã cha nếu sản phẩm chỉ có một SKU.
                </Typography.Paragraph>
                <Flex gap={8} wrap>
                  <Button icon={<DownloadOutlined aria-hidden />} loading={template.isPending && template.variables === 'xlsx'}
                    disabled={template.isPending && template.variables !== 'xlsx'} onClick={() => template.mutate('xlsx')}>
                    Tải mẫu Excel
                  </Button>
                  <Button icon={<DownloadOutlined aria-hidden />} loading={template.isPending && template.variables === 'csv'}
                    disabled={template.isPending && template.variables !== 'csv'} onClick={() => template.mutate('csv')}>
                    Tải mẫu CSV
                  </Button>
                </Flex>
                {template.isError && <ErrorAlert error={template.error} style={{ marginTop: 8, marginBottom: 0 }} />}
              </div>
              <div>
                <Typography.Paragraph style={{ marginBottom: 8 }}>
                  Chọn file .xlsx hoặc .csv (UTF-8), tối đa 2 MB và {count.format(PRODUCT_IMPORT_MAX_ROWS)} dòng.
                  Xem trước các nhóm và lỗi rồi xác nhận tạo mới sản phẩm.
                </Typography.Paragraph>
                <Flex align="center" gap={12} wrap>
                  <Upload
                    accept={ACCEPT}
                    showUploadList={false}
                    disabled={pending}
                    beforeUpload={(picked) => {
                      chooseFile(picked);
                      return Upload.LIST_IGNORE;
                    }}
                  >
                    <Button icon={<UploadOutlined aria-hidden />} disabled={pending}
                      aria-describedby={fileError ? fileErrorId : undefined}>
                      {file === null ? 'Chọn file' : 'Chọn file khác'}
                    </Button>
                  </Upload>
                  {file && (
                    <Typography.Text style={{ overflowWrap: 'anywhere' }} aria-live="polite">
                      <FileTextOutlined aria-hidden /> {file.name}
                    </Typography.Text>
                  )}
                </Flex>
                {preview.isError && <ImportError error={preview.error} />}
                {retryingPreview && (
                  <Typography.Paragraph type="secondary" role="status" style={{ marginTop: 8, marginBottom: 0 }}>
                    File đã chọn vẫn được giữ. Bấm Xem trước để thử lại cùng yêu cầu.
                  </Typography.Paragraph>
                )}
                {fileError && (
                  <Typography.Paragraph id={fileErrorId} type="danger" role="alert" style={{ marginTop: 8, marginBottom: 0 }}>
                    {fileError}
                  </Typography.Paragraph>
                )}
              </div>
            </>
          )}
        </Flex>
      )}
    </Modal>
  );
}
