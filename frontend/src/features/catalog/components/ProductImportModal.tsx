/**
 * Hộp thoại nhập Sản phẩm từ Excel (FR-6, SP-06). Người giữ: Hiếu — features/catalog (AD-20). Chỉ Chủ cửa hàng mở được
 * (nút "Nhập từ Excel" ở màn Sản phẩm; backend vẫn kiểm quyền, AD-16).
 *
 * Ba bước: tải file mẫu (`.xlsx`, tải về bằng `Blob`), chọn file đã điền, bấm "Nhập". Chọn file chỉ kiểm phía client
 * đuôi `.xlsx` và cỡ tối đa 2 MB (backend kiểm nội dung file, tiêu đề cột, tối đa 1000 dòng); file chỉ gửi khi bấm
 * "Nhập". Nhập xong, hộp thoại hiện kết quả thay cho các bước: số Sản phẩm đã nhập, Danh mục mới, và bảng dòng lỗi (số
 * dòng Excel, cột, lý do) để sửa file; "Nhập file khác" quay về bước chọn file. Bảng Sản phẩm, Danh mục, Tồn kho, sổ kho
 * tải lại sau mỗi lần nhập (`useImportProducts`).
 *
 * Lỗi cả file (sai mẫu, không đọc được, trùng mã do người khác vừa tạo: không dòng nào được nhập) hiện ở khung báo lỗi
 * kèm lý do cụ thể của backend (`detail`: cột nào thiếu, dòng nào trùng). Mất mạng, lỗi máy chủ: bấm "Nhập" lại với
 * cùng file gửi lại cùng `Idempotency-Key`, nên nếu lần trước đã nhập xong thì chỉ nhận lại kết quả đó, không nhập hai
 * lần. Đang gửi thì không đóng được hộp thoại.
 */
import { DownloadOutlined, FileExcelOutlined, UploadOutlined } from '@ant-design/icons';
import { useMutation } from '@tanstack/react-query';
import { Alert, Button, Flex, Modal, Table, Typography, Upload } from 'antd';
import type { TableColumnsType } from 'antd';
import { useState } from 'react';
import { isApiError, shouldReuseIdempotencyKey } from '../../../shared/api/client';
import { ErrorAlert } from '../../../shared/components';
import { describeError } from '../../../shared/messages.vi';
import {
  IDEMPOTENCY_KEY_REUSE,
  IMPORT_INVALID_FILE,
  PRODUCT_IMPORT_MAX_BYTES,
  PRODUCT_IMPORT_MAX_ROWS,
  PRODUCT_IMPORT_TEMPLATE_FILENAME,
  fetchProductImportTemplate,
  useImportProducts,
} from '../api/products';
import type { ProductImportResult, ProductImportRowError } from '../api/products';

const XLSX_TYPE = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
const ACCEPT = `.xlsx,${XLSX_TYPE}`;
const EMPTY = '—';

export interface ProductImportModalProps {
  onClose: () => void;
  /** Gọi sau mỗi lần nhập xong (kể cả khi không dòng nào nhập được), trước khi hộp thoại hiện kết quả. */
  onImported?: (result: ProductImportResult) => void;
}

/** Lỗi của file vừa chọn, kiểm phía client; `null` khi gửi được (backend kiểm tiếp nội dung). */
function fileProblem(file: File): string | null {
  if (!/\.xlsx$/i.test(file.name)) {
    return 'Chỉ nhận file Excel .xlsx (không nhận .xls, .csv). Hãy mở file bằng Excel rồi lưu lại dạng .xlsx.';
  }
  if (file.size > PRODUCT_IMPORT_MAX_BYTES) return 'File tối đa 2 MB. Hãy tách thành nhiều file nhỏ hơn.';
  return null;
}

/** Lưu `blob` thành file tải về của trình duyệt. */
function saveFile(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.append(link);
  link.click();
  link.remove();
  // Trình duyệt đã nhận lệnh tải; thu hồi URL sau khi xử lý xong sự kiện click
  setTimeout(() => URL.revokeObjectURL(url), 0);
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

/** Dòng của bảng lỗi; `key` vì một dòng Excel có thể có nhiều lỗi. */
type FailedRow = ProductImportRowError & { key: number };

const FAILED_COLUMNS: TableColumnsType<FailedRow> = [
  { title: 'Dòng', dataIndex: 'row', key: 'row', width: 80, align: 'right' },
  { title: 'Cột', dataIndex: 'column', key: 'column', width: 160, render: (column: string | null) => column ?? EMPTY },
  { title: 'Lý do', dataIndex: 'message', key: 'message' },
];

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
      ? 'Sửa các dòng lỗi rồi chỉ nhập các dòng đó: nhập lại dòng đã nhập thì dòng có SKU hoặc mã vạch bị báo trùng, ' +
        'còn dòng không có SKU, mã vạch sẽ được tạo thêm một Sản phẩm nữa.'
      : null,
  ].filter((line): line is string => line !== null);
  const failed: FailedRow[] = result.failed.map((f, index) => ({ ...f, key: index }));
  return (
    <>
      <Alert
        type={type}
        showIcon
        title={title}
        description={lines.length > 0 ? lines.map((line) => <div key={line}>{line}</div>) : undefined}
        style={{ marginBottom: 16 }}
      />
      {failed.length > 0 ? (
        <Table<FailedRow>
          size="small"
          rowKey="key"
          columns={FAILED_COLUMNS}
          dataSource={failed}
          pagination={{ pageSize: 10, hideOnSinglePage: true, showSizeChanger: false }}
        />
      ) : null}
    </>
  );
}

