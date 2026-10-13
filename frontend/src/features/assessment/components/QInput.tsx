import React from 'react';
import { ArrowDown, ArrowUp, Check, ChevronRight } from 'lucide-react';
import type { ItemResponse, ItemResult, LearnerItem, RecordingInfo } from '../types';
import { ABC, abcd, cx, wordCount } from '../format';
import { ar, ia } from '../model/answers';
import { RichLine } from './ContentBlocks';
import { Recorder } from './Recorder';
import { Seg } from './primitives';
import { AdvancedInput,type EvidenceHooks } from './AdvancedInput';

type Mark = 'correct' | 'wrong' | null;

export interface RecordingHooks {
  recordingTakes?:Record<string,number>;
  recordings?: Record<string, RecordingInfo>;
  onUploadRecording?: (itemId: string, file: File | Blob, durationMs: number, filename: string) => Promise<RecordingInfo>;
  onDeleteRecording?: (itemId: string) => Promise<void>;
  onRecordingUrl?: (itemId: string) => Promise<string>;
}

export interface QInputProps extends RecordingHooks,EvidenceHooks {
  renderAudio?: (assetKey:string)=>React.ReactNode;
  item: LearnerItem;
  value: ItemResponse | null | undefined;
  onChange: (value: ItemResponse | null) => void;
  /** Result mode: inputs are locked and coloured. */
  result?: ItemResult | null;
  preview?: boolean;
}

const markBox = (m: Mark, on: boolean) =>
  m === 'correct' ? 'border-[1.5px] border-green-400 bg-green-50' : m === 'wrong' ? 'border-[1.5px] border-red-400 bg-red-50' : on ? 'border-[1.5px] border-slate-900 bg-slate-50' : 'border border-slate-200 bg-white hover:border-slate-300';

const OptBtn: React.FC<{ on: boolean; mark: React.ReactNode; m: Mark; onClick?: () => void; square?: boolean; children: React.ReactNode; role: 'radio' | 'checkbox' }> = ({ on, mark, m, onClick, square, children, role }) => (
  <button type="button" role={role} aria-checked={on} onClick={onClick} disabled={!onClick && !on && !m}
    className={cx('flex min-h-[48px] w-full items-center gap-3 rounded-[14px] px-3.5 py-2.5 text-left text-body-sm font-medium text-slate-900 transition-colors', markBox(m, on), !onClick && 'cursor-default')}>
    <span className={cx('flex h-7 w-7 flex-none items-center justify-center text-meta font-bold', square ? 'rounded-lg' : 'rounded-full', on ? 'bg-slate-900 text-white' : 'bg-slate-100 text-slate-600')}>
      {square && on ? <Check className="h-3.5 w-3.5" strokeWidth={2.6} aria-hidden="true" /> : mark}
    </span>
    <span className="flex-1">{children}</span>
    {m === 'correct' && <Check className="h-[18px] w-[18px] text-green-700" strokeWidth={2.4} aria-label="Đáp án đúng" />}
  </button>
);

const inlineBase = 'mx-1 h-[34px] rounded-t-lg border-0 border-b-2 bg-slate-50 px-2.5 align-baseline text-body-sm font-semibold outline-none focus:bg-blue-50';
const inlineMark = (m: Mark) => (m === 'correct' ? 'border-green-400 bg-green-50' : m === 'wrong' ? 'border-red-400 bg-red-50' : 'border-slate-900');

/** Splits a prompt at `___` and renders `render(i)` in each gap. */
const Inline: React.FC<{ text: string; render: (i: number) => React.ReactNode }> = ({ text, render }) => {
  const parts = text.split('___');
  return (
    <div className="text-body leading-[34px] text-slate-900">
      {parts.map((p, i) => (
        <React.Fragment key={i}>
          <RichLine text={p} />
          {i < parts.length - 1 && render(i)}
        </React.Fragment>
      ))}
    </div>
  );
};

