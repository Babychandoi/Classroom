import React from 'react';
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ClassCard, isManager, roleLabel } from '../components/ClassCard';
import { baseClassroom } from './blogEventsHelpers';

const renderCard = (props: React.ComponentProps<typeof ClassCard>) => render(<MemoryRouter><ClassCard {...props} /></MemoryRouter>);

describe('ClassCard', () => {
  it('default (home) variant: owner line, fee badge, no role chips or Studio link', () => {
    renderCard({ cls: { ...baseClassroom, userRole: 'GUEST', isMember: false } });
    expect(screen.getByText('Chủ Lớp')).toBeInTheDocument();
    expect(screen.getByText('Miễn phí')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Xem lớp' })).toHaveAttribute('href', '/classes/demo-class/feed');
    expect(screen.queryByTestId('card-chips')).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Studio/ })).not.toBeInTheDocument();
  });

  it('personal variant: role chip, paid badge, private badge and Studio for the owner', () => {
    renderCard({ cls: { ...baseClassroom, isOwner: true, userRole: 'OWNER', visibility: 'PRIVATE', accessType: 'PAID' }, personal: true });
    expect(screen.getByText('Chủ lớp')).toBeInTheDocument();
    expect(screen.getByText('Trả phí')).toBeInTheDocument();
    expect(screen.getByText('Riêng tư')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Studio/ })).toHaveAttribute('href', '/studio/classes/class-1');
  });

  it('role helpers', () => {
    expect(roleLabel({ ...baseClassroom, userRole: 'STAFF' })).toBe('Trợ giảng');
    expect(roleLabel({ ...baseClassroom, userRole: 'MEMBER' })).toBe('Thành viên');
    expect(isManager({ ...baseClassroom, userRole: 'MEMBER' })).toBe(false);
  });
});