export function ProductImportModal({ onClose, onImported }: ProductImportModalProps) {
  const [file, setFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const importing = useImportProducts();
  const template = useMutation({
    mutationFn: fetchProductImportTemplate,
    onSuccess: (blob) => saveFile(blob, PRODUCT_IMPORT_TEMPLATE_FILENAME),
  });
  const pending = importing.isPending;

  const chooseFile = (picked: File) => {
    importing.reset();
    const problem = fileProblem(picked);
    setFileError(problem);
    setFile(problem === null ? picked : null);
  };

  const submit = () => {
    if (file === null || pending) return;
    importing.mutate(file, { onSuccess: (result) => onImported?.(result) });
  };

  const startOver = () => {
    importing.reset();
    setFile(null);
    setFileError(null);
  };

  const footer = importing.isSuccess
    ? [
        <Button key="again" onClick={startOver}>
          Nhập file khác
        </Button>,
        <Button key="close" type="primary" onClick={onClose}>
          Đóng
        </Button>,
      ]
    : [
        <Button key="cancel" disabled={pending} onClick={onClose}>
          Hủy
        </Button>,
        <Button key="import" type="primary" loading={pending} disabled={file === null} onClick={submit}>
          Nhập
        </Button>,
      ];

  return (
    <Modal
      open
      title="Nhập Sản phẩm từ Excel"
      width={720}
      footer={footer}
      onCancel={() => {
        if (!pending) onClose();
      }}
      closable={!pending}
      keyboard={!pending}
      mask={{ closable: false }}
    >
      {importing.isSuccess ? (
        <ImportResultView result={importing.data} />
      ) : (
        <Flex vertical gap={16}>
          {importing.isError ? (
            <div>
              <ImportError error={importing.error} />
              {shouldReuseIdempotencyKey(importing.error) ? (
                <Typography.Paragraph type="secondary" style={{ marginTop: -8 }}>
                  Có thể file đã được nhập trước khi mất kết nối. Bấm &quot;Nhập&quot; để gửi lại: nếu lần trước đã
                  nhập xong, hệ thống chỉ trả lại kết quả đó, không nhập hai lần.
                </Typography.Paragraph>
              ) : null}
            </div>
          ) : null}
          <div>
            <Typography.Paragraph style={{ marginBottom: 8 }}>
              1. Tải file mẫu, điền mỗi Sản phẩm một dòng từ dòng 2 (cột có dấu * là bắt buộc; xem trang Hướng dẫn trong
              file).
            </Typography.Paragraph>
            <Button
              icon={<DownloadOutlined />}
              loading={template.isPending}
              onClick={() => {
                template.reset();
                template.mutate();
              }}
            >
              Tải file mẫu
            </Button>
            {template.isError ? <ErrorAlert error={template.error} style={{ marginTop: 8, marginBottom: 0 }} /> : null}
          </div>
          <div>
            <Typography.Paragraph style={{ marginBottom: 8 }}>
              2. Chọn file đã điền: .xlsx, tối đa 2 MB, {PRODUCT_IMPORT_MAX_ROWS} dòng. Dòng lỗi được báo kèm số dòng và
              lý do; các dòng đúng vẫn được nhập.
            </Typography.Paragraph>
            <Flex align="center" gap={12} wrap>
              <Upload
                accept={ACCEPT}
                showUploadList={false}
                disabled={pending}
                // Không tự tải lên: chỉ giữ file, gửi khi bấm "Nhập"
                beforeUpload={(picked) => {
                  chooseFile(picked);
                  return Upload.LIST_IGNORE;
                }}
              >
                <Button icon={<UploadOutlined />} disabled={pending}>
                  {file === null ? 'Chọn file' : 'Chọn file khác'}
                </Button>
              </Upload>
              {file !== null ? (
                <Typography.Text>
                  <FileExcelOutlined /> {file.name}
                </Typography.Text>
              ) : null}
            </Flex>
            {fileError !== null ? (
              <Typography.Paragraph type="danger" role="alert" style={{ marginTop: 8, marginBottom: 0 }}>
                {fileError}
              </Typography.Paragraph>
            ) : null}
          </div>
        </Flex>
      )}
    </Modal>
  );
}
