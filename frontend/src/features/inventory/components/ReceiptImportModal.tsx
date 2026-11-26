import { DownloadOutlined, UploadOutlined } from '@ant-design/icons';
import { useMutation } from '@tanstack/react-query';
import { Alert, Button, Flex, Input, Modal, Steps, Typography, Upload } from 'antd';
import { useEffect, useId, useRef, useState } from 'react';
import { isApiError, newIdempotencyKey, shouldReuseIdempotencyKey } from '../../../shared/api/client';
import { downloadBlob } from '../../../shared/api/downloadBlob';
import { ErrorAlert } from '../../../shared/components';
import {
  fetchReceiptImportTemplate, previewReceiptImport, RECEIPT_IMPORT_MAX_BYTES, RECEIPT_IMPORT_MAX_ROWS, saveReceiptImport,
} from '../receiptImportApi';
import type {
  Receipt, ReceiptImportCommit, ReceiptImportEdit, ReceiptImportPreview, ReceiptImportRow, ReceiptImportValues,
} from '../receiptImportApi';
import { ReceiptImportReview } from './ReceiptImportReview';
import { ReceiptImportRowEditor } from './ReceiptImportRowEditor';
import { ReceiptSupplierSelect } from './ReceiptSupplierSelect';
import type { ReceiptSupplier } from './ReceiptSupplierSelect';
import './receipt-import.css';

const accept = '.csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
const count = new Intl.NumberFormat('vi-VN');

interface Props {
  initialSupplier?: ReceiptSupplier;
  initialNote: string;
  hasManualLines: boolean;
  onClose: () => void;
  onImported: (receipt: Receipt) => void;
}

function ImportProblem({ error }: { error: unknown }) {
  if (isApiError(error) && error.detail && [
    '/errors/inventory/receipt-import-invalid', '/errors/catalog/import-invalid-file',
  ].includes(error.type)) {
    return <Alert type="error" showIcon title={error.title} description={error.detail} />;
  }
  return <ErrorAlert error={error} style={{ marginBottom: 0 }} />;
}

