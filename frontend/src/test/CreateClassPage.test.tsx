import React from 'react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, waitFor, within, act } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { CreateClassPage } from '../pages/CreateClassPage';
import { AppNavbar } from '../App';
import { FALLBACK_CATEGORIES } from '../components/create/classMedia';

/**
 * "Tạo lớp học" (/classes/new): validation hints and the disabled-looking CTA, category chips from the server (with
 * the contract's fallback list), paid price formatting, the live preview, the create call sequence (POST /classes
 * without slug -> PUT access when paid -> media uploads -> PUT with media ids and positions), honest partial failures
 * with retry, the "đã mở" dialog links, and the global top bar hidden on this route.
 */

const mockNavigate = vi.fn();
vi.mock('react-router-dom', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-router-dom')>()),
  useNavigate: () => mockNavigate,
}));

vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 'u1', email: 'minh@test.local', fullName: 'Nguyễn Minh', role: 'USER', status: 'ACTIVE' },
    isLoading: false,
    logout: vi.fn(),
    quickLogin: vi.fn(),
  }),
}));

const ok = (data: unknown) => new Response(JSON.stringify({ success: true, data }), { status: 200 });
const fail = (status: number, message: string) =>
  new Response(JSON.stringify({ success: false, error: { code: 'ERR', message } }), { status });

type Call = { method: string; path: string; body: any };

function install(over: { categories?: string[] | 'missing'; post?: () => Response; avatarIntent?: () => Response } = {}) {
  const calls: Call[] = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const raw = input.toString();
    const method = (init?.method || 'GET').toUpperCase();
    const url = new URL(raw, 'http://localhost');
    const path = url.origin === 'https://store.test' ? raw : url.pathname.replace('/api/v1', '');
    const body = typeof init?.body === 'string' ? JSON.parse(init.body) : init?.body ? '[file]' : undefined;
    calls.push({ method, path, body });
    if (path === '/auth/refresh') return fail(401, 'no');
    if (path === '/classes/categories') return over.categories === 'missing' ? fail(404, 'Not found') : ok(over.categories ?? FALLBACK_CATEGORIES);
    if (path === '/classes' && method === 'POST') return over.post ? over.post() : ok({ id: 'c1', slug: 'bep-com-nha', title: body.title, description: body.description });
    if (path === '/classes/c1/access') return ok({});
    if (path === '/classes/c1/media/upload-intents') {
      if (body.purpose === 'CLASS_AVATAR' && over.avatarIntent) return over.avatarIntent();
      const id = body.purpose === 'CLASS_COVER' ? 'm-cover' : 'm-avatar';
      return ok({ assetId: id, uploadUrl: `https://store.test/${id}` });
    }
    if (path.startsWith('https://store.test/')) return new Response(null, { status: 200 });
    if (path.startsWith('/media/') && path.endsWith('/complete')) return ok({});
    if (path === '/classes/c1' && method === 'PUT') return ok({});
    return fail(404, 'unexpected ' + path);
  });
  return calls;
}

const renderPage = () =>
  render(
    <MemoryRouter initialEntries={['/classes/new']}>
      <Routes>
        <Route path="/classes/new" element={<CreateClassPage />} />
      </Routes>
    </MemoryRouter>,
  );

const cta = () => screen.getByRole('button', { name: /^(Tạo lớp học|Đang tạo lớp học…)$/ });
const hint = () => screen.getByTestId('create-hint');
const image = (name: string, type = 'image/png', size = 1000) => {
  const file = new File(['x'.repeat(10)], name, { type });
  Object.defineProperty(file, 'size', { value: size });
  return file;
};
const pickFile = (testId: string, file: File) => fireEvent.change(screen.getByTestId(`${testId}-input`), { target: { files: [file] } });

const fillValid = async () => {
  fireEvent.change(screen.getByLabelText('Tên lớp học · bắt buộc'), { target: { value: 'Bếp cơm nhà' } });
  fireEvent.click(await screen.findByRole('button', { name: 'Ăn chay' }));
};

