import React, { useEffect, useRef } from 'react';
import { useBlocker } from 'react-router-dom';
import { Check } from 'lucide-react';
import { Modal } from '../../components/Modal';
import { Button } from '../../components/ui';
import { ModalActions } from './studioUi';
import type { WizardStep } from './courseWizardModel';

// Presentation + leave-guard pieces of the step-by-step wizards in Studio (stepper card, form cards, labelled fields).
// Modelled on the product wizard of the other Connecty project, rebuilt from our Connecty tokens.

const cx = (...parts: Array<string | false | null | undefined>) => parts.filter(Boolean).join(' ');

export type StepState = { active: boolean; done: boolean; locked: boolean };

/** Stepper card: equal cells, number (or a check once done) + label + short description; the active cell is underlined. */
export const WizardStepper: React.FC<{
  steps: readonly WizardStep[];
  stateOf: (num: number) => StepState;
  onSelect: (num: number) => void;
  ariaLabel: string;
}> = ({ steps, stateOf, onSelect, ariaLabel }) => (
  <nav aria-label={ariaLabel} className="rounded-card border border-slate-200 bg-white px-2 py-3 shadow-hairline sm:px-6 sm:py-5">
    <ol className="grid grid-cols-4 gap-1 sm:gap-4">
      {steps.map((step) => {
        const { active, done, locked } = stateOf(step.num);
        return (
          <li key={step.num} className="min-w-0">
            <button
              type="button"
              disabled={locked}
              aria-current={active ? 'step' : undefined}
              title={locked ? 'Hoàn thành các bước trước để mở bước này.' : undefined}
              onClick={() => onSelect(step.num)}
              className={cx(
                'relative flex w-full min-w-0 flex-col items-center gap-1.5 rounded-btn px-1 py-1 text-center transition-colors duration-micro',
                'focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-600 focus-visible:ring-offset-2',
                'sm:flex-row sm:items-center sm:gap-3 sm:px-1.5 sm:text-left',
                locked ? 'cursor-not-allowed opacity-60' : 'hover:bg-slate-50',
              )}
            >
              <span
                aria-hidden="true"
                className={cx(
                  'inline-flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-full text-ui font-semibold tabular',
                  active ? 'bg-blue-600 text-white' : done ? 'bg-green-600 text-white' : 'bg-slate-100 text-slate-600',
                )}
              >
                {done && !active ? <Check className="h-4 w-4" strokeWidth={2.5} /> : step.num}
              </span>
              <span className="flex min-w-0 flex-col">
                <span className={cx('text-micro font-semibold leading-[14px] sm:text-meta sm:leading-[18px]', active ? 'text-slate-900' : 'text-slate-900')}>
                  <span className="sr-only">Bước {step.num}: </span>
                  {step.label}
                </span>
                <span className="mt-0.5 hidden truncate text-caption text-slate-500 sm:block">{step.desc}</span>
                {done && !active && <span className="sr-only">Đã hoàn thành</span>}
                {locked && <span className="sr-only">Chưa mở</span>}
              </span>
              {active && <span aria-hidden="true" className="absolute inset-x-1 -bottom-3 h-[3px] rounded-full bg-blue-600 sm:-bottom-5" />}
            </button>
          </li>
        );
      })}
    </ol>
  </nav>
);

/** A white content card of a step: 18px title, one-line subtitle, fields below. */
export const FormCard: React.FC<{
  title: React.ReactNode;
  subtitle?: React.ReactNode;
  headingRef?: React.Ref<HTMLHeadingElement>;
  children: React.ReactNode;
  className?: string;
  action?: React.ReactNode;
}> = ({ title, subtitle, headingRef, children, className, action }) => (
  <section className={cx('rounded-card border border-slate-200 bg-white p-5 shadow-hairline sm:p-7', className)}>
    <header className="mb-5 flex flex-wrap items-start justify-between gap-3">
      <div className="min-w-0">
        <h2 ref={headingRef} tabIndex={-1} className="scroll-mt-52 text-h3-lg font-semibold text-slate-900 focus:outline-none">{title}</h2>
        {subtitle && <p className="mt-0.5 text-meta text-slate-600">{subtitle}</p>}
      </div>
      {action}
    </header>
    <div className="space-y-5">{children}</div>
  </section>
);

