import React, { useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { X } from 'lucide-react';

export function AssessmentDialog({title,onClose,children,footer,wide=false}:{title:string;onClose:()=>void;children:React.ReactNode;footer?:React.ReactNode;wide?:boolean}) {
  const ref=useRef<HTMLDivElement>(null);
  const closeRef=useRef(onClose);closeRef.current=onClose;
  useEffect(()=>{
    const old=document.activeElement as HTMLElement|null;const overflow=document.body.style.overflow;document.body.style.overflow='hidden';
    const root=document.getElementById('root');const wasInert=root?.inert??false;if(root)root.inert=true;
    ref.current?.focus();
    const key=(e:KeyboardEvent)=>{if(e.key==='Escape'){e.preventDefault();closeRef.current();}if(e.key==='Tab'){
      const focusable=Array.from(ref.current?.querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled),select:not(:disabled),textarea:not(:disabled),a[href],[tabindex="0"]')??[]);
      const first=focusable[0],last=focusable[focusable.length-1];if(e.shiftKey && (document.activeElement===first || document.activeElement===ref.current)){e.preventDefault();last?.focus();}
      else if(!e.shiftKey && document.activeElement===last){e.preventDefault();first?.focus();}
      if(!first){e.preventDefault();ref.current?.focus();}
    }};document.addEventListener('keydown',key);return()=>{document.removeEventListener('keydown',key);document.body.style.overflow=overflow;if(root)root.inert=wasInert;old?.focus();};
  },[]);
  return createPortal(<div className="fixed inset-0 z-[120] flex items-end justify-center bg-slate-900/45 p-0 sm:items-center sm:p-6" onMouseDown={e=>{if(e.target===e.currentTarget)onClose();}}>
    <div ref={ref} tabIndex={-1} role="dialog" aria-modal="true" aria-label={title} className={`flex max-h-[90dvh] w-full flex-col rounded-t-3xl bg-white shadow-xl sm:rounded-3xl ${wide?'sm:max-w-5xl':'sm:max-w-xl'}`}>
      <div className="flex items-center justify-between gap-4 border-b border-slate-100 p-5"><h2 className="text-heading-sm font-bold">{title}</h2><button aria-label="Đóng" onClick={onClose} className="flex h-10 w-10 items-center justify-center rounded-full bg-slate-100"><X size={18}/></button></div>
      <div className="overflow-y-auto p-5">{children}</div>{footer && <div className="flex flex-wrap justify-end gap-2 border-t border-slate-100 p-4">{footer}</div>}
    </div>
  </div>,document.body);
}
