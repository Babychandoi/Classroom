import React, { useEffect, useId, useRef } from 'react';

const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'textarea:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

const SIZE_CLASS = { sm: 'max-w-sm', md: 'max-w-md', lg: 'max-w-lg', xl: 'max-w-3xl' } as const;

// Only the topmost open modal reacts to Escape (a confirmation opened over a form must not also close the form).
const openModals: symbol[] = [];

interface ModalProps {
  children: React.ReactNode;
  /** Escape closes the dialog when provided (the dialog's own "Hủy" button is still the primary way out). */
  onClose?: () => void;
  /** Visible heading; also names the dialog. Omit it and pass `ariaLabel` when the caller renders its own header. */
  title?: string;
  ariaLabel?: string;
  size?: keyof typeof SIZE_CLASS;
  /** `alertdialog` for confirmations that interrupt the user. */
  role?: 'dialog' | 'alertdialog';
}

/**
 * R18-01: the one overlay/dialog shell for every modal in the app. The overlay itself scrolls
 * (`overflow-y-auto`) and the panel is capped at 90% of the viewport height and scrolls internally, so a
 * form taller than the window (e.g. the staff permission editor, 1400+ px with a course or two) can still be
 * reached and submitted with a mouse - the previous centred flex overlay clipped it top and bottom with no way
 * to scroll. Also gives every dialog role/aria-modal, an accessible name, Escape-to-close, focus moved into the
 * dialog on open, Tab kept inside it, and focus restored to the opener on close.
 *
 * Put action buttons in a footer that sticks to the bottom of the panel so they stay visible while the body scrolls:
 * `<div className="sticky bottom-0 -mx-6 -mb-6 border-t border-slate-100 bg-white px-6 py-4">...</div>`.
 */
export const Modal: React.FC<ModalProps> = ({ children, onClose, title, ariaLabel, size = 'lg', role = 'dialog' }) => {
  const titleId = useId();
  const panelRef = useRef<HTMLDivElement>(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    const token = Symbol('modal');
    openModals.push(token);
    const opener = document.activeElement as HTMLElement | null;
    const panel = panelRef.current;
    if (panel && !panel.contains(document.activeElement)) {
      const first = panel.querySelector<HTMLElement>(FOCUSABLE_SELECTOR);
      (first ?? panel).focus({ preventScroll: true });
    }

    const onKeyDown = (event: KeyboardEvent) => {
      if (openModals[openModals.length - 1] !== token) return;
      if (event.key === 'Escape' && onCloseRef.current) {
        event.preventDefault();
        onCloseRef.current();
        return;
      }
      if (event.key !== 'Tab' || !panel) return;
      const focusable = Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR));
      if (focusable.length === 0) {
        event.preventDefault();
        panel.focus();
        return;
      }
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      const active = document.activeElement;
      if (event.shiftKey && (active === first || active === panel || !panel.contains(active))) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && (active === last || !panel.contains(active))) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', onKeyDown);

    return () => {
      document.removeEventListener('keydown', onKeyDown);
      const index = openModals.indexOf(token);
      if (index >= 0) openModals.splice(index, 1);
      if (opener && opener.isConnected && typeof opener.focus === 'function') opener.focus({ preventScroll: true });
    };
  }, []);

  return (
    <div className="fixed inset-0 z-50 overflow-y-auto overscroll-contain bg-slate-900/[0.48]" data-modal-overlay="">
      <div className="flex min-h-full items-center justify-center p-4">
        <div
          ref={panelRef}
          role={role}
          aria-modal="true"
          aria-labelledby={title ? titleId : undefined}
          aria-label={title ? undefined : ariaLabel}
          tabIndex={-1}
          className={`w-full ${SIZE_CLASS[size]} max-h-[90vh] overflow-y-auto rounded-modal bg-white p-6 sm:p-7 shadow-modal focus:outline-none`}
        >
          {title && (
            <h3 id={titleId} className="mb-4 text-h2-sm font-semibold text-slate-900">
              {title}
            </h3>
          )}
          {children}
        </div>
      </div>
    </div>
  );
};
