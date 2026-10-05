import React from 'react';
import { buttonClass, Container } from './ui';
import { ErrorBanner } from './UIStates';

// Shell and "Xem thêm" footer shared by the "của tôi" pages (/me/classes, /me/courses, /me/events).

export const MePage: React.FC<{ title: string; description: string; children: React.ReactNode; actions?: React.ReactNode }> = ({
  title, description, children, actions,
}) => (
  <Container className="pb-16 pt-8 sm:pb-24 sm:pt-12">
    <div className="mb-6 flex flex-wrap items-end justify-between gap-4 sm:mb-8">
      <div className="min-w-0">
        <h1 className="text-[23px] font-semibold leading-[30px] tracking-[-0.4px] text-slate-900 sm:text-h1">{title}</h1>
        <p className="mt-1 text-ui text-slate-600 sm:text-body-sm">{description}</p>
      </div>
      {actions}
    </div>
    {children}
  </Container>
);

export const LoadMore: React.FC<{
  label: string;
  loading: boolean;
  error: string | null;
  onClick: () => void;
}> = ({ label, loading, error, onClick }) => (
  <div className="mt-8 flex flex-col items-center gap-3">
    {error && <ErrorBanner message={error} />}
    <button type="button" onClick={onClick} disabled={loading} className={buttonClass('secondary', 'md')}>
      {loading ? 'Đang tải...' : label}
    </button>
  </div>
);
