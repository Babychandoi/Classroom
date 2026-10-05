import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { GraduationCap, Lock } from 'lucide-react';
import type { Classroom } from '../types';
import { accessPriceLabel, formatDate } from '../api/format';
import { Avatar, Badge, ClassAvatar, CoverImage, LiveDot, buttonClass } from './ui';

// CMP-1 Community Card: cover 16:9 -> class avatar overlapping the cover -> title -> one-line value -> owner ->
// meta -> footer (access badge left, one secondary CTA right). The CTA's ::after covers the card, so the whole
// card is one link without nesting interactive elements (a second link inside, e.g. "Studio", sits above it with z-10).
// Shared by the home page (default) and "Lớp học của tôi" (`personal`: role + state chips, Studio link).

/** The caller already belongs to the class (owner, staff or an active member). */
export const isMine = (cls: Classroom) =>
  !!cls.isOwner || !!cls.isMember || cls.userRole === 'OWNER' || cls.userRole === 'STAFF';

/** The caller leads the class (owner or staff) - the people who get a Studio. */
export const isManager = (cls: Classroom) => !!cls.isOwner || cls.userRole === 'OWNER' || cls.userRole === 'STAFF';

export const roleLabel = (cls: Classroom) =>
  cls.isOwner || cls.userRole === 'OWNER' ? 'Chủ lớp' : cls.userRole === 'STAFF' ? 'Trợ giảng' : 'Thành viên';

/** Class fields of the "Tạo lớp học" round (docs/API-CREATE-CLASS.md); absent on older payloads. */
export type CardClass = Classroom & {
  category?: string | null;
  avatarUrl?: string | null;
  coverPosition?: string | null;
  avatarPosition?: string | null;
};

/** Accept only the server's "x% y%" object-position format; anything else falls back to the centre. */
const objectPosition = (position?: string | null) => (position && /^\d{1,3}% \d{1,3}%$/.test(position) ? position : undefined);

/** The uploaded square class avatar (positioned like on the class page), else the initial tile. */
const CardAvatar: React.FC<{ cls: CardClass }> = ({ cls }) => {
  const [failed, setFailed] = useState(false);
  if (cls.avatarUrl && !failed) {
    return (
      <img
        src={cls.avatarUrl}
        alt=""
        onError={() => setFailed(true)}
        style={{ objectPosition: objectPosition(cls.avatarPosition) }}
        className="relative -mt-6 h-12 w-12 flex-shrink-0 rounded-[14px] border-[3px] border-white bg-white object-cover"
      />
    );
  }
  return <ClassAvatar title={cls.title} seed={cls.id} size={48} bordered className="relative -mt-6" />;
};

