import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent, act, within } from '@testing-library/react';
import { StudioSettings } from '../pages/studio/StudioSettings';
import type { Classroom } from '../types';

/**
 * D-19: Studio "Hiển thị & tham gia" (public/private, CLASS:EDIT) and "Hình thức vào lớp" (free/paid + price + length; OWNER or
 * CLASS:EDIT + STORE:EDIT) - each change goes through an in-page confirmation that spells out the consequences.
 */

const owner: Classroom = {
  id: 'class-1',
  ownerId: 'owner-1',
  slug: 'demo-class',
  title: 'Lớp Demo',
  description: 'Mô tả gốc',
  coverImageUrl: 'https://img.test/cover.png',
  status: 'ACTIVE',
  memberCount: 3,
  userRole: 'OWNER',
  visibility: 'PUBLIC',
  accessType: 'FREE',
  accessProduct: null,
  createdAt: new Date().toISOString(),
} as Classroom;

let current: Classroom = owner;
const refreshClassroom = vi.fn(async () => {});

vi.mock('react-router-dom', () => ({
  useOutletContext: () => ({ classroom: current, refreshClassroom }),
}));

type Sent = { method: string; url: string; body: any };
let sent: Sent[];
let failWith: { status: number; code: string; message: string } | null;

function installFetch() {
  sent = [];
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = input.toString();
    const method = (init?.method || 'GET').toUpperCase();
    sent.push({ method, url, body: init?.body ? JSON.parse(String(init.body)) : undefined });
    if (method === 'PUT' && failWith) {
      return new Response(JSON.stringify({ success: false, error: { code: failWith.code, message: failWith.message } }), { status: failWith.status });
    }
    if (method === 'PUT') return new Response(JSON.stringify({ success: true, data: {} }), { status: 200 });
    return new Response('{}', { status: 404 });
  });
}

const puts = () => sent.filter((s) => s.method === 'PUT');
const visibilitySection = () => screen.getByRole('region', { name: /Hiển thị & tham gia/ });
const accessSection = () => screen.getByRole('region', { name: /Hình thức vào lớp/ });

beforeEach(() => {
  vi.restoreAllMocks();
  refreshClassroom.mockClear();
  current = owner;
  failWith = null;
  installFetch();
});

