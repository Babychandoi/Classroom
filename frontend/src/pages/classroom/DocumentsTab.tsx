import React, { useEffect, useMemo, useState } from 'react';
import { useOutletContext, Link } from 'react-router-dom';
import { Classroom, DocumentAsset } from '../../types';
import { api } from '../../api/client';
import { useAuth } from '../../context/AuthContext';
import { LoadingSpinner, ErrorBanner, EmptyState } from '../../components/UIStates';
import { FilterChip, buttonClass } from '../../components/ui';
import { formatDate } from '../../api/format';
import { Download, FileArchive, FileImage, FileSpreadsheet, FileText, FileVideo, Search, Star } from 'lucide-react';

/** File kind shown on the tile: label + topic colour + stroke icon (by MIME type, then by extension). */
function fileKind(doc: DocumentAsset): { label: string; tone: string; Icon: typeof FileText } {
  const mime = (doc.mimeType || '').toLowerCase();
  const ext = (doc.filename || '').split('.').pop()?.toLowerCase() || '';
  if (mime === 'application/pdf' || ext === 'pdf') return { label: 'PDF', tone: 'bg-red-100 text-red-800', Icon: FileText };
  if (mime.includes('sheet') || mime.includes('excel') || mime === 'text/csv' || ['xls', 'xlsx', 'csv', 'ods'].includes(ext)) return { label: 'Bảng tính', tone: 'bg-green-100 text-green-800', Icon: FileSpreadsheet };
  if (mime.startsWith('image/')) return { label: 'Hình ảnh', tone: 'bg-sky-100 text-sky-800', Icon: FileImage };
  if (mime.startsWith('video/') || mime.startsWith('audio/')) return { label: 'Media', tone: 'bg-amber-100 text-amber-800', Icon: FileVideo };
  if (mime.includes('zip') || mime.includes('compressed') || ['zip', 'rar', '7z'].includes(ext)) return { label: 'Tệp nén', tone: 'bg-violet-100 text-violet-800', Icon: FileArchive };
  if (mime.includes('word') || mime.includes('presentation') || ['doc', 'docx', 'ppt', 'pptx'].includes(ext)) return { label: ext ? ext.toUpperCase() : 'Tài liệu', tone: 'bg-blue-100 text-blue-800', Icon: FileText };
  return { label: ext ? ext.toUpperCase() : 'Tài liệu', tone: 'bg-slate-200 text-slate-700', Icon: FileText };
}