export const ClassCard: React.FC<{ cls: CardClass; className?: string; personal?: boolean }> = ({ cls, className = '', personal = false }) => {
  const mine = isMine(cls);
  const cover = cls.coverUrl ?? cls.coverImageUrl ?? null;
  const [coverFailed, setCoverFailed] = useState(false);
  const product = cls.accessProduct;
  const expired = cls.memberState === 'EXPIRED';
  const pending = cls.memberState === 'PENDING';
  const manager = isManager(cls);
  const feeBadge =
    cls.accessType === 'PAID' ? (
      <Badge tone="paid" className="tabular min-w-0 truncate">
        {product ? `Trả phí · ${accessPriceLabel(product.price, product.durationDays, product.lifetime)}` : 'Trả phí'}
      </Badge>
    ) : (
      <Badge tone="free">Miễn phí</Badge>
    );
  const cta = personal ? (expired ? 'Gia hạn' : pending ? 'Xem lớp' : 'Vào lớp') : mine ? 'Vào lớp' : 'Xem lớp';
  return (
    <article
      className={`card-hover relative flex flex-col overflow-hidden rounded-card border border-slate-200 bg-white shadow-hairline ${className}`}
    >
      <div className={`relative bg-slate-100 ${personal ? 'h-[128px] sm:h-[160px]' : 'h-[128px] sm:h-[200px]'}`}>
        {cover && !coverFailed ? (
          <img
            src={cover}
            alt={`Ảnh bìa lớp ${cls.title}`}
            onError={() => setCoverFailed(true)}
            style={{ objectPosition: objectPosition(cls.coverPosition) }}
            className="h-full w-full object-cover"
          />
        ) : (
          <CoverImage seed={cls.id} icon={<GraduationCap className="h-10 w-10 opacity-80" strokeWidth={1.5} />} />
        )}
        {/* D-19: "Riêng tư" is only ever on a class the viewer can see (a PRIVATE class they cannot see is not sent at all). */}
        {cls.visibility === 'PRIVATE' && (
          <span className="absolute left-3 top-3 inline-flex h-[26px] items-center gap-1 rounded-full bg-slate-900/80 px-2.5 text-caption font-semibold text-white">
            <Lock className="h-3 w-3" strokeWidth={2} aria-hidden="true" />
            Riêng tư
          </span>
        )}
      </div>
      <div className="flex flex-1 flex-col px-4 pb-4 sm:px-5 sm:pb-5">
        <CardAvatar cls={cls} />
        <h3 className="mt-3 truncate text-[17px] font-semibold leading-6 text-slate-900 sm:text-h2-sm">{cls.title}</h3>
        {personal && (
          <div className="mt-2 flex flex-wrap items-center gap-1.5" data-testid="card-chips">
            {!pending && <Badge tone={manager ? 'member' : 'neutral'}>{roleLabel(cls)}</Badge>}
            {expired && <Badge tone="warn">Hết hạn</Badge>}
            {pending && <Badge tone="info">Chờ duyệt</Badge>}
            {feeBadge}
          </div>
        )}
        <p className="mt-1.5 line-clamp-2 text-ui leading-[22px] text-slate-600">
          {cls.description || 'Chưa có mô tả lớp học.'}
        </p>
        {!personal && cls.ownerName && (
          <div className="mt-3 flex min-w-0 items-center gap-2">
            <Avatar name={cls.ownerName} src={cls.ownerAvatarUrl} size={22} />
            <span className="truncate text-meta text-slate-600">
              Dẫn dắt bởi <strong className="font-semibold text-slate-900">{cls.ownerName}</strong>
            </span>
          </div>
        )}
        <div className="mt-2 flex flex-wrap items-center gap-x-1.5 text-meta text-slate-600">
          {cls.category && (
            <>
              <span className="font-medium text-slate-900" data-testid="card-category">{cls.category}</span>
              <span aria-hidden="true">·</span>
            </>
          )}
          <span className="tabular">{(cls.memberCount ?? 0).toLocaleString('vi-VN')} thành viên</span>
          {(cls.upcomingEventCount ?? 0) > 0 && (
            <>
              <span aria-hidden="true">·</span>
              <LiveDot><span className="tabular">{cls.upcomingEventCount} sự kiện sắp tới</span></LiveDot>
            </>
          )}
        </div>
        {personal && expired && (
          <p className="mt-2 text-meta text-amber-800">
            {cls.accessExpiresAt ? `Quyền truy cập hết hạn ngày ${formatDate(cls.accessExpiresAt)}. ` : 'Quyền truy cập đã hết hạn. '}
            Gia hạn để học tiếp.
          </p>
        )}
        {personal && pending && (
          <p className="mt-2 text-meta text-slate-600">Yêu cầu tham gia của bạn đang chờ chủ lớp duyệt.</p>
        )}
        <div className="mt-auto flex items-center justify-between gap-3 pt-4">
          {personal ? (
            manager ? (
              <Link
                to={`/studio/classes/${cls.id}`}
                aria-label={`Studio của ${cls.title}`}
                className={buttonClass('secondary', 'md', 'relative z-10 flex-shrink-0')}
              >
                Studio
              </Link>
            ) : (
              <span />
            )
          ) : (
            feeBadge
          )}
          <Link
            to={`/classes/${cls.slug}/feed`}
            className={buttonClass('secondary', 'md', "flex-shrink-0 after:absolute after:inset-0 after:content-['']")}
          >
            {cta}
          </Link>
        </div>
      </div>
    </article>
  );
};