describe('StudioSettings - visibility (Hiển thị & tham gia)', () => {
  it('shows the current state with badges and the two choices with one-line explanations', () => {
    render(<StudioSettings />);
    const section = visibilitySection();
    expect(within(section).getByTestId('badge-public')).toBeInTheDocument();
    expect(within(section).getByTestId('badge-free')).toBeInTheDocument();
    expect(within(section).getByRole('radio', { name: /Công khai/ })).toBeChecked();
    expect(within(section).getByRole('radio', { name: /Riêng tư/ })).not.toBeChecked();
    expect(within(section).getByText(/Ẩn khỏi khám phá/)).toBeInTheDocument();
    // nothing to confirm until the choice differs
    expect(within(section).queryByRole('group', { name: 'Xác nhận đổi chế độ hiển thị' })).not.toBeInTheDocument();
  });

  it('PUBLIC -> PRIVATE: spells out the consequences, then PUTs the saved fields plus the new visibility', async () => {
    render(<StudioSettings />);
    const section = visibilitySection();

    fireEvent.click(within(section).getByRole('radio', { name: /Riêng tư/ }));
    const confirm = within(section).getByRole('group', { name: 'Xác nhận đổi chế độ hiển thị' });
    expect(confirm).toHaveTextContent('bị ẩn khỏi danh sách khám phá');
    expect(confirm).toHaveTextContent('Thành viên hiện tại giữ nguyên quyền');
    expect(puts()).toHaveLength(0); // not saved before the confirmation

    await act(async () => { fireEvent.click(within(confirm).getByRole('button', { name: 'Xác nhận đổi chế độ' })); });

    await waitFor(() => expect(puts()).toHaveLength(1));
    expect(puts()[0].url).toMatch(/\/classes\/class-1$/);
    // the SAVED title / description / cover go with it (PUT /classes/{id} replaces them) - nothing else changes
    expect(puts()[0].body).toEqual({ title: 'Lớp Demo', description: 'Mô tả gốc', coverImageUrl: 'https://img.test/cover.png', visibility: 'PRIVATE' });
    await waitFor(() => expect(refreshClassroom).toHaveBeenCalled());
    expect(await screen.findByText('Đã chuyển lớp sang chế độ Riêng tư.')).toBeInTheDocument();
  });

  it('does not send a half-edited title with the visibility change', async () => {
    render(<StudioSettings />);
    fireEvent.change(screen.getByLabelText('Tên lớp học'), { target: { value: 'Tên chưa lưu' } });
    const section = visibilitySection();
    fireEvent.click(within(section).getByRole('radio', { name: /Riêng tư/ }));
    await act(async () => { fireEvent.click(within(section).getByRole('button', { name: 'Xác nhận đổi chế độ' })); });
    await waitFor(() => expect(puts()).toHaveLength(1));
    expect(puts()[0].body.title).toBe('Lớp Demo');
  });

  it('PRIVATE -> PUBLIC: says it becomes visible and anyone can join', async () => {
    current = { ...owner, visibility: 'PRIVATE' } as Classroom;
    render(<StudioSettings />);
    const section = visibilitySection();
    expect(within(section).getByRole('radio', { name: /Riêng tư/ })).toBeChecked();
    expect(within(section).getByTestId('badge-private')).toBeInTheDocument();

    fireEvent.click(within(section).getByRole('radio', { name: /Công khai/ }));
    const confirm = within(section).getByRole('group', { name: 'Xác nhận đổi chế độ hiển thị' });
    expect(confirm).toHaveTextContent('hiện trong danh sách khám phá');
    expect(confirm).toHaveTextContent('ai cũng có thể tham gia');

    await act(async () => { fireEvent.click(within(confirm).getByRole('button', { name: 'Xác nhận đổi chế độ' })); });
    await waitFor(() => expect(puts()[0]?.body.visibility).toBe('PUBLIC'));
  });

  it('"Hủy" puts the radio back and sends nothing', () => {
    render(<StudioSettings />);
    const section = visibilitySection();
    fireEvent.click(within(section).getByRole('radio', { name: /Riêng tư/ }));
    fireEvent.click(within(section).getByRole('button', { name: 'Hủy' }));
    expect(within(section).getByRole('radio', { name: /Công khai/ })).toBeChecked();
    expect(within(section).queryByRole('group', { name: 'Xác nhận đổi chế độ hiển thị' })).not.toBeInTheDocument();
    expect(puts()).toHaveLength(0);
  });

  it('a server error is shown in the section and the confirmation stays open', async () => {
    failWith = { status: 403, code: 'FORBIDDEN', message: 'Không đủ quyền' };
    render(<StudioSettings />);
    const section = visibilitySection();
    fireEvent.click(within(section).getByRole('radio', { name: /Riêng tư/ }));
    await act(async () => { fireEvent.click(within(section).getByRole('button', { name: 'Xác nhận đổi chế độ' })); });

    expect(await within(section).findByText('Không đủ quyền')).toBeInTheDocument();
    expect(refreshClassroom).not.toHaveBeenCalled();
  });

  it('needs CLASS:EDIT: a staff member without it sees the state but cannot change it', () => {
    current = { ...owner, userRole: 'STAFF', studioPermissions: ['CLASS:VIEW'] } as Classroom;
    render(<StudioSettings />);
    const section = visibilitySection();
    expect(within(section).getByRole('radio', { name: /Công khai/ })).toBeDisabled();
    expect(within(section).getByRole('radio', { name: /Riêng tư/ })).toBeDisabled();
    expect(within(section).getByText(/Bạn cần quyền CLASS:EDIT/)).toBeInTheDocument();
  });

  it('a staff member with CLASS:EDIT can change the visibility', async () => {
    current = { ...owner, userRole: 'STAFF', studioPermissions: ['CLASS:EDIT'] } as Classroom;
    render(<StudioSettings />);
    const section = visibilitySection();
    const radio = within(section).getByRole('radio', { name: /Riêng tư/ });
    expect(radio).toBeEnabled();
    fireEvent.click(radio);
    expect(within(section).getByRole('button', { name: 'Xác nhận đổi chế độ' })).toBeInTheDocument();
  });
});