/** Labelled field: label with a required star, control, then error (red) or hint (grey) and an optional counter. */
export const WizField: React.FC<{
  label: React.ReactNode;
  htmlFor: string;
  required?: boolean;
  hint?: React.ReactNode;
  hintId?: string;
  error?: React.ReactNode;
  counter?: string;
  children: React.ReactNode;
  className?: string;
}> = ({ label, htmlFor, required, hint, hintId, error, counter, children, className }) => (
  <div className={cx('space-y-1.5', className)}>
    <div className="flex items-baseline justify-between gap-3">
      <label htmlFor={htmlFor} className="block text-meta font-semibold text-slate-900">
        {label}
        {required && <span className="ml-0.5 text-red-600" aria-hidden="true">*</span>}
        {required && <span className="sr-only"> (bắt buộc)</span>}
      </label>
      {counter && <span className="text-caption text-slate-500 tabular" aria-hidden="true">{counter}</span>}
    </div>
    {children}
    {error ? (
      <p id={hintId} role="alert" className="text-caption text-red-600">{error}</p>
    ) : hint ? (
      <p id={hintId} className="text-caption text-slate-500">{hint}</p>
    ) : null}
  </div>
);

/** Sticky footer of the wizard: back on the left, secondary + primary actions on the right. */
export const WizardFooter: React.FC<{ left: React.ReactNode; right: React.ReactNode; status?: React.ReactNode; wide?: boolean }> = ({
  left, right, status, wide,
}) => (
  <footer className="sticky bottom-0 z-30 -mx-4 mt-6 border-t border-slate-200 bg-slate-50/95 px-4 py-3 backdrop-blur sm:-mx-8 sm:px-8">
    <div className={cx('mx-auto flex w-full flex-wrap items-center justify-between gap-x-3 gap-y-2', wide ? 'max-w-[1280px]' : 'max-w-[1080px]')}>
      <div className="flex items-center gap-3">{left}</div>
      <div className="flex min-w-0 flex-wrap items-center justify-end gap-2">
        {status && <span role="status" aria-live="polite" className="mr-1 text-caption text-slate-600">{status}</span>}
        {right}
      </div>
    </div>
  </footer>
);

/**
 * "Thoát mà chưa lưu?": blocks in-app navigation (links, Back/Forward, programmatic) away from the page while `dirty`, and
 * asks the browser to confirm closing / reloading the tab. Navigation that stays on the same path (the wizard's own `?step=`
 * and the /new -> /:id/edit move after the first save) is never blocked.
 */
export function useLeaveGuard(dirty: boolean) {
  const dirtyRef = useRef(dirty);
  dirtyRef.current = dirty;
  /** True only while an intentional navigation (after a save, "Xong") runs, so it is not intercepted. */
  const allowRef = useRef(false);
  const blocker = useBlocker(
    ({ currentLocation, nextLocation }) => dirtyRef.current && !allowRef.current && currentLocation.pathname !== nextLocation.pathname,
  );

  useEffect(() => {
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      if (!dirtyRef.current) return;
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', onBeforeUnload);
    return () => window.removeEventListener('beforeunload', onBeforeUnload);
  }, []);

  return {
    open: blocker.state === 'blocked',
    stay: () => { if (blocker.state === 'blocked') blocker.reset(); },
    leave: () => { if (blocker.state === 'blocked') blocker.proceed(); },
    /** Runs `navigateFn` (a navigate() call) without the confirmation. */
    withoutPrompt: (navigateFn: () => void) => {
      allowRef.current = true;
      try { navigateFn(); } finally { allowRef.current = false; }
    },
  };
}

export const LeaveConfirm: React.FC<{ open: boolean; onStay: () => void; onLeave: () => void }> = ({ open, onStay, onLeave }) => {
  if (!open) return null;
  return (
    <Modal size="md" role="alertdialog" title="Thoát mà chưa lưu?" onClose={onStay}>
      <p className="text-ui text-slate-600">
        Bạn có thay đổi chưa được lưu. Nếu thoát bây giờ, những gì vừa nhập (tên, mô tả, giá...) sẽ bị mất. Bạn có chắc muốn thoát?
      </p>
      <ModalActions className="mt-5">
        <Button variant="secondary" onClick={onStay}>Ở lại</Button>
        <Button variant="danger" onClick={onLeave}>Thoát, bỏ thay đổi</Button>
      </ModalActions>
    </Modal>
  );
};
