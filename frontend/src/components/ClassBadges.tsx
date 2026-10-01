import React from 'react';
import { Coins, Gift, Globe, Lock } from 'lucide-react';
import type { Classroom, ClassVisibility } from '../types';
import { accessPriceLabel } from '../api/format';

const BASE = 'inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-bold whitespace-nowrap';

/** "Riêng tư" (always shown when PRIVATE) / "Công khai" (only when `showPublic`, e.g. in Studio where both states matter). */
export const VisibilityBadge: React.FC<{ visibility?: ClassVisibility; showPublic?: boolean }> = ({ visibility, showPublic = false }) => {
  if (visibility === 'PRIVATE') {
    return (
      <span data-testid="badge-private" className={`${BASE} bg-slate-900 text-white`}>
        <Lock className="w-3 h-3" aria-hidden="true" />
        Riêng tư
      </span>
    );
  }
  if (!showPublic) return null;
  return (
    <span data-testid="badge-public" className={`${BASE} bg-sky-100 text-sky-800`}>
      <Globe className="w-3 h-3" aria-hidden="true" />
      Công khai
    </span>
  );
};

/** "Miễn phí" or "Trả phí · 199.000đ / 30 ngày" ("trọn đời" when the access never expires). */
export const AccessBadge: React.FC<{ classroom: Pick<Classroom, 'accessType' | 'accessProduct'> }> = ({ classroom }) => {
  if (classroom.accessType === 'PAID') {
    const product = classroom.accessProduct;
    return (
      <span data-testid="badge-paid" className={`${BASE} bg-amber-100 text-amber-900`}>
        <Coins className="w-3 h-3" aria-hidden="true" />
        {product ? `Trả phí · ${accessPriceLabel(product.price, product.durationDays, product.lifetime)}` : 'Trả phí'}
      </span>
    );
  }
  return (
    <span data-testid="badge-free" className={`${BASE} bg-emerald-100 text-emerald-800`}>
      <Gift className="w-3 h-3" aria-hidden="true" />
      Miễn phí
    </span>
  );
};

/** Visibility + access badges of a class (D-19), side by side. */
export const ClassBadges: React.FC<{
  classroom: Pick<Classroom, 'visibility' | 'accessType' | 'accessProduct'>;
  showPublic?: boolean;
  className?: string;
}> = ({ classroom, showPublic = false, className = '' }) => (
  <div className={`flex flex-wrap items-center gap-1.5 ${className}`}>
    <VisibilityBadge visibility={classroom.visibility} showPublic={showPublic} />
    <AccessBadge classroom={classroom} />
  </div>
);
