import React, { useEffect, useId, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { Classroom } from '../../types';
import { api } from '../../api/client';
import { AboutSections, AboutSectionsEditor, AboutSection } from '../../components/AboutSections';
import { LoadingSpinner, ErrorBanner } from '../../components/UIStates';
import { Info, ShieldAlert, Edit3, Check } from 'lucide-react';

interface ClassAbout {
  id: string;
  classId: string;
  contentMarkdown: string;
  rulesMarkdown: string;
  publishedVersion: number;
  sections?: AboutSection[];
}

export const AboutTab: React.FC = () => {
  const { classroom } = useOutletContext<{ classroom: Classroom }>();

  const [about, setAbout] = useState<ClassAbout | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Edit mode for owner/staff
  const [editing, setEditing] = useState(false);
  const [contentDraft, setContentDraft] = useState('');
  const [rulesDraft, setRulesDraft] = useState('');
  const [sections, setSections] = useState<AboutSection[]>([]);
  const [saveError, setSaveError] = useState('');
  const [saving, setSaving] = useState(false);
  const contentId = useId();
  const rulesId = useId();

  const canEdit = classroom.userRole === 'OWNER' || classroom.studioPermissions?.includes('ABOUT:EDIT');

  const fetchAbout = async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await api.get<ClassAbout>(`/classes/${classroom.id}/about`);
      setAbout(data);
      setContentDraft(data.contentMarkdown || '');
      setRulesDraft(data.rulesMarkdown || '');
      setSections(data.sections || []);
    } catch (err: any) {
      setError(err.message || 'Không thể tải thông tin giới thiệu');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchAbout();
  }, [classroom.id]);

  const handleSave = async () => {
    setSaving(true);
    setSaveError('');
    try {
      const updated = await api.put<ClassAbout>(`/classes/${classroom.id}/about`, {
        contentMarkdown: contentDraft,
        rulesMarkdown: rulesDraft,
        sections: sections.map(s => ({ ...s, imageUrl: s.mediaAssetId ? '' : s.imageUrl })),
        publishedVersion: about?.publishedVersion,
      });
      setAbout(updated);
      setEditing(false);
    } catch (err: any) {
      setSaveError(err.message || 'Cập nhật thất bại. Nội dung bạn nhập vẫn được giữ lại.');
    } finally {
      setSaving(false);
    }
  };

  if (loading) return <LoadingSpinner message="Đang tải trang giới thiệu..." />;
  if (error) return <ErrorBanner message={error} onRetry={fetchAbout} />;

  return (
    <div className="max-w-4xl mx-auto space-y-6">
      <div className="flex flex-wrap gap-4 justify-between items-center">
        <div>
          <h2 className="text-xl font-extrabold text-slate-900 tracking-tight">Giới thiệu & Nội quy</h2>
          <p className="text-xs text-slate-500">Thông tin tổng quan về mục tiêu lớp học và quy định văn hóa lớp</p>
        </div>

        {canEdit && !editing && (
          <button
            onClick={() => setEditing(true)}
            className="inline-flex items-center space-x-1.5 px-3.5 py-2 bg-indigo-50 hover:bg-indigo-100 text-indigo-700 rounded-xl text-xs font-bold transition"
          >
            <Edit3 className="w-3.5 h-3.5" />
            <span>Chỉnh sửa nội dung</span>
          </button>
        )}

        {editing && (
          <div className="flex space-x-2">
            <button
              onClick={() => setEditing(false)}
              className="px-3 py-1.5 border border-slate-300 text-slate-600 rounded-xl text-xs font-semibold hover:bg-slate-50"
            >
              Hủy
            </button>
            <button
              onClick={handleSave}
              disabled={saving}
              className="inline-flex items-center space-x-1.5 px-4 py-1.5 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl text-xs font-bold transition disabled:opacity-50"
            >
              <Check className="w-3.5 h-3.5" />
              <span>{saving ? 'Đang lưu...' : 'Lưu thay đổi'}</span>
            </button>
          </div>
        )}
      </div>

      {saveError && <p role="alert" className="rounded-xl bg-red-50 p-4 text-red-800">{saveError}</p>}
      {editing ? (
        <div className="space-y-6 bg-white p-6 rounded-2xl border border-slate-200">
          <AboutSectionsEditor classId={classroom.id} sections={sections} onChange={setSections} />
          <div>
            <label htmlFor={contentId} className="block text-xs font-bold text-slate-700 uppercase mb-2">
              Nội dung giới thiệu lớp học (Markdown)
            </label>
            <textarea
              id={contentId}
              rows={8}
              value={contentDraft}
              onChange={(e) => setContentDraft(e.target.value)}
              className="w-full p-4 bg-slate-50 border border-slate-200 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 font-mono"
            />
          </div>

          <div>
            <label htmlFor={rulesId} className="block text-xs font-bold text-slate-700 uppercase mb-2">
              Nội quy lớp học (Markdown)
            </label>
            <textarea
              id={rulesId}
              rows={6}
              value={rulesDraft}
              onChange={(e) => setRulesDraft(e.target.value)}
              className="w-full p-4 bg-slate-50 border border-slate-200 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 font-mono"
            />
          </div>
        </div>
      ) : (
        <div className="space-y-6">
          <AboutSections sections={about?.sections || []} />
          {/* Main introduction */}
          <div className="bg-white rounded-2xl border border-slate-200 p-6 md:p-8 shadow-sm">
            <div className="flex items-center space-x-2 text-indigo-600 mb-4">
              <Info className="w-5 h-5" />
              <span className="text-xs font-bold uppercase tracking-wider">Thông tin chi tiết</span>
            </div>
            <div className="prose prose-slate max-w-none text-slate-700 leading-relaxed whitespace-pre-line text-sm md:text-base">
              {about?.contentMarkdown || 'Lớp học chưa có mô tả giới thiệu.'}
            </div>
          </div>

          {/* Rules */}
          <div className="bg-amber-50/50 rounded-2xl border border-amber-200/80 p-6 md:p-8 shadow-sm">
            <div className="flex items-center space-x-2 text-amber-700 mb-4">
              <ShieldAlert className="w-5 h-5" />
              <span className="text-xs font-bold uppercase tracking-wider">Nội quy học tập bắt buộc</span>
            </div>
            <div className="prose prose-amber max-w-none text-slate-800 leading-relaxed whitespace-pre-line text-sm">
              {about?.rulesMarkdown || 'Nội quy đang được cập nhật.'}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
