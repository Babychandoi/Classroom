import React from 'react';
import { Coins, Gift, Globe, Lock } from 'lucide-react';
import type { Classroom, ClassVisibility } from '../types';
import { accessPriceLabel } from '../api/format';

/**
 * `default`: the design's pills (free = green, paid = violet, private = dark, public = neutral).
 * `glass`: white text on the translucent chip used on top of a cover image (masthead).
 */
export type ClassBadgeVariant = 'default' | 'glass';

const BASE = 'inline-flex flex-shrink-0 items-center gap-1 whitespace-nowrap rounded-full h-[26px] px-2.5 text-caption font-semibold tabular';
const GLASS = 'chip-glass text-white';

/** "Riêng tư" (always shown when PRIVATE) / "Công khai" (only when `showPublic`, e.g. in Studio where both states matter). */
export const VisibilityBadge: React.FC<{ visibility?: ClassVisibility; showPublic?: boolean; variant?: ClassBadgeVariant }> = ({
  visibility, showPublic = false, variant = 'default',
}) => {
  if (visibility === 'PRIVATE') {
    return (
      <span data-testid="badge-private" className={`${BASE} ${variant === 'glass' ? GLASS : 'bg-slate-900 text-white'}`}>
        <Lock className="h-3 w-3" strokeWidth={2} aria-hidden="true" />
        Riêng tư
      </span>
    );
  }
  if (!showPublic) return null;
  return (
    <span data-testid="badge-public" className={`${BASE} ${variant === 'glass' ? GLASS : 'bg-slate-100 text-slate-600'}`}>
      <Globe className="h-3 w-3" strokeWidth={2} aria-hidden="true" />
      Công khai
    </span>
  );
};

/** "Miễn phí" or "Trả phí · 199.000đ / 30 ngày" ("trọn đời" when the access never expires). */
export const AccessBadge: React.FC<{ classroom: Pick<Classroom, 'accessType' | 'accessProduct'>; variant?: ClassBadgeVariant }> = ({
  classroom, variant = 'default',
}) => {
  if (classroom.accessType === 'PAID') {
    const product = classroom.accessProduct;
    return (
      <span data-testid="badge-paid" className={`${BASE} ${variant === 'glass' ? GLASS : 'bg-violet-100 text-violet-800'}`}>
        <Coins className="h-3 w-3" strokeWidth={2} aria-hidden="true" />
        {product ? `Trả phí · ${accessPriceLabel(product.price, product.durationDays, product.lifetime)}` : 'Trả phí'}
      </span>
    );
  }
  return (
    <span data-testid="badge-free" className={`${BASE} ${variant === 'glass' ? GLASS : 'bg-green-100 text-green-800'}`}>
      <Gift className="h-3 w-3" strokeWidth={2} aria-hidden="true" />
      Miễn phí
    </span>
  );
};

/** Visibility + access badges of a class (D-19), side by side. */
export const ClassBadges: React.FC<{
  classroom: Pick<Classroom, 'visibility' | 'accessType' | 'accessProduct'>;
  showPublic?: boolean;
  className?: string;
  variant?: ClassBadgeVariant;
}> = ({ classroom, showPublic = false, className = '', variant = 'default' }) => (
  <div className={`flex flex-wrap items-center gap-1.5 ${className}`}>
    <VisibilityBadge visibility={classroom.visibility} showPublic={showPublic} variant={variant} />
    <AccessBadge classroom={classroom} variant={variant} />
  </div>
);