/** Student input for each P0 item type (prototype 17-qb-attempt.jsx QInput), on learner DTOs. */
export const QInput: React.FC<QInputProps> = ({ item, value, onChange, result, preview, recordings,recordingTakes, onUploadRecording, onDeleteRecording, onRecordingUrl,renderAudio,...evidenceHooks }) => {
  const lock = !!result;
  const v = ar(value);
  const x = ia(item);
  const correct = ar(result?.correctResponse ?? null);
  const sub = result?.subResults ?? null;
  const set = (r: ItemResponse | null) => { if (!lock) onChange(r); };
  const subMark = (id: string, fallback: boolean | null): Mark => {
    if (!result) return null;
    if (sub && id in sub) return sub[id] ? 'correct' : 'wrong';
    return fallback == null ? null : fallback ? 'correct' : 'wrong';
  };

  switch (item.type) {
    case 'Q01':
      return (
        <div role="radiogroup" aria-label={`Phương án ${item.label}`} className="flex flex-col gap-2">
          {(x.options ?? []).map((o, i) => {
            const m: Mark = result ? (o.id === correct.optionId ? 'correct' : v.optionId === o.id ? 'wrong' : null) : null;
            return <div key={o.id}><OptBtn role="radio" on={v.optionId === o.id} m={m} mark={ABC[i]} onClick={lock ? undefined : () => set({ optionId: o.id })}>{o.text ? <RichLine text={o.text} /> : <i className="text-slate-500">Phương án {ABC[i]}</i>}{o.assetKey && evidenceHooks.images?.[o.assetKey] && <img src={evidenceHooks.images[o.assetKey].url} alt={o.text || `Phương án ${ABC[i]}`} className="mt-2 max-h-40 max-w-full object-contain"/>}</OptBtn>{o.audioAssetKey && <div className="mt-2">{renderAudio?.(o.audioAssetKey)}</div>}</div>;
          })}
        </div>
      );
    case 'Q02': {
      const sel = v.optionIds ?? [];
      return (
        <div role="group" aria-label={`Phương án ${item.label}`} className="flex flex-col gap-2">
          <div className="text-caption font-medium text-slate-500">Chọn tất cả đáp án đúng</div>
          {(x.options ?? []).map((o, i) => {
            const on = sel.includes(o.id);
            const m: Mark = result ? ((correct.optionIds ?? []).includes(o.id) ? 'correct' : on ? 'wrong' : null) : null;
            return <div key={o.id}><OptBtn role="checkbox" square on={on} m={m} mark={ABC[i]} onClick={lock ? undefined : () => set({ optionIds: on ? sel.filter((s) => s !== o.id) : [...sel, o.id] })}>{o.text ? <RichLine text={o.text} /> : <i className="text-slate-500">Phương án {ABC[i]}</i>}{o.assetKey && evidenceHooks.images?.[o.assetKey] && <img src={evidenceHooks.images[o.assetKey].url} alt={o.text || `Phương án ${ABC[i]}`} className="mt-2 max-h-40 max-w-full object-contain"/>}</OptBtn>{o.audioAssetKey && <div className="mt-2">{renderAudio?.(o.audioAssetKey)}</div>}</div>;
          })}
        </div>
      );
    }
    case 'Q03':
      return (
        <div role="radiogroup" aria-label={`Đúng hay sai — ${item.label}`} className="grid grid-cols-2 gap-2.5">
          {([[true, 'Đúng'], [false, 'Sai']] as const).map(([k, l]) => {
            const m: Mark = result ? (correct.value === k ? 'correct' : v.value === k ? 'wrong' : null) : null;
            return <OptBtn key={l} role="radio" on={v.value === k} m={m} mark={l[0]} onClick={lock ? undefined : () => set({ value: k })}>{l}</OptBtn>;
          })}
        </div>
      );
    case 'Q04': {
      const vals = v.values ?? {};
      return (
        <div className="overflow-hidden rounded-[14px] border border-slate-200">
          {(x.statements ?? []).map((s, i) => {
            const m = subMark(s.id, result && correct.values ? vals[s.id] === correct.values[s.id] : null);
            return (
              <div key={s.id} className={cx('flex flex-wrap items-center gap-3 px-3 py-2.5', i > 0 && 'border-t border-slate-100', m === 'correct' ? 'bg-green-50' : m === 'wrong' ? 'bg-red-50' : 'bg-white')}>
                <span className="w-4 text-meta font-bold text-slate-500">{abcd[i]})</span>
                <span className="min-w-[160px] flex-1 text-ui font-medium"><RichLine text={s.text} /></span>
                <Seg size="sm" label={`Ý ${abcd[i]}: đúng hay sai`} value={vals[s.id]} disabled={lock} onChange={(b) => set({ values: { ...vals, [s.id]: b as boolean } })} items={[[true, 'Đ'], [false, 'S']]} />
                {result && m === 'wrong' && correct.values && s.id in correct.values && <span className="w-9 text-caption font-semibold text-green-700">→ {correct.values[s.id] ? 'Đ' : 'S'}</span>}
              </div>
            );
          })}
        </div>
      );
    }
    case 'Q05': {
      const pairs = v.pairs ?? {};
      const right = x.right ?? [];
      return (
        <div className="flex flex-col gap-2">
          {(x.left ?? []).map((l) => {
            const m = subMark(l.id, result && correct.pairs ? pairs[l.id] === correct.pairs[l.id] : null);
            return (
              <div key={l.id} className="grid grid-cols-[minmax(0,.8fr)_20px_minmax(0,1.4fr)] items-center gap-2">
                <span className="rounded-xl bg-slate-100 px-3 py-2.5 text-ui font-semibold"><RichLine text={l.text} /></span>
                <ChevronRight className="h-4 w-4 text-slate-500" aria-hidden="true" />
                <select aria-label={`Ghép với "${l.text}"`} disabled={lock} value={pairs[l.id] ?? ''}
                  onChange={(e) => { const next = { ...pairs }; if (e.target.value) next[l.id] = e.target.value; else delete next[l.id]; set(Object.keys(next).length ? { pairs: next } : null); }}
                  className={cx('h-[42px] min-w-0 rounded-xl px-2.5 text-ui', markBox(m, !!pairs[l.id]))}>
                  <option value="">Chọn…</option>
                  {right.map((r) => <option key={r.id} value={r.id}>{r.text}</option>)}
                </select>
                {result && m === 'wrong' && correct.pairs?.[l.id] && <span className="col-span-3 text-caption font-semibold text-green-700">Đáp án: {right.find((r) => r.id === correct.pairs![l.id])?.text}</span>}
              </div>
            );
          })}
        </div>
      );
    }
    case 'Q06': {
      const items = x.items ?? [];
      const order = v.order && v.order.length === items.length ? v.order : items.map((i) => i.id);
      const byId = new Map(items.map((i) => [i.id, i]));
      const mv = (i: number, d: number) => { const b = [...order]; [b[i], b[i + d]] = [b[i + d], b[i]]; set({ order: b }); };
      return (
        <div className="flex flex-col gap-1.5">
          <ol className="flex flex-col gap-1.5" aria-label={`Thứ tự ${item.label}`}>
            {order.map((id, i) => {
              const m: Mark = result && correct.order ? (correct.order[i] === id ? 'correct' : 'wrong') : null;
              return (
                <li key={id} draggable={!lock} onDragStart={e=>e.dataTransfer.setData('text/plain',id)} onDragOver={e=>{if(!lock)e.preventDefault();}} onDrop={e=>{e.preventDefault();if(lock)return;const dragged=e.dataTransfer.getData('text/plain');if(!order.includes(dragged) || dragged===id)return;const nextOrder=order.filter(value=>value!==dragged);nextOrder.splice(i,0,dragged);set({order:nextOrder});}} className={cx('flex items-center gap-2.5 rounded-xl py-1.5 pl-3 pr-1.5', m === 'correct' ? 'border border-green-400 bg-green-50' : m === 'wrong' ? 'border border-red-400 bg-red-50' : 'border border-slate-200 bg-white')}>
                  <span className="w-[18px] text-ui font-extrabold text-slate-500">{i + 1}</span>
                  <span className="flex-1 text-ui font-medium"><RichLine text={byId.get(id)?.text ?? ''} /></span>
                  {!lock && (
                    <>
                      <button type="button" aria-label={`Đưa "${byId.get(id)?.text ?? ''}" lên`} disabled={i === 0} onClick={() => mv(i, -1)} className="flex h-8 w-8 items-center justify-center rounded-lg bg-slate-100 text-slate-700 disabled:opacity-40"><ArrowUp className="h-4 w-4" aria-hidden="true" /></button>
                      <button type="button" aria-label={`Đưa "${byId.get(id)?.text ?? ''}" xuống`} disabled={i === order.length - 1} onClick={() => mv(i, 1)} className="flex h-8 w-8 items-center justify-center rounded-lg bg-slate-100 text-slate-700 disabled:opacity-40"><ArrowDown className="h-4 w-4" aria-hidden="true" /></button>
                    </>
                  )}
                </li>
              );
            })}
          </ol>
          {!v.order && !lock && (
            <div className="flex flex-wrap items-center gap-2 text-caption text-slate-500">
              <span>Kéo thả hoặc dùng mũi tên để đổi vị trí. Thứ tự hiện tại chưa được tính là câu trả lời.</span>
              <button type="button" onClick={() => set({ order })} className="font-semibold text-blue-700 underline underline-offset-2">Giữ thứ tự này</button>
            </div>
          )}
        </div>
      );
    }
    case 'Q07': {
      const bl = v.blanks ?? {};
      const blanks = x.blanks ?? [];
      return (
        <Inline text={item.prompt} render={(i) => {
          const b = blanks[i];
          if (!b) return <span className="text-slate-500">___</span>;
          const m = subMark(b.id, null);
          return (
            <span>
              <input aria-label={`Chỗ trống ${i + 1}`} disabled={lock} value={bl[b.id] ?? ''} onChange={(e) => { const next = { ...bl, [b.id]: e.target.value }; set({ blanks: next }); }}
                className={cx(inlineBase, 'w-[130px]', inlineMark(m))} />
              {result && m === 'wrong' && correct.blanks?.[b.id] && <span className="text-meta font-semibold text-green-700">({correct.blanks[b.id]})</span>}
            </span>
          );
        }} />
      );
    }
    case 'Q08': {
      const bl = v.blanks ?? {};
      const blanks = x.blanks ?? [];
      const used = Object.values(bl);
      const available=(word:string)=>((x.bank??[]).filter(w=>w===word).length-used.filter(w=>w===word).length)>0;
      const next = blanks.findIndex((b) => !bl[b.id]);
      return (
        <div className="flex flex-col gap-3">
          <Inline text={item.prompt} render={(i) => {
            const b = blanks[i];
            if (!b) return <span className="text-slate-500">___</span>;
            const m = subMark(b.id, null);
            return (
              <span>
                <button type="button" aria-label={bl[b.id] ? `Chỗ trống ${i + 1}: ${bl[b.id]} — bấm để bỏ` : `Chỗ trống ${i + 1}: trống`} disabled={lock}
                  onClick={() => { const n = { ...bl }; delete n[b.id]; set(Object.keys(n).length ? { blanks: n } : null); }}
                  onDragOver={e=>{if(!lock)e.preventDefault();}} onDrop={e=>{e.preventDefault();const word=e.dataTransfer.getData('text/plain');if(!lock && available(word))set({blanks:{...bl,[b.id]:word}});}}
                  className={cx(inlineBase, 'min-w-[90px]', inlineMark(m), bl[b.id] ? 'text-slate-900' : 'text-slate-500')}>{bl[b.id] || '…'}</button>
                {result && m === 'wrong' && correct.blanks?.[b.id] && <span className="text-meta font-semibold text-green-700">({correct.blanks[b.id]})</span>}
              </span>
            );
          }} />
          {!lock && (
            <div role="group" aria-label="Ngân hàng từ" className="flex flex-wrap gap-2 rounded-xl bg-slate-100 p-2.5">
              {(x.bank ?? []).map((w,index) => {const exhausted=(x.bank??[]).slice(0,index+1).filter(word=>word===w).length<=used.filter(word=>word===w).length;return (
                <button key={index} type="button" draggable={!exhausted && next>=0} onDragStart={e=>e.dataTransfer.setData('text/plain',w)} disabled={exhausted || next < 0} onClick={() => set({ blanks: { ...bl, [blanks[next].id]: w } })}
                  className={cx('h-[34px] rounded-full border border-slate-200 bg-white px-3.5 text-ui font-semibold', exhausted && 'opacity-35')}>{w}</button>
              );})}
            </div>
          )}
        </div>
      );
    }
    case 'Q09': {
      const ch = v.choices ?? {};
      const dds = x.dropdowns ?? [];
      return (
        <Inline text={item.prompt} render={(i) => {
          const d = dds[i];
          if (!d) return <span className="text-slate-500">___</span>;
          const m = subMark(d.id, result && correct.choices ? ch[d.id] === correct.choices[d.id] : null);
          return (
            <select aria-label={`Ô chọn ${i + 1}`} disabled={lock} value={ch[d.id] ?? ''}
              onChange={(e) => { const n = { ...ch }; if (e.target.value) n[d.id] = e.target.value; else delete n[d.id]; set(Object.keys(n).length ? { choices: n } : null); }}
              className={cx(inlineBase, inlineMark(m))}>
              <option value="">▾ chọn</option>
              {d.options.map((o) => <option key={o.id} value={o.id}>{o.text}</option>)}
            </select>
          );
        }} />
      );
    }
    case 'Q10': case 'Q11': {
      const m: Mark = result ? (result.status === 'CORRECT' ? 'correct' : 'wrong') : null;
      return (
        <div className="flex flex-col gap-1.5">
          <div className="flex max-w-[480px] items-center gap-2">
            <input aria-label={`Câu trả lời ${item.label}`} disabled={lock} inputMode={item.type === 'Q11' ? 'decimal' : undefined}
              maxLength={item.type === 'Q10' ? x.maxLength ?? 500 : 60} value={v.text ?? ''} onChange={(e) => set(e.target.value ? { text: e.target.value } : null)}
              placeholder={item.type === 'Q11' ? '0' : 'Nhập câu trả lời…'}
              className={cx('h-10 min-w-0 flex-1 rounded-xl border px-3 text-ui outline-none focus:ring-2 focus:ring-blue-600/20', item.type === 'Q11' && 'font-mono',
                m === 'correct' ? 'border-green-400 bg-green-50' : m === 'wrong' ? 'border-red-400 bg-red-50' : 'border-slate-200 bg-white')} />
            {item.type === 'Q11' && x.unit && <span className="text-ui font-semibold text-slate-600">{x.unit}</span>}
          </div>
          {result && m === 'wrong' && correct.text && <span className="text-meta font-medium text-green-700">Đáp án: {correct.text}</span>}
        </div>
      );
    }
    case 'Q12': {
      const w = wordCount(v.text);
      const lo = x.minWords ?? 0;
      const hi = x.maxWords ?? 0;
      const out = w > 0 && ((lo && w < lo) || (hi && w > hi));
      return (
        <div className="flex flex-col gap-1.5">
          <textarea aria-label={`Bài viết ${item.label}`} disabled={lock} value={v.text ?? ''} onChange={(e) => set(e.target.value ? { text: e.target.value } : null)} rows={7}
            placeholder="Viết câu trả lời của bạn…" maxLength={20000}
            className={cx('resize-y rounded-[14px] border border-slate-200 p-3.5 text-body-sm leading-6 outline-none focus:ring-2 focus:ring-blue-600/20', lock ? 'bg-slate-50' : 'bg-white')} />
          <div className={cx('flex justify-between text-caption font-medium', out ? 'text-amber-700' : 'text-slate-500')}>
            <span>{lo || hi ? `Yêu cầu ${lo || 0}–${hi || '…'} từ` : ''}</span>
            <span className="tabular-nums">{w} từ</span>
          </div>
        </div>
      );
    }
    case 'Q22': {
      const policy = x.recording ?? { prepSeconds: 0, maxSeconds: 60, maxRetakes: 2, allowFileFallback: true, maxFileMb: 10 };
      const rec = recordings?.[item.id];
      const val = v.recordingAssetId ? { assetId: v.recordingAssetId, durationMs: v.durationMs ?? rec?.durationMs ?? 0 } : rec ? { assetId: rec.assetId, durationMs: rec.durationMs } : null;
      return (
        <Recorder
          policy={policy}
          value={val}
          takesUsed={rec?.takesUsed ?? recordingTakes?.[item.id] ?? 0}
          preview={preview || lock}
          onUpload={onUploadRecording ? async (file, ms, name) => {
            const info = await onUploadRecording(item.id, file, ms, name);
            onChange({ recordingAssetId: info.assetId, durationMs: info.durationMs });
            return info;
          } : undefined}
          onDelete={onDeleteRecording ? async () => { await onDeleteRecording(item.id); onChange(null); } : undefined}
          onRequestUrl={onRecordingUrl ? () => onRecordingUrl(item.id) : undefined}
        />
      );
    }
    default:
      return <AdvancedInput item={item} value={value} onChange={onChange} locked={lock} preview={preview} {...evidenceHooks}/>;
  }
};