describe('StudioSettings - fee (Hình thức vào lớp)', () => {
  it('FREE -> PAID: validates the price, confirms that existing members keep free access, then PUTs /access', async () => {
    render(<StudioSettings />);
    const section = accessSection();
    expect(within(section).getByTestId('access-summary')).toHaveTextContent('Lớp đang miễn phí');
    expect(within(section).getByRole('button', { name: 'Lưu hình thức thu phí' })).toBeDisabled(); // unchanged

    fireEvent.click(within(section).getByRole('radio', { name: /Trả phí/ }));
    const save = within(section).getByRole('button', { name: 'Lưu hình thức thu phí' });

    // no price yet -> inline error, no confirmation, no request
    fireEvent.click(save);
    expect(await within(section).findByText('Giá vào lớp phải là số nguyên đồng lớn hơn 0.')).toBeInTheDocument();
    expect(within(section).queryByRole('group', { name: 'Xác nhận đổi hình thức thu phí' })).not.toBeInTheDocument();

    fireEvent.change(within(section).getByLabelText('Giá vào lớp (VND)'), { target: { value: '199000' } });
    fireEvent.change(within(section).getByLabelText('Thời hạn (ngày)'), { target: { value: '30' } });
    fireEvent.click(within(section).getByRole('button', { name: 'Lưu hình thức thu phí' }));

    const confirm = within(section).getByRole('group', { name: 'Xác nhận đổi hình thức thu phí' });
    expect(confirm).toHaveTextContent('199.000đ / 30 ngày');
    expect(confirm).toHaveTextContent('Thành viên hiện tại được giữ quyền truy cập miễn phí trọn đời');
    expect(puts()).toHaveLength(0);

    await act(async () => { fireEvent.click(within(confirm).getByRole('button', { name: 'Xác nhận thay đổi' })); });
    await waitFor(() => expect(puts()).toHaveLength(1));
    expect(puts()[0].url).toMatch(/\/classes\/class-1\/access$/);
    expect(puts()[0].body).toEqual({ accessType: 'PAID', price: 199000, currency: 'VND', durationDays: 30 });
    await waitFor(() => expect(refreshClassroom).toHaveBeenCalled());
    expect(await screen.findByText('Đã lưu hình thức thu phí của lớp.')).toBeInTheDocument();
  });

  it('"Trọn đời" disables the day count and sends durationDays null', async () => {
    render(<StudioSettings />);
    const section = accessSection();
    fireEvent.click(within(section).getByRole('radio', { name: /Trả phí/ }));
    fireEvent.change(within(section).getByLabelText('Giá vào lớp (VND)'), { target: { value: '500000' } });
    fireEvent.click(within(section).getByLabelText('Trọn đời (không hết hạn)'));
    expect(within(section).getByLabelText('Thời hạn (ngày)')).toBeDisabled();

    fireEvent.click(within(section).getByRole('button', { name: 'Lưu hình thức thu phí' }));
    expect(within(section).getByRole('group', { name: 'Xác nhận đổi hình thức thu phí' })).toHaveTextContent('500.000đ / trọn đời');
    await act(async () => { fireEvent.click(within(section).getByRole('button', { name: 'Xác nhận thay đổi' })); });
    await waitFor(() => expect(puts()).toHaveLength(1));
    expect(puts()[0].body).toEqual({ accessType: 'PAID', price: 500000, currency: 'VND', durationDays: null });
  });

  it.each([
    ['0', '30', 'Giá vào lớp phải là số nguyên đồng lớn hơn 0.'],
    ['-5', '30', 'Giá vào lớp phải là số nguyên đồng lớn hơn 0.'],
    ['1999.5', '30', 'Giá vào lớp phải là số nguyên đồng lớn hơn 0.'],
    ['199000', '0', 'Thời hạn phải từ 1 đến 3650 ngày, hoặc chọn "Trọn đời".'],
    ['199000', '4000', 'Thời hạn phải từ 1 đến 3650 ngày, hoặc chọn "Trọn đời".'],
  ])('rejects price %s / %s days before any request', async (price, days, message) => {
    render(<StudioSettings />);
    const section = accessSection();
    fireEvent.click(within(section).getByRole('radio', { name: /Trả phí/ }));
    fireEvent.change(within(section).getByLabelText('Giá vào lớp (VND)'), { target: { value: price } });
    fireEvent.change(within(section).getByLabelText('Thời hạn (ngày)'), { target: { value: days } });
    fireEvent.click(within(section).getByRole('button', { name: 'Lưu hình thức thu phí' }));

    expect(await within(section).findByText(message)).toBeInTheDocument();
    expect(puts()).toHaveLength(0);
  });

  it('PAID -> FREE: shows the current product, warns that the old package is archived and PUTs only accessType', async () => {
    current = {
      ...owner,
      accessType: 'PAID',
      accessProduct: { id: 'prod-1', price: 199000, currency: 'VND', durationDays: 30, lifetime: false },
    } as Classroom;
    render(<StudioSettings />);
    const section = accessSection();
    expect(within(section).getByTestId('access-summary')).toHaveTextContent('199.000đ / 30 ngày');
    expect(within(section).getByRole('radio', { name: /Trả phí/ })).toBeChecked();
    expect(within(section).getByLabelText('Giá vào lớp (VND)')).toHaveValue(199000);

    fireEvent.click(within(section).getByRole('radio', { name: /Miễn phí/ }));
    fireEvent.click(within(section).getByRole('button', { name: 'Lưu hình thức thu phí' }));
    const confirm = within(section).getByRole('group', { name: 'Xác nhận đổi hình thức thu phí' });
    expect(confirm).toHaveTextContent('Mọi người có thể vào lớp tự do');
    expect(confirm).toHaveTextContent('được lưu trữ');

    await act(async () => { fireEvent.click(within(confirm).getByRole('button', { name: 'Xác nhận thay đổi' })); });
    await waitFor(() => expect(puts()).toHaveLength(1));
    expect(puts()[0].body).toEqual({ accessType: 'FREE' });
  });

  it('PAID -> PAID (new price): says the new price only applies to new orders', async () => {
    current = {
      ...owner,
      accessType: 'PAID',
      accessProduct: { id: 'prod-1', price: 199000, currency: 'VND', durationDays: 30, lifetime: false },
    } as Classroom;
    render(<StudioSettings />);
    const section = accessSection();
    fireEvent.change(within(section).getByLabelText('Giá vào lớp (VND)'), { target: { value: '249000' } });
    fireEvent.click(within(section).getByRole('button', { name: 'Lưu hình thức thu phí' }));
    expect(within(section).getByRole('group', { name: 'Xác nhận đổi hình thức thu phí' })).toHaveTextContent('chỉ áp dụng cho đơn mua mới');
  });

  it('"Hủy" on the confirmation sends nothing', () => {
    render(<StudioSettings />);
    const section = accessSection();
    fireEvent.click(within(section).getByRole('radio', { name: /Trả phí/ }));
    fireEvent.change(within(section).getByLabelText('Giá vào lớp (VND)'), { target: { value: '100000' } });
    fireEvent.click(within(section).getByRole('button', { name: 'Lưu hình thức thu phí' }));
    fireEvent.click(within(within(section).getByRole('group', { name: 'Xác nhận đổi hình thức thu phí' })).getByRole('button', { name: 'Hủy' }));
    expect(within(section).queryByRole('group', { name: 'Xác nhận đổi hình thức thu phí' })).not.toBeInTheDocument();
    expect(puts()).toHaveLength(0);
  });

  it('surfaces a server rejection (e.g. price rules) and does not claim success', async () => {
    failWith = { status: 400, code: 'BAD_REQUEST', message: 'Giá VND phải là số nguyên' };
    render(<StudioSettings />);
    const section = accessSection();
    fireEvent.click(within(section).getByRole('radio', { name: /Trả phí/ }));
    fireEvent.change(within(section).getByLabelText('Giá vào lớp (VND)'), { target: { value: '100000' } });
    fireEvent.click(within(section).getByRole('button', { name: 'Lưu hình thức thu phí' }));
    await act(async () => { fireEvent.click(within(section).getByRole('button', { name: 'Xác nhận thay đổi' })); });

    expect(await within(section).findByText('Giá VND phải là số nguyên')).toBeInTheDocument();
    expect(refreshClassroom).not.toHaveBeenCalled();
    expect(screen.queryByText('Đã lưu hình thức thu phí của lớp.')).not.toBeInTheDocument();
  });

  it('OWNER or CLASS:EDIT + STORE:EDIT may change the fee; CLASS:EDIT alone (or STORE:EDIT alone) may not', () => {
    current = { ...owner, userRole: 'STAFF', studioPermissions: ['CLASS:EDIT', 'STORE:EDIT'] } as Classroom;
    const both = render(<StudioSettings />);
    expect(within(accessSection()).getByRole('radio', { name: /Trả phí/ })).toBeEnabled();
    expect(within(accessSection()).getByRole('button', { name: 'Lưu hình thức thu phí' })).toBeInTheDocument();
    both.unmount();

    current = { ...owner, userRole: 'STAFF', studioPermissions: ['CLASS:EDIT'] } as Classroom;
    const classOnly = render(<StudioSettings />);
    expect(within(accessSection()).getByRole('radio', { name: /Trả phí/ })).toBeDisabled();
    expect(within(accessSection()).queryByRole('button', { name: 'Lưu hình thức thu phí' })).not.toBeInTheDocument();
    expect(within(accessSection()).getByText(/CLASS:EDIT và STORE:EDIT/)).toBeInTheDocument();
    // ... while visibility stays editable for them
    expect(within(visibilitySection()).getByRole('radio', { name: /Riêng tư/ })).toBeEnabled();
    classOnly.unmount();

    current = { ...owner, userRole: 'STAFF', studioPermissions: ['STORE:EDIT'] } as Classroom;
    render(<StudioSettings />);
    expect(within(accessSection()).getByRole('radio', { name: /Trả phí/ })).toBeDisabled();
  });

  it('wildcard grants count: CLASS:* and STORE:* allow the change', () => {
    current = { ...owner, userRole: 'STAFF', studioPermissions: ['CLASS:*', '*:EDIT'] } as Classroom;
    render(<StudioSettings />);
    expect(within(accessSection()).getByRole('radio', { name: /Trả phí/ })).toBeEnabled();
  });
});