/** 2516582 -> "2,4 MB". */
function formatBytes(bytes?: number): string {
  if (!bytes || bytes <= 0) return '';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1).replace('.', ',')} MB`;
}

type DocFilter = 'ALL' | 'MEMBERS' | 'PRO';

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

  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<DocFilter>('ALL');
  const proCount = documents.filter((d) => d.visibility === 'PRO').length;
  const shown = useMemo(() => {
    const q = query.trim().toLowerCase();
    return documents.filter((d) =>
      (filter === 'ALL' || (filter === 'PRO' ? d.visibility === 'PRO' : d.visibility !== 'PRO'))
      && (!q || `${d.title} ${d.filename ?? ''} ${d.description ?? ''}`.toLowerCase().includes(q)));
  }, [documents, query, filter]);

  return (
    <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,1fr)_360px]">
      <div className="min-w-0 space-y-6">
        <div>
          <h2 className="text-h2-sm font-semibold text-slate-900">Tài liệu học tập</h2>
          <p className="mt-0.5 text-meta text-slate-600">Giáo trình, tài liệu tham khảo và tuyển tập đề thi được giáo viên biên soạn</p>
        </div>

        {!loading && !error && documents.length > 0 && (
          <div className="flex flex-wrap items-center gap-3">
            <label className="flex h-11 min-w-[240px] flex-1 items-center gap-2.5 rounded-input border border-slate-200 bg-white px-3.5 focus-within:border-blue-600 focus-within:ring-2 focus-within:ring-blue-600/20">
              <Search className="h-4 w-4 flex-shrink-0 text-slate-600" strokeWidth={1.5} aria-hidden="true" />
              <input
                type="search"
                aria-label="Tìm tài liệu"
                placeholder="Tìm theo tên tài liệu…"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                className="min-w-0 flex-1 bg-transparent text-ui text-slate-900 outline-none placeholder:text-slate-400 focus-visible:outline-none"
              />
            </label>
            {proCount > 0 && (
              <div className="flex flex-wrap items-center gap-2" role="group" aria-label="Lọc tài liệu">
                <FilterChip selected={filter === 'ALL'} onClick={() => setFilter('ALL')}>Tất cả</FilterChip>
                <FilterChip selected={filter === 'MEMBERS'} onClick={() => setFilter('MEMBERS')}>Mọi thành viên</FilterChip>
                <FilterChip selected={filter === 'PRO'} onClick={() => setFilter('PRO')}>Chỉ PRO</FilterChip>
              </div>
            )}
          </div>
        )}

        {loading && <LoadingSpinner message="Đang tải danh sách tài liệu..." />}
        {error && <ErrorBanner message={error} onRetry={fetchDocuments} />}

        {!loading && !error && documents.length === 0 && (
          <EmptyState
            title="Chưa có tài liệu nào"
            description="Giáo viên chưa tải lên tài liệu cho lớp học này."
          />
        )}

        {!loading && !error && documents.length > 0 && (
          <section aria-labelledby="docs-list-title">
            <h3 id="docs-list-title" className="mb-3 text-body font-semibold text-slate-900">
              {query || filter !== 'ALL' ? 'Kết quả' : 'Mới chia sẻ'} · <span className="font-medium text-slate-600 tabular">{shown.length}</span>
            </h3>
            {shown.length === 0 ? (
              <p className="rounded-card border border-slate-200 bg-white px-6 py-8 text-center text-ui text-slate-600 shadow-hairline">
                Không có tài liệu khớp. Thử từ khóa khác hoặc chọn “Tất cả”.
              </p>
            ) : (
              <ul className="divide-y divide-slate-100 overflow-hidden rounded-card border border-slate-200 bg-white shadow-hairline">
                {shown.map((doc) => {
                  const kind = fileKind(doc);
                  const meta = [kind.label, formatBytes(doc.sizeBytes), formatDate(doc.createdAt)].filter(Boolean).join(' · ');
                  return (
                    <li key={doc.id} className="grid grid-cols-[44px_minmax(0,1fr)] items-center gap-x-3.5 gap-y-3 px-4 py-4 sm:grid-cols-[44px_minmax(0,1fr)_auto] sm:px-6">
                      <span aria-hidden="true" className={`inline-flex h-11 w-11 items-center justify-center rounded-btn ${kind.tone}`}>
                        <kind.Icon className="h-5 w-5" strokeWidth={1.75} />
                      </span>
                      <span className="flex min-w-0 flex-col gap-0.5">
                        <span className="flex min-w-0 items-center gap-2">
                          <span className="truncate text-body-sm font-semibold text-slate-900">{doc.title}</span>
                          {doc.visibility === 'PRO' && (
                            <span className="inline-flex h-[18px] flex-shrink-0 items-center gap-1 rounded-full bg-amber-100 px-1.5 text-micro-xs font-bold uppercase text-amber-800">
                              <Star className="h-2.5 w-2.5 fill-current" aria-hidden="true" />
                              Chỉ PRO
                            </span>
                          )}
                        </span>
                        {doc.description && <span className="line-clamp-1 text-meta text-slate-600">{doc.description}</span>}
                        <span className="truncate text-meta text-slate-500 tabular">
                          {doc.filename ? `${doc.filename} · ` : ''}{meta}
                        </span>
                      </span>
                      <button
                        type="button"
                        onClick={() => handleDownload(doc)}
                        disabled={downloadingId === doc.id}
                        className={buttonClass('secondary', 'md', 'col-span-2 sm:col-span-1')}
                      >
                        <Download className="h-4 w-4 text-slate-600" strokeWidth={1.75} aria-hidden="true" />
                        <span>{downloadingId === doc.id ? 'Đang chuẩn bị...' : 'Tải xuống'}</span>
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
          </section>
        )}
      </div>

      <aside className="min-w-0 space-y-5">
        {!loading && !error && documents.length > 0 && (
          <section className="rounded-card border border-slate-200 bg-white p-[22px] shadow-hairline">
            <h3 className="text-body-sm font-semibold text-slate-900">Kho tài liệu</h3>
            <dl className="mt-3 space-y-2.5">
              {[
                ['Tổng tài liệu', documents.length],
                ['Cho mọi thành viên', documents.length - proCount],
                ['Chỉ hội viên PRO', proCount],
              ].map(([label, value]) => (
                <div key={label} className="flex items-baseline justify-between gap-3">
                  <dt className="text-meta text-slate-600">{label}</dt>
                  <dd className="text-ui font-semibold text-slate-900 tabular">{value}</dd>
                </div>
              ))}
            </dl>
          </section>
        )}
        <section className="rounded-card border border-slate-200 bg-white p-[22px] shadow-hairline">
          <h3 className="text-body-sm font-semibold text-slate-900">Cần thêm tài liệu?</h3>
          <p className="mt-2 text-meta text-slate-600">Hỏi giáo viên hoặc các bạn trong Thảo luận — tài liệu mới sẽ được đăng ở đây.</p>
          <Link to={`/classes/${classroom.slug}/feed`} className="mt-2.5 inline-block text-meta font-medium text-blue-600 hover:text-blue-700">
            Hỏi trong Thảo luận
          </Link>
        </section>
      </aside>
    </div>
  );
};