describe('CreateClassPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    mockNavigate.mockReset();
    let n = 0;
    URL.createObjectURL = vi.fn(() => `blob:preview-${++n}`);
    URL.revokeObjectURL = vi.fn();
  });
  afterEach(() => vi.restoreAllMocks());

  it('walks the hints in order and keeps the CTA inert until the form is valid', async () => {
    const calls = install();
    renderPage();
    expect(screen.getByRole('heading', { level: 1, name: 'Tạo lớp học' })).toBeInTheDocument();
    expect(hint()).toHaveTextContent('Nhập tên lớp học để tiếp tục.');
    expect(cta()).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(cta());
    expect(calls.some((c) => c.method === 'POST' && c.path === '/classes')).toBe(false);

    fireEvent.change(screen.getByLabelText('Tên lớp học · bắt buộc'), { target: { value: 'Bếp cơm nhà' } });
    expect(hint()).toHaveTextContent('Chọn một lĩnh vực để tiếp tục.');
    expect(screen.getAllByTestId('check')).toHaveLength(1);

    fireEvent.click(await screen.findByRole('button', { name: 'Ăn chay' }));
    expect(screen.getByRole('button', { name: 'Ăn chay' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getAllByTestId('check')).toHaveLength(2);
    expect(hint()).toHaveTextContent('Bằng việc tạo, bạn đồng ý với Điều khoản dành cho người dẫn dắt.');
    expect(cta()).toHaveAttribute('aria-disabled', 'false');

    // Paid needs a price; the input formats as you type and caps the description at 160.
    fireEvent.click(screen.getByRole('button', { name: /Có phí/ }));
    expect(hint()).toHaveTextContent('Nhập phí mỗi tháng để tiếp tục.');
    const price = screen.getByLabelText('Phí mỗi tháng · bắt buộc');
    fireEvent.change(price, { target: { value: '199000' } });
    expect(price).toHaveValue('199.000');
    expect(hint()).toHaveTextContent('Bằng việc tạo');
    fireEvent.change(screen.getByLabelText(/Mô tả ngắn/), { target: { value: 'a'.repeat(200) } });
    expect(screen.getByTestId('desc-count')).toHaveTextContent('160/160');
  });

  it('shows the server categories, or the contract list when the endpoint is missing', async () => {
    install({ categories: ['Yoga', 'Piano'] });
    const view = renderPage();
    expect(await screen.findByRole('button', { name: 'Piano' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Nấu ăn' })).not.toBeInTheDocument();
    view.unmount();

    vi.restoreAllMocks();
    install({ categories: 'missing' });
    renderPage();
    const group = screen.getByRole('group', { name: 'Lĩnh vực · bắt buộc' });
    await waitFor(() => expect(within(group).getAllByRole('button')).toHaveLength(12));
    expect(within(group).getByRole('button', { name: 'Phát triển bản thân' })).toBeInTheDocument();
  });

  it('the preview tab strip scrolls sideways and is reachable from the keyboard (desktop and phone preview)', () => {
    install();
    renderPage();
    const desktop = within(screen.getByTestId('preview-desktop')).getByRole('group', { name: 'Các khu vực của lớp (xem trước)' });
    expect(desktop).toHaveAttribute('tabindex', '0');
    fireEvent.click(screen.getByRole('button', { name: 'Xem trên điện thoại' }));
    const phone = screen.getByTestId('preview-tabs-mobile');
    expect(phone).toHaveAttribute('tabindex', '0');
    expect(phone).toHaveClass('overflow-x-auto');
    expect(phone).toHaveAccessibleName('Các khu vực của lớp (xem trước)');
  });

  it('updates the live preview as you type, with the signed-in owner and our class tabs', async () => {
    install();
    renderPage();
    const preview = screen.getByTestId('preview-desktop');
    expect(within(preview).getByTestId('preview-name')).toHaveTextContent('Tên lớp học');
    expect(within(preview).getByTestId('preview-tag')).toHaveTextContent('Công khai · Miễn phí');
    expect(preview).toHaveTextContent('Dẫn dắt bởi Nguyễn Minh');
    ['Blog', 'Thảo luận', 'Khóa học', 'Thi', 'Sự kiện', 'Tài liệu', 'Thành viên', 'Shop', 'Bảng xếp hạng', 'Giới thiệu'].forEach((tab) =>
      expect(within(preview).getAllByText(tab).length).toBeGreaterThan(0),
    );

    await fillValid();
    fireEvent.change(screen.getByLabelText(/Mô tả ngắn/), { target: { value: 'Cho người bận rộn.' } });
    expect(within(preview).getByTestId('preview-name')).toHaveTextContent('Bếp cơm nhà');
    expect(within(preview).getByTestId('preview-desc')).toHaveTextContent('Cho người bận rộn.');
    expect(within(preview).getByTestId('preview-category')).toHaveTextContent('Ăn chay');

    // Approval turns the join button into "Xin tham gia" - only for a public free class.
    fireEvent.click(screen.getByRole('switch', { name: 'Duyệt từng người trước khi vào' }));
    expect(within(preview).getByTestId('preview-join')).toHaveTextContent('Xin tham gia');
    fireEvent.click(screen.getByRole('button', { name: /Riêng tư/ }));
    expect(within(preview).getByTestId('preview-join')).toHaveTextContent('Tham gia');
    expect(screen.getByText('Chỉ áp dụng cho lớp công khai miễn phí.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Có phí/ }));
    fireEvent.change(screen.getByLabelText('Phí mỗi tháng · bắt buộc'), { target: { value: '199000' } });
    expect(within(preview).getByTestId('preview-tag')).toHaveTextContent('Riêng tư · 199.000đ/tháng');

    fireEvent.click(screen.getByRole('button', { name: 'Xem trên điện thoại' }));
    expect(screen.getByText('Xem trước trên điện thoại')).toBeInTheDocument();
    expect(screen.getByTestId('preview-mobile')).toHaveTextContent('Riêng tư · 199.000đ/tháng · 1 thành viên');
    expect(screen.queryByTestId('preview-desktop')).not.toBeInTheDocument();
  });

  it('accepts images up to 5 MB only, and "Căn ảnh" moves the picture (object-position)', async () => {
    install();
    renderPage();
    pickFile('slot-cover-desktop', image('doc.pdf', 'application/pdf'));
    expect(within(screen.getByTestId('slot-cover-desktop')).getByRole('alert')).toHaveTextContent('Chỉ nhận tệp ảnh');
    pickFile('slot-cover-desktop', image('big.png', 'image/png', 6 * 1024 * 1024));
    expect(within(screen.getByTestId('slot-cover-desktop')).getByRole('alert')).toHaveTextContent('Ảnh tối đa 5 MB.');

    pickFile('slot-cover-desktop', image('cover.png'));
    const slot = screen.getByTestId('slot-cover-desktop');
    const img = within(slot).getByAltText('Ảnh bìa');
    expect(img).toHaveAttribute('src', 'blob:preview-1');
    expect(within(slot).queryByRole('alert')).not.toBeInTheDocument();
    // The narrow-screen slot shows the same picture.
    expect(within(screen.getByTestId('slot-cover-narrow')).getByAltText('Ảnh bìa')).toHaveAttribute('src', 'blob:preview-1');

    fireEvent.click(within(slot).getByRole('button', { name: 'Căn ảnh - Ảnh bìa' }));
    fireEvent.keyDown(within(slot).getByLabelText(/Ảnh bìa: kéo hoặc dùng phím mũi tên/), { key: 'ArrowLeft' });
    expect(img.style.objectPosition).toBe('52% 50%');
    expect(within(slot).getByRole('button', { name: 'Xong - Ảnh bìa' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('creates a paid class with images: POST (no slug) -> PUT access -> uploads -> PUT media, then the done dialog', async () => {
    const calls = install();
    renderPage();
    await fillValid();
    fireEvent.change(screen.getByLabelText(/Mô tả ngắn/), { target: { value: '  Cho người bận rộn.  ' } });
    fireEvent.click(screen.getByRole('button', { name: /Có phí/ }));
    fireEvent.change(screen.getByLabelText('Phí mỗi tháng · bắt buộc'), { target: { value: '199000' } });
    pickFile('slot-cover-desktop', image('cover.png'));
    pickFile('slot-avatar-desktop', image('avatar.png'));
    const coverSlot = screen.getByTestId('slot-cover-desktop');
    fireEvent.click(within(coverSlot).getByRole('button', { name: 'Căn ảnh - Ảnh bìa' }));
    fireEvent.keyDown(within(coverSlot).getByLabelText(/kéo hoặc dùng phím mũi tên/), { key: 'ArrowDown', shiftKey: true });

    await act(async () => { fireEvent.click(cta()); });
    const dialog = await screen.findByRole('dialog', { name: 'Bếp cơm nhà đã mở' });

    const seq = calls.filter((c) => c.path !== '/classes/categories' && c.path !== '/auth/refresh').map((c) => `${c.method} ${c.path}`);
    expect(seq).toEqual([
      'POST /classes',
      'PUT /classes/c1/access',
      'POST /classes/c1/media/upload-intents',
      'PUT https://store.test/m-cover',
      'POST /media/m-cover/complete',
      'POST /classes/c1/media/upload-intents',
      'PUT https://store.test/m-avatar',
      'POST /media/m-avatar/complete',
      'PUT /classes/c1',
    ]);
    const body = (method: string, path: string) => calls.find((c) => c.method === method && c.path === path)!.body;
    expect(body('POST', '/classes')).toEqual({
      title: 'Bếp cơm nhà', description: 'Cho người bận rộn.', visibility: 'PUBLIC', category: 'Ăn chay',
      requireApproval: false, coverPosition: '50% 40%', avatarPosition: '50% 50%',
    });
    expect(body('POST', '/classes')).not.toHaveProperty('slug');
    expect(body('PUT', '/classes/c1/access')).toEqual({ accessType: 'PAID', price: 199000, currency: 'VND', durationDays: 30 });
    expect(calls.filter((c) => c.path.endsWith('upload-intents')).map((c) => c.body.purpose)).toEqual(['CLASS_COVER', 'CLASS_AVATAR']);
    expect(body('PUT', '/classes/c1')).toEqual({
      title: 'Bếp cơm nhà', description: 'Cho người bận rộn.',
      coverMediaId: 'm-cover', coverPosition: '50% 40%', avatarMediaId: 'm-avatar', avatarPosition: '50% 50%',
    });

    expect(dialog).toHaveTextContent('Lớp học có phí 199.000đ/tháng đã sẵn sàng');
    expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument();
    const link = (name: RegExp | string) => within(dialog).getByRole('link', { name });
    expect(link(/Đăng bài chào mừng trong Thảo luận/)).toHaveAttribute('href', '/classes/bep-com-nha/feed');
    expect(link(/Tạo khóa học đầu tiên trong Studio/)).toHaveAttribute('href', '/studio/classes/c1/courses');
    expect(link(/Mời 5 người bạn/)).toHaveAttribute('href', '/studio/classes/c1/members');
    expect(link('Vào lớp học')).toHaveAttribute('href', '/classes/bep-com-nha/feed');
    expect(link('Về trang chủ')).toHaveAttribute('href', '/classes');
  });

  it('a free class with approval sends requireApproval and skips the access call', async () => {
    const calls = install();
    renderPage();
    await fillValid();
    fireEvent.click(screen.getByRole('switch', { name: 'Duyệt từng người trước khi vào' }));
    await act(async () => { fireEvent.click(cta()); });
    const dialog = await screen.findByRole('dialog');
    expect(calls.find((c) => c.method === 'POST' && c.path === '/classes')!.body).toEqual({
      title: 'Bếp cơm nhà', description: '', visibility: 'PUBLIC', category: 'Ăn chay', requireApproval: true,
    });
    expect(calls.some((c) => c.path.includes('/access') || c.path.includes('upload-intents') || (c.method === 'PUT' && c.path === '/classes/c1'))).toBe(false);
    expect(dialog).toHaveTextContent('Lớp học miễn phí đã sẵn sàng');
  });

  it('keeps the created class when an image fails, says so, and "Thử lại" only redoes what failed', async () => {
    let avatarAttempts = 0;
    const calls = install({ avatarIntent: () => (++avatarAttempts === 1 ? fail(503, 'Kho lưu trữ tệp tạm thời không khả dụng.') : ok({ assetId: 'm-avatar', uploadUrl: 'https://store.test/m-avatar' })) });
    renderPage();
    await fillValid();
    pickFile('slot-cover-desktop', image('cover.png'));
    pickFile('slot-avatar-desktop', image('avatar.png'));
    await act(async () => { fireEvent.click(cta()); });

    const dialog = await screen.findByRole('dialog', { name: 'Bếp cơm nhà đã mở' });
    const alert = within(dialog).getByRole('alert');
    expect(alert).toHaveTextContent('Lớp học đã được tạo, nhưng còn việc chưa xong');
    expect(alert).toHaveTextContent('Chưa tải được ảnh đại diện (Kho lưu trữ tệp tạm thời không khả dụng.)');
    expect(within(alert).getByRole('link', { name: 'Làm tiếp trong Studio › Cài đặt' })).toHaveAttribute('href', '/studio/classes/c1/settings');
    // The cover that did upload was saved already.
    expect(calls.filter((c) => c.method === 'PUT' && c.path === '/classes/c1').map((c) => c.body)).toEqual([
      { title: 'Bếp cơm nhà', description: '', coverMediaId: 'm-cover', coverPosition: '50% 50%' },
    ]);

    await act(async () => { fireEvent.click(within(alert).getByRole('button', { name: 'Thử lại' })); });
    await waitFor(() => expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument());
    expect(calls.filter((c) => c.method === 'POST' && c.path === '/classes')).toHaveLength(1);
    expect(calls.filter((c) => c.path.endsWith('upload-intents')).map((c) => c.body.purpose)).toEqual(['CLASS_COVER', 'CLASS_AVATAR', 'CLASS_AVATAR']);
    const puts = calls.filter((c) => c.method === 'PUT' && c.path === '/classes/c1');
    expect(puts[puts.length - 1].body).toEqual({
      title: 'Bếp cơm nhà', description: '', avatarMediaId: 'm-avatar', avatarPosition: '50% 50%',
    });
  });

  it('shows a refused create inline and stays on the form', async () => {
    install({ post: () => fail(400, 'Đường dẫn (slug) là bắt buộc') });
    renderPage();
    await fillValid();
    await act(async () => { fireEvent.click(cta()); });
    expect(await screen.findByRole('alert')).toHaveTextContent('Đường dẫn (slug) là bắt buộc');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(cta()).toHaveTextContent('Tạo lớp học');
  });

  it('the close X goes home on a direct visit; a private class suggests an invite link', async () => {
    install();
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: 'Đóng' }));
    expect(mockNavigate).toHaveBeenCalledWith('/classes');

    await fillValid();
    fireEvent.click(screen.getByRole('button', { name: /Riêng tư/ }));
    await act(async () => { fireEvent.click(cta()); });
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('Lớp riêng tư chỉ mở cho người có liên kết mời.');
    expect(within(dialog).getByRole('link', { name: /Tạo liên kết mời/ })).toHaveAttribute('href', '/studio/classes/c1/members');
  });

  it('hides the global top bar on /classes/new only', () => {
    const at = (path: string) =>
      render(
        <MemoryRouter initialEntries={[path]}>
          <AppNavbar />
        </MemoryRouter>,
      );
    const home = at('/classes');
    expect(screen.getByRole('link', { name: 'Lớp Học Trực Tuyến - về trang chủ' })).toBeInTheDocument();
    home.unmount();
    at('/classes/new');
    expect(screen.queryByRole('banner')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Mở menu tài khoản' })).not.toBeInTheDocument();
  });
});
