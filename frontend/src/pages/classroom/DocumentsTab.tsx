import React, { useEffect, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { Classroom, DocumentAsset } from '../../types';
import { api } from '../../api/client';
import { useAuth } from '../../context/AuthContext';
import { LoadingSpinner, ErrorBanner, EmptyState } from '../../components/UIStates';
import { FileText, Download, Lock } from 'lucide-react';

export const DocumentsTab: React.FC = () => {
  const { classroom } = useOutletContext<{ classroom: Classroom }>();
  const { user } = useAuth();

  const [documents, setDocuments] = useState<DocumentAsset[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [downloadingId, setDownloadingId] = useState<string | null>(null);

  const fetchDocuments = async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await api.get<DocumentAsset[]>(`/classes/${classroom.id}/documents`);
      setDocuments(data || []);
    } catch (err: any) {
      setError(err.message || 'Không thể tải danh sách tài liệu');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchDocuments();
  }, [classroom.id, user]);

  // R8-01: request only the short-lived presigned MinIO URL and hand it straight to the browser.
  // MinIO serves the response with Content-Disposition: attachment (set server-side when the URL
  // is issued), so a plain anchor click downloads the file directly from the object store with
  // native Range support - no more buffering the whole file into a Blob first, which silently
  // truncated large documents once the proxy endpoint's 30s async window elapsed.
  const handleDownload = async (doc: DocumentAsset) => {
    try {
      setDownloadingId(doc.id);
      const res = await api.get<{ downloadUrl: string }>(`/documents/${doc.id}/download-url`);
      const link = document.createElement('a');
      link.href = res.downloadUrl;
      link.rel = 'noopener';
      link.download = doc.filename || doc.title;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
    } catch (err: any) {
      if (err.code === 'PRO_MEMBERSHIP_REQUIRED') {
        alert('Tài liệu này chỉ dành riêng cho hội viên PRO của lớp học.');
      } else {
        alert(err.message || 'Không thể tải tài liệu');
      }
    } finally {
      setDownloadingId(null);
    }
  };

  return (
    <div className="max-w-4xl mx-auto space-y-6">
      <div>
        <h2 className="text-xl font-extrabold text-slate-900 tracking-tight">Tài liệu học tập</h2>
        <p className="text-xs text-slate-500">Giáo trình, tài liệu tham khảo và tuyển tập đề thi được giáo viên biên soạn</p>
      </div>

      {loading && <LoadingSpinner message="Đang tải danh sách tài liệu..." />}
      {error && <ErrorBanner message={error} onRetry={fetchDocuments} />}

      {!loading && !error && documents.length === 0 && (
        <EmptyState
          title="Chưa có tài liệu nào"
          description="Giáo viên chưa tải lên tài liệu cho lớp học này."
        />
      )}

      <div className="space-y-3">
        {documents.map((doc) => (
          <div
            key={doc.id}
            className="bg-white rounded-2xl border border-slate-200 p-5 shadow-sm flex items-center justify-between hover:border-slate-300 transition"
          >
            <div className="flex items-center space-x-3.5">
              <div className="w-11 h-11 rounded-xl bg-indigo-50 text-indigo-600 flex items-center justify-center flex-shrink-0">
                <FileText className="w-6 h-6" />
              </div>
              <div>
                <div className="flex items-center space-x-2">
                  <h4 className="text-sm font-bold text-slate-900">{doc.title}</h4>
                  {doc.visibility === 'PRO' && (
                    <span className="px-2 py-0.5 rounded-full text-[10px] font-bold bg-amber-100 text-amber-800">
                      Chỉ PRO ⭐
                    </span>
                  )}
                </div>
                <p className="text-xs text-slate-500 mt-0.5 line-clamp-1">{doc.description || 'Tài liệu tham khảo đính kèm.'}</p>
                <div className="text-[11px] text-slate-500 mt-1">
                  {doc.filename && <span>{doc.filename} • </span>}
                  <span>{new Date(doc.createdAt).toLocaleDateString('vi-VN')}</span>
                </div>
              </div>
            </div>

            <button
              onClick={() => handleDownload(doc)}
              disabled={downloadingId === doc.id}
              className="inline-flex items-center space-x-1.5 px-4 py-2 bg-slate-900 hover:bg-indigo-600 text-white rounded-xl text-xs font-bold transition shadow-sm disabled:opacity-50 flex-shrink-0"
            >
              <Download className="w-3.5 h-3.5" />
              <span>{downloadingId === doc.id ? 'Đang chuẩn bị...' : 'Tải xuống'}</span>
            </button>
          </div>
        ))}
      </div>
    </div>
  );
};
