import React, { useEffect, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { Classroom } from '../../types';
import { api } from '../../api/client';
import { LoadingSpinner, ErrorBanner } from '../../components/UIStates';
import { ShieldAlert, Activity } from 'lucide-react';

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
    <div className="max-w-5xl mx-auto space-y-6">
      <div>
        <h1 className="text-2xl font-black text-slate-900 tracking-tight">Nhật ký kiểm toán (Audit Logs)</h1>
        <p className="text-xs text-slate-600">
          Ghi nhận toàn bộ thao tác quan trọng: phân quyền nhân sự, chỉnh sửa điểm số và giao dịch tài chính
        </p>
      </div>

      {loading && <LoadingSpinner message="Đang tải nhật ký kiểm toán..." />}
      {error && <ErrorBanner message={error} onRetry={fetchLogs} />}

      {!loading && (
        <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm divide-y divide-slate-100">
          {logs.map((log) => (
            <div key={log.id} className="p-4 flex items-center justify-between hover:bg-slate-50 transition text-xs">
              <div className="space-y-0.5">
                <div className="flex items-center space-x-2">
                  <span className="font-bold text-slate-900">{log.action}</span>
                  <span className="px-2 py-0.5 rounded font-mono text-[10px] bg-slate-100 text-slate-600">
                    {log.targetType}: {log.targetId ? log.targetId.substring(0, 8) : 'N/A'}
                  </span>
                </div>
                <div className="text-[11px] text-slate-500 font-mono">
                  Actor ID: {log.actorId ? log.actorId.substring(0, 8) : 'SYSTEM'}
                </div>
              </div>

              <span className="text-[11px] text-slate-500">
                {new Date(log.createdAt).toLocaleString('vi-VN')}
              </span>
            </div>
          ))}

          {logs.length === 0 && (
            <div className="p-8 text-center text-xs text-slate-500">Chưa ghi nhận sự kiện kiểm toán nào.</div>
          )}
        </div>
      )}
    </div>
  );
};
