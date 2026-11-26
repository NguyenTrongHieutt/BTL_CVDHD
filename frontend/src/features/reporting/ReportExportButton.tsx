import { DownloadOutlined } from '@ant-design/icons';
import { Button } from 'antd';
import { downloadBlob } from '../../shared/api/downloadBlob';
import { encodeReportCsv } from './reportCsv';
import type { CsvRow } from './reportCsv';

interface ReportExportButtonProps {
  filename: string;
  rows?: () => readonly CsvRow[];
}

export function ReportExportButton({ filename, rows }: ReportExportButtonProps) {
  return (
    <Button icon={<DownloadOutlined />} disabled={!rows} onClick={() => {
      if (rows) {
        const file = new Blob([encodeReportCsv(rows())], { type: 'text/csv;charset=utf-8' });
        downloadBlob(file, filename + '.csv');
      }
    }}>
      Tải CSV
    </Button>
  );
}