export function ReceiptImportModal({ initialSupplier, initialNote, hasManualLines, onClose, onImported }: Props) {
  const [open, setOpen] = useState(true);
  const [supplier, setSupplier] = useState(initialSupplier);
  const [note, setNote] = useState(initialNote);
  const [file, setFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<string>();
  const [preview, setPreview] = useState<ReceiptImportPreview>();
  const [reviewSearch, setReviewSearch] = useState('');
  const [edits, setEdits] = useState<ReceiptImportEdit[]>([]);
  const [editing, setEditing] = useState<ReceiptImportRow>();
  const [pendingCommit, setPendingCommit] = useState<ReceiptImportCommit>();
  const [requiresReview, setRequiresReview] = useState(false);
  const content = useRef<HTMLDivElement>(null);
  const noteId = useId();

  const template = useMutation({
    mutationFn: fetchReceiptImportTemplate,
    onSuccess: (blob, format) => downloadBlob(blob, `mau-nhap-kho.${format}`),
  });
  const inspect = useMutation({ mutationFn: previewReceiptImport });
  const commit = useMutation({
    mutationFn: saveReceiptImport,
    onSuccess: (receipt) => {
      setPendingCommit(undefined);
      onImported(receipt);
      setOpen(false);
    },
    onError: (error) => {
      if (!shouldReuseIdempotencyKey(error)) setPendingCommit(undefined);
      if (isApiError(error) && error.status === 409) setRequiresReview(true);
    },
  });
  const busy = inspect.isPending || commit.isPending;
  const canSave = preview !== undefined && preview.rows.length > 0
    && preview.errors.length === 0 && preview.rows.every(row => row.valid);

  useEffect(() => {
    const region = content.current;
    if (preview && !editing && region) {
      const scrollBody = region.closest<HTMLElement>('.ant-modal-body');
      if (scrollBody) scrollBody.scrollTop = 0;
      region.focus({ preventScroll: true });
    }
  }, [preview, editing]);

  function check(nextEdits = edits) {
    if (!file || !supplier || busy || pendingCommit) return;
    commit.reset();
    inspect.mutate({ file, supplierId: supplier.value, note, edits: nextEdits }, {
      onSuccess: (result) => {
        setPreview(result);
        setEdits(nextEdits);
        setEditing(undefined);
        setRequiresReview(false);
      },
    });
  }

  function apply(row: ReceiptImportRow, changes: ReceiptImportValues) {
    const previous = edits.find(edit => edit.row === row.row);
    check([...edits.filter(edit => edit.row !== row.row), {
      row: row.row, values: { ...previous?.values, ...changes },
    }].sort((a, b) => a.row - b.row));
  }

  function save() {
    if (!file || !supplier || !preview || !canSave || busy || requiresReview) return;
    const submission = pendingCommit ?? {
      key: newIdempotencyKey(), file, supplierId: supplier.value, note, edits, previewToken: preview.previewToken,
    };
    setPendingCommit(submission);
    commit.mutate(submission);
  }

  function pick(picked: File) {
    let problem: string | undefined;
    if (!/\.(csv|xlsx)$/i.test(picked.name)) problem = 'Chọn file CSV (.csv) hoặc Excel (.xlsx).';
    else if (picked.size > RECEIPT_IMPORT_MAX_BYTES) problem = 'File tối đa 2 MB.';
    setFileError(problem);
    setFile(problem ? null : picked);
    setEdits([]);
    setPreview(undefined);
    setReviewSearch('');
    setRequiresReview(false);
    inspect.reset();
    commit.reset();
    return Upload.LIST_IGNORE;
  }

  function back() {
    if (preview) {
      setPreview(undefined);
      setReviewSearch('');
      inspect.reset();
      commit.reset();
    } else {
      setOpen(false);
    }
  }

  let primary = (
    <Button key="preview" type="primary" loading={inspect.isPending} disabled={!file || !supplier} onClick={() => check()}>
      Xem trước
    </Button>
  );
  if (preview) {
    primary = requiresReview ? (
      <Button key="refresh" type="primary" loading={inspect.isPending} onClick={() => check()}>Kiểm tra lại</Button>
    ) : (
      <Button key="save" type="primary" loading={commit.isPending} disabled={!canSave || inspect.isPending} onClick={save}>
        {pendingCommit && !commit.isPending ? 'Gửi lại phiếu' : 'Xác nhận nhập kho'}
      </Button>
    );
  }

  return (
    <Modal
      open={open}
      title="Nhập kho từ file"
      rootClassName="receipt-import-dialog"
      width={preview ? 860 : 620}
      styles={{ body: { maxHeight: '66dvh', overflowY: 'auto' } }}
      mask={{ closable: false }}
      closable={!busy && !pendingCommit}
      keyboard={!busy && !pendingCommit}
      onCancel={() => {
        if (busy || pendingCommit) return;
        if (editing) setEditing(undefined);
        else setOpen(false);
      }}
      afterOpenChange={(visible) => {
        if (!visible) onClose();
      }}
      footer={editing ? null : [
        <Button key="back" disabled={busy || pendingCommit !== undefined} onClick={back}>
          {preview ? 'Quay lại' : 'Hủy'}
        </Button>,
        primary,
      ]}
    >
      <Flex vertical gap={14} className="receipt-import-content">
        <Steps size="small" responsive={false} current={preview ? 1 : 0}
          items={[{ title: 'Chọn file' }, { title: 'Xem trước và sửa' }]} />
        {inspect.isError && <ImportProblem error={inspect.error} />}
        {commit.isError && <ImportProblem error={commit.error} />}
        {commit.isError && pendingCommit && (
          <Alert showIcon type="info" title="Bấm Gửi lại phiếu để nhận kết quả của cùng lần nhập." />
        )}
        {preview ? (
          <div tabIndex={-1} role="region" aria-label="Kết quả xem trước phiếu nhập" ref={content}>
            <Flex vertical gap={12}>
              <Typography.Text type="secondary">{supplier?.label} · {file?.name}</Typography.Text>
              {editing ? (
                <ReceiptImportRowEditor key={editing.row} row={editing}
                  errors={preview.errors.filter(error => error.row === editing.row)}
                  busy={busy} onCancel={() => setEditing(undefined)} onApply={changes => apply(editing, changes)} />
              ) : (
                <ReceiptImportReview key={preview.previewToken} preview={preview} onEdit={setEditing}
                  search={reviewSearch} onSearchChange={setReviewSearch}
                  disabled={busy || pendingCommit !== undefined} />
              )}
            </Flex>
          </div>
        ) : (
          <>
            {hasManualLines && (
              <Alert type="info" showIcon title="Các dòng đã chọn thủ công vẫn nằm ở màn Phiếu nhập."
                description="Phiếu đang tạo trong hộp thoại này sử dụng các dòng từ file." />
            )}
            <ReceiptSupplierSelect value={supplier} onChange={setSupplier} disabled={busy} />
            <div className="receipt-import-field">
              <label htmlFor={noteId}>Ghi chú (không bắt buộc)</label>
              <Input id={noteId} value={note} maxLength={500} disabled={busy} onChange={event => setNote(event.target.value)} />
            </div>
            <Flex vertical gap={8}>
              <Typography.Paragraph style={{ margin: 0 }}>
                Điền SKU, số lượng và giá nhập cho từng dòng. Với SKU mới, bổ sung tên, giá bán và mã cha nếu có biến thể.
              </Typography.Paragraph>
              <Flex wrap gap={8}>
                <Button icon={<DownloadOutlined aria-hidden />} onClick={() => template.mutate('xlsx')}
                  loading={template.isPending && template.variables === 'xlsx'} disabled={template.isPending}>
                  Tải mẫu Excel
                </Button>
                <Button icon={<DownloadOutlined aria-hidden />} onClick={() => template.mutate('csv')}
                  loading={template.isPending && template.variables === 'csv'} disabled={template.isPending}>
                  Tải mẫu CSV
                </Button>
              </Flex>
              {template.isError && <ImportProblem error={template.error} />}
            </Flex>
            <Flex vertical gap={8}>
              <Typography.Text type="secondary">
                File .xlsx hoặc .csv (UTF-8), tối đa 2 MB và {count.format(RECEIPT_IMPORT_MAX_ROWS)} dòng.
              </Typography.Text>
              <Flex align="center" gap={8} wrap>
                <Upload accept={accept} showUploadList={false} disabled={busy} beforeUpload={pick}>
                  <Button icon={<UploadOutlined aria-hidden />} disabled={busy}>{file ? 'Chọn file khác' : 'Chọn file'}</Button>
                </Upload>
                {file && <Typography.Text>{file.name}</Typography.Text>}
              </Flex>
              {fileError && <Typography.Text type="danger" role="alert">{fileError}</Typography.Text>}
            </Flex>
          </>
        )}
      </Flex>
    </Modal>
  );
}
