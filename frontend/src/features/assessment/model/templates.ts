import type { AssessmentDocument, AssessmentKind } from '../types';
import { blankGroup, blankItem, blankSection, newDocument } from './doc';

export type TemplateKey = 'BLANK' | 'LISTENING' | 'READING' | 'QUIZ15';

export const TEMPLATES: { key: TemplateKey; title: string; description: string }[] = [
  { key: 'BLANK', title: 'Đề trống', description: 'Bắt đầu với một phần trống' },
  { key: 'LISTENING', title: 'Bài nghe + câu hỏi con', description: 'Nhóm audio chung, 4 câu khác loại' },
  { key: 'READING', title: 'Đọc hiểu', description: 'Đoạn văn + 4 câu, giữ ngữ liệu khi cuộn' },
  { key: 'QUIZ15', title: 'Kiểm tra 15 phút', description: 'Một phần trắc nghiệm, chấm tự động' },
];

export interface TemplateMeta { title: string; kind: AssessmentKind; grade: number | null; subjectCode: string | null; languageCode: string | null; topicTitle: string | null }

/** Builds the starting document of a template (fresh ids every time). */
export function buildTemplate(key: TemplateKey, meta: TemplateMeta): AssessmentDocument {
  const section = blankSection('Phần I');
  switch (key) {
    case 'LISTENING': {
      const g = blankGroup('Q21');
      section.title = 'Phần I — Nghe';
      section.nodes.push({ ...g, children: [blankItem('Q01'), blankItem('Q03'), blankItem('Q07'), blankItem('Q22')] });
      break;
    }
    case 'READING': {
      const g = blankGroup('Q13');
      section.title = 'Phần I — Đọc hiểu';
      section.nodes.push({ ...g, children: [blankItem('Q01'), blankItem('Q04'), blankItem('Q10'), blankItem('Q12')] });
      break;
    }
    case 'QUIZ15':
      section.title = 'Phần I — Trắc nghiệm';
      for (let i = 0; i < 5; i++) section.nodes.push(blankItem('Q01'));
      break;
    case 'BLANK':
    default:
      break;
  }
  return newDocument(meta, [section]);
}
