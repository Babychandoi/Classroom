import React, { useEffect, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { Classroom } from '../../types';
import { api } from '../../api/client';
import { LoadingSpinner, ErrorBanner } from '../../components/UIStates';
import { formatDateTime } from '../../api/format';
import { Card } from '../../components/ui';
import { EmptyRow, PageHeader, StudioPage, tdClass, thClass } from './studioUi';

interface AuditItem {
  id: string;
  actorId: string;
  action: string;
  targetType: string;
  targetId: string;
  detailsJson: string;
  createdAt: string;
}

export const StudioAudit: React.FC = () => {
  const { classroom } = useOutletContext<{ classroom: Classroom }>();

  const [logs, setLogs] = useState<AuditItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchLogs = async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await api.get<AuditItem[]>(`/classes/${classroom.id}/audit`);
      setLogs(data || []);
    } catch (err: any) {
      setError(err.message || 'Không thể tải nhật ký kiểm toán');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchLogs();
  }, [classroom.id]);

  return (
    <StudioPage>
      <PageHeader
        title="Nhật ký"
        description="Ghi lại các thao tác quan trọng trong lớp: phân quyền trợ giảng, sửa điểm và giao dịch thanh toán."
      />

      {loading && <LoadingSpinner message="Đang tải nhật ký kiểm toán..." />}
      {error && <ErrorBanner message={error} onRetry={fetchLogs} />}

      {!loading && (
        <Card padded={false} className="overflow-hidden">
          {logs.length === 0 ? (
            <EmptyRow>Chưa ghi nhận sự kiện kiểm toán nào. Các thao tác như phân quyền hay sửa điểm sẽ hiện ở đây.</EmptyRow>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[640px]">
                <caption className="sr-only">Nhật ký kiểm toán của lớp</caption>
                <thead className="border-b border-slate-200 bg-slate-50">
                  <tr>
                    <th scope="col" className={thClass}>Thời gian</th>
                    <th scope="col" className={thClass}>Thao tác</th>
                    <th scope="col" className={thClass}>Đối tượng</th>
                    <th scope="col" className={thClass}>Người thực hiện</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {logs.map((log) => (
                    <tr key={log.id} className="transition-colors duration-micro hover:bg-slate-50">
                      <td className={`${tdClass} whitespace-nowrap text-meta text-slate-600 tabular`}>{formatDateTime(log.createdAt)}</td>
                      <td className={`${tdClass} font-semibold`}>{log.action}</td>
                      <td className={tdClass}>
                        <span className="inline-flex h-[22px] items-center rounded-full bg-slate-100 px-2 font-mono text-micro font-semibold text-slate-600">
                          {log.targetType}: {log.targetId ? log.targetId.substring(0, 8) : 'N/A'}
                        </span>
                      </td>
                      <td className={`${tdClass} font-mono text-meta text-slate-500`}>{log.actorId ? log.actorId.substring(0, 8) : 'SYSTEM'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      )}
    </StudioPage>
  );
};
