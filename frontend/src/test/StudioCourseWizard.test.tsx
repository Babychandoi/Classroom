import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { StudioCourseWizard } from '../pages/studio/StudioCourseWizard';
import type { Classroom, Course } from '../types';

/**
 * Course wizard (Studio): stepper locking, validation, draft persistence (POST then PUT), the paid flow that creates the
 * linked product, the "Thoát mà chưa lưu?" guard, publish gating and permission gating. The server is a fetch mock.
 */

let currentClassroom: Classroom;
vi.mock('react-router-dom', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-router-dom')>()),
  useOutletContext: () => ({ classroom: currentClassroom }),
}));

const base = {
  id: 'class-1', ownerId: 'owner-1', slug: 'demo', title: 'Demo', status: 'ACTIVE', memberCount: 3, createdAt: new Date().toISOString(),
};
const owner = { ...base, userRole: 'OWNER' } as Classroom;
const staff = (grants: string[], extra: Partial<Classroom> = {}) =>
  ({ ...base, userRole: 'STAFF', studioPermissions: grants, studioScopedPermissions: [], ...extra }) as Classroom;

const lesson = { id: 'l1', sectionId: 's1', courseId: 'c1', title: 'Bài một', type: 'TEXT', position: 1, durationMinutes: 0, completed: false };
const section = { id: 's1', courseId: 'c1', title: 'Chương một', position: 1, lessons: [lesson] };
const draft = (over: Partial<Course> = {}): Course =>
  ({ id: 'c1', classId: 'class-1', title: 'Toán 10', description: 'Mô tả', coverImageUrl: '', accessMode: 'FREE', status: 'DRAFT', position: 0, sections: [], ...over }) as Course;

interface Call { url: string; method: string; body: any }
type Handler = (url: string, method: string, body: any) => unknown | undefined;

function mockServer(handler: Handler = () => undefined) {
  const calls: Call[] = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = input.toString().replace(/^https?:\/\/[^/]+/, '');
    const method = (init?.method || 'GET').toUpperCase();
    const body = init?.body ? JSON.parse(String(init.body)) : undefined;
    calls.push({ url, method, body });
    const custom = handler(url, method, body);
    if (custom !== undefined) {
      if (custom instanceof Response) return custom;
      return new Response(JSON.stringify({ success: true, data: custom }), { status: 200 });
    }
    return new Response(JSON.stringify({ success: false, error: { code: 'NOT_FOUND', message: 'Không có' } }), { status: 404 });
  });
  return calls;
}

const API = '/api/v1';
const renderWizard = (path: string) => {
  const router = createMemoryRouter(
    [
      { path: '/studio/classes/class-1/courses/new', element: <StudioCourseWizard /> },
      { path: '/studio/classes/class-1/courses/:courseId/edit', element: <StudioCourseWizard /> },
      { path: '/studio/classes/class-1/courses', element: <p>Danh sách khóa học</p> },
      { path: '/elsewhere', element: <p>Trang khác</p> },
    ],
    { initialEntries: [path] },
  );
  render(
    <>
      <RouterProvider router={router} />
    </>,
  );
  return router;
};

const stepButton = (n: number) => {
  const items = within(screen.getByRole('navigation', { name: 'Các bước tạo khóa học' })).getAllByRole('button');
  return items[n - 1];
};

beforeEach(() => {
  vi.restoreAllMocks();
  currentClassroom = owner;
  try { localStorage.clear(); } catch { /* ignore */ }
});
afterEach(() => { vi.restoreAllMocks(); });

describe('StudioCourseWizard - stepper', () => {
  it('shows four steps with the first current and the others locked until the previous ones are valid', async () => {
    mockServer();
    renderWizard('/studio/classes/class-1/courses/new');

    expect(stepButton(1)).toHaveAttribute('aria-current', 'step');
    expect(stepButton(2)).toBeDisabled();
    expect(stepButton(3)).toBeDisabled();
    expect(stepButton(4)).toBeDisabled();
    expect(within(stepButton(1)).getByText('Thông tin cơ bản')).toBeInTheDocument();
    expect(within(stepButton(2)).getByText('Chọn cách bán')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/Tên khóa học/), { target: { value: 'Toán 10' } });
    expect(stepButton(2)).not.toBeDisabled();
    // Lessons live under a course that does not exist yet, so step 3 stays shut.
    expect(stepButton(3)).toBeDisabled();
  });

  it('keeps the next step label on the primary button and goes back without saving', async () => {
    const calls = mockServer((url, method) => (method === 'POST' && url === `${API}/classes/class-1/courses` ? draft() : undefined));
    renderWizard('/studio/classes/class-1/courses/new');
    fireEvent.change(screen.getByLabelText(/Tên khóa học/), { target: { value: 'Toán 10' } });

    fireEvent.click(screen.getByRole('button', { name: /Tiếp tục: Loại bán/ }));
    await waitFor(() => expect(stepButton(2)).toHaveAttribute('aria-current', 'step'));
    expect(screen.getByRole('heading', { name: 'Chọn cách bán' })).toHaveFocus();
    expect(screen.getByRole('button', { name: /Tiếp tục: Nội dung bài học/ })).toBeInTheDocument();
    // Step 1 shows the green check once it is done.
    expect(within(stepButton(1)).getByText('Đã hoàn thành')).toBeInTheDocument();

    const before = calls.length;
    fireEvent.click(screen.getByRole('button', { name: /Quay lại/ }));
    await waitFor(() => expect(stepButton(1)).toHaveAttribute('aria-current', 'step'));
    expect(calls.length).toBe(before);
  });
});

describe('StudioCourseWizard - validation and draft persistence', () => {
  it('does not continue without a course name and says why', async () => {
    const calls = mockServer();
    renderWizard('/studio/classes/class-1/courses/new');

    fireEvent.click(screen.getByRole('button', { name: /Tiếp tục/ }));
    expect(await screen.findAllByText('Nhập tên khóa học để tiếp tục.')).not.toHaveLength(0);
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0);
    expect(stepButton(1)).toHaveAttribute('aria-current', 'step');
  });

  it('creates the DRAFT with POST on the first save and updates it with PUT afterwards', async () => {
    const calls = mockServer((url, method) => {
      if (method === 'POST' && url === `${API}/classes/class-1/courses`) return draft({ title: 'Toán 10' });
      if (method === 'PUT' && url === `${API}/courses/c1`) return draft();
      return undefined;
    });
    const router = renderWizard('/studio/classes/class-1/courses/new');

    fireEvent.change(screen.getByLabelText(/Tên khóa học/), { target: { value: 'Toán 10' } });
    fireEvent.change(screen.getByLabelText(/Mô tả ngắn/), { target: { value: 'Ôn thi' } });
    fireEvent.click(screen.getByRole('button', { name: 'Lưu nháp' }));

    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true));
    const post = calls.find((c) => c.method === 'POST')!;
    expect(post.url).toBe(`${API}/classes/class-1/courses`);
    expect(post.body).toMatchObject({ title: 'Toán 10', description: 'Ôn thi', accessMode: 'FREE' });
    await waitFor(() => expect(router.state.location.pathname).toBe('/studio/classes/class-1/courses/c1/edit'));
    expect(await screen.findByText(/Đã lưu lúc/)).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/Tên khóa học/), { target: { value: 'Toán 10 nâng cao' } });
    fireEvent.click(screen.getByRole('button', { name: 'Lưu nháp' }));
    await waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true));
    const put = calls.find((c) => c.method === 'PUT')!;
    expect(put.url).toBe(`${API}/courses/c1`);
    expect(put.body).toMatchObject({ title: 'Toán 10 nâng cao' });
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(1);
  });

  it('reopens a draft on the requested step with its data and loads the curriculum tree', async () => {
    mockServer((url) => (url === `${API}/courses/c1` ? draft({ sections: [section] as any }) : undefined));
    renderWizard('/studio/classes/class-1/courses/c1/edit?step=3');

    expect(await screen.findByText('Danh mục 1: Chương một')).toBeInTheDocument();
    expect(screen.getByText('Bài một')).toBeInTheDocument();
    expect(screen.getByText('1 danh mục • 1 bài học')).toBeInTheDocument();
    expect(stepButton(3)).toHaveAttribute('aria-current', 'step');
    expect(screen.getByRole('button', { name: /Tiếp tục: Kiểm tra & xuất bản/ })).toBeInTheDocument();
  });

  it('cannot leave the lessons step without a lesson; a category is added from the tree', async () => {
    const calls = mockServer((url, method) => {
      if (url === `${API}/courses/c1` && method === 'GET') return draft({ sections: [] });
      if (url === `${API}/courses/c1/sections` && method === 'POST') return { ...section, lessons: [] };
      return undefined;
    });
    renderWizard('/studio/classes/class-1/courses/c1/edit?step=3');
    await screen.findByText(/chưa có danh mục nào/);

    fireEvent.click(screen.getByRole('button', { name: /Tiếp tục: Kiểm tra/ }));
    expect(await screen.findByText('Thêm ít nhất một bài học để tiếp tục.')).toBeInTheDocument();
    expect(stepButton(3)).toHaveAttribute('aria-current', 'step');
    expect(stepButton(4)).toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: 'Thêm danh mục' }));
    fireEvent.change(screen.getByLabelText('Tên danh mục mới'), { target: { value: 'Chương một' } });
    fireEvent.click(screen.getByRole('button', { name: 'Lưu danh mục' }));
    await waitFor(() => expect(calls.some((c) => c.url === `${API}/courses/c1/sections` && c.method === 'POST')).toBe(true));
    expect(calls.find((c) => c.url.endsWith('/sections'))!.body).toMatchObject({ title: 'Chương một', position: 1 });
  });
});

describe('StudioCourseWizard - selling step', () => {
  it('formats the price as typed and creates the linked product when continuing', async () => {
    const calls = mockServer((url, method) => {
      if (url === `${API}/courses/c1` && method === 'GET') return draft({ sections: [section] as any });
      if (url === `${API}/classes/class-1/products` && method === 'POST') return { id: 'p1', status: 'DRAFT', title: 'Toán 10' };
      return undefined;
    });
    renderWizard('/studio/classes/class-1/courses/c1/edit?step=2');
    await screen.findByRole('heading', { name: 'Chọn cách bán' });

    fireEvent.click(screen.getByRole('radio', { name: /Trả phí/ }));
    const price = screen.getByLabelText(/Giá khóa học/) as HTMLInputElement;
    fireEvent.change(price, { target: { value: '299000' } });
    expect(price.value).toBe('299.000');
    fireEvent.change(screen.getByLabelText(/Thời hạn truy cập/), { target: { value: '90' } });

    fireEvent.click(screen.getByRole('button', { name: /Tiếp tục: Nội dung bài học/ }));
    await waitFor(() => expect(calls.some((c) => c.url === `${API}/classes/class-1/products`)).toBe(true));
    const post = calls.find((c) => c.url === `${API}/classes/class-1/products`)!;
    expect(post.body).toMatchObject({ title: 'Toán 10', price: 299000, durationDays: 90, targetCourseId: 'c1' });
    await waitFor(() => expect(stepButton(3)).toHaveAttribute('aria-current', 'step'));
  });

  it('blocks a paid course without a price and does not create a product', async () => {
    const calls = mockServer((url) => (url === `${API}/courses/c1` ? draft() : undefined));
    renderWizard('/studio/classes/class-1/courses/c1/edit?step=2');
    await screen.findByRole('heading', { name: 'Chọn cách bán' });

    fireEvent.click(screen.getByRole('radio', { name: /Trả phí/ }));
    expect(stepButton(3)).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: /Tiếp tục/ }));
    expect(await screen.findAllByText('Nhập giá bán lớn hơn 0đ cho khóa học trả phí.')).not.toHaveLength(0);
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0);
  });

  it('updates the existing product with PUT and locks the course to paid once it has a product', async () => {
    currentClassroom = owner;
    const calls = mockServer((url, method) => {
      if (url === `${API}/courses/c1` && method === 'GET') return draft({ accessMode: 'PURCHASE_REQUIRED', productId: 'p1', sections: [section] as any });
      if (url === `${API}/classes/class-1/studio/products`) return [{ id: 'p1', classId: 'class-1', kind: 'STANDARD', title: 'Toán 10', status: 'DRAFT', price: 199000, currency: 'VND', durationDays: 30 }];
      if (url === `${API}/products/p1` && method === 'PUT') return {};
      return undefined;
    });
    renderWizard('/studio/classes/class-1/courses/c1/edit?step=2');
    const price = (await screen.findByLabelText(/Giá khóa học/)) as HTMLInputElement;
    await waitFor(() => expect(price.value).toBe('199.000'));
    expect(screen.getByRole('radio', { name: /Miễn phí/ })).toBeDisabled();

    fireEvent.change(price, { target: { value: '249000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Lưu nháp' }));
    await waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true));
    expect(calls.find((c) => c.method === 'PUT')).toMatchObject({ url: `${API}/products/p1`, body: { price: 249000, durationDays: 30 } });
  });

  it('disables the paid option with an explanation when the user cannot create products', async () => {
    currentClassroom = staff(['COURSE:CREATE', 'COURSE:EDIT']);
    mockServer((url) => (url === `${API}/courses/c1` ? draft() : undefined));
    renderWizard('/studio/classes/class-1/courses/c1/edit?step=2');
    await screen.findByRole('heading', { name: 'Chọn cách bán' });

    expect(screen.getByRole('radio', { name: /Trả phí/ })).toBeDisabled();
    expect(screen.getByText(/chưa có quyền tạo sản phẩm trong Shop/)).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: /Miễn phí/ })).toBeChecked();
  });
});

describe('StudioCourseWizard - leave confirmation', () => {
  const dirtyWizard = async () => {
    mockServer((url) => (url === `${API}/courses/c1` ? draft() : undefined));
    const router = renderWizard('/studio/classes/class-1/courses/c1/edit?step=1');
    const name = (await screen.findByLabelText(/Tên khóa học/)) as HTMLInputElement;
    await waitFor(() => expect(name.value).toBe('Toán 10'));
    return { router, name };
  };

  it('asks before leaving with unsaved changes; "Ở lại" keeps the page, "Thoát" leaves', async () => {
    const { router, name } = await dirtyWizard();
    fireEvent.change(name, { target: { value: 'Toán 11' } });

    fireEvent.click(screen.getAllByRole('link', { name: 'Khóa học' })[0]);
    const dialog = await screen.findByRole('alertdialog', { name: 'Thoát mà chưa lưu?' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Ở lại' }));
    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument());
    expect(router.state.location.pathname).toBe('/studio/classes/class-1/courses/c1/edit');
    expect((screen.getByLabelText(/Tên khóa học/) as HTMLInputElement).value).toBe('Toán 11');

    fireEvent.click(screen.getAllByRole('link', { name: 'Khóa học' })[0]);
    fireEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Thoát, bỏ thay đổi' }));
    expect(await screen.findByText('Danh sách khóa học')).toBeInTheDocument();
  });

  it('leaves without asking when nothing changed', async () => {
    await dirtyWizard();
    fireEvent.click(screen.getAllByRole('link', { name: 'Khóa học' })[0]);
    expect(await screen.findByText('Danh sách khóa học')).toBeInTheDocument();
  });

  it('asks the browser to confirm closing the tab only while there are unsaved changes', async () => {
    const { name } = await dirtyWizard();
    const clean = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(clean);
    expect(clean.defaultPrevented).toBe(false);

    fireEvent.change(name, { target: { value: 'Toán 11' } });
    const dirty = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(dirty);
    expect(dirty.defaultPrevented).toBe(true);
  });

  it('does not ask again after saving', async () => {
    const { name } = await dirtyWizard();
    vi.restoreAllMocks();
    mockServer((url, method) => (method === 'PUT' && url === `${API}/courses/c1` ? draft() : undefined));
    fireEvent.change(name, { target: { value: 'Toán 11' } });
    fireEvent.click(screen.getByRole('button', { name: 'Lưu nháp' }));
    await screen.findByText(/Đã lưu lúc/);
    fireEvent.click(screen.getAllByRole('link', { name: 'Khóa học' })[0]);
    expect(await screen.findByText('Danh sách khóa học')).toBeInTheDocument();
  });
});

describe('StudioCourseWizard - review and publish', () => {
  const readyCourse = (over: Partial<Course> = {}) => draft({ sections: [section] as any, coverImageUrl: 'https://x.test/c.png', ...over });

  it('summarises each step, jumps to it with "Sửa" and offers both previews', async () => {
    mockServer((url) => (url === `${API}/courses/c1` ? readyCourse() : undefined));
    renderWizard('/studio/classes/class-1/courses/c1/edit?step=4');

    expect(await screen.findByRole('heading', { name: 'Xem trước & xuất bản' })).toBeInTheDocument();
    expect(screen.getByRole('list', { name: 'Danh sách kiểm tra' })).toBeInTheDocument();
    expect(screen.getByText('Đã có 1 bài học')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Xem trước thẻ khóa học' }));
    const card = await screen.findByTestId('course-card-preview');
    expect(within(card).getByText('Toán 10')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Xem trang khóa học/ }));
    const detail = await screen.findByTestId('course-detail-preview');
    expect(within(detail).getByText('Chương một')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Đóng' }));

    fireEvent.click(screen.getByRole('button', { name: 'Sửa loại bán' }));
    await waitFor(() => expect(stepButton(2)).toHaveAttribute('aria-current', 'step'));
  });

  it('publishes a free course through POST /courses/{id}/publish', async () => {
    const calls = mockServer((url, method) => {
      if (url === `${API}/courses/c1` && method === 'GET') return readyCourse();
      if (url === `${API}/courses/c1/publish`) return readyCourse({ status: 'PUBLISHED' });
      return undefined;
    });
    renderWizard('/studio/classes/class-1/courses/c1/edit?step=4');
    fireEvent.click(await screen.findByRole('button', { name: 'Xuất bản' }));

    await waitFor(() => expect(calls.some((c) => c.url === `${API}/courses/c1/publish`)).toBe(true));
    expect(await screen.findByText(/Khóa học đã được xuất bản/)).toBeInTheDocument();
    expect(calls.some((c) => c.url.includes('/products/'))).toBe(false);
  });

  it('publishes the linked product too when STORE:PUBLISH is held', async () => {
    const calls = mockServer((url, method) => {
      if (url === `${API}/courses/c1` && method === 'GET') return readyCourse({ accessMode: 'PURCHASE_REQUIRED', productId: 'p1' });
      if (url === `${API}/classes/class-1/studio/products`) return [{ id: 'p1', status: 'DRAFT', price: 199000, durationDays: 30, kind: 'STANDARD' }];
      if (url === `${API}/courses/c1/publish` || url === `${API}/products/p1/publish`) return {};
      return undefined;
    });
    renderWizard('/studio/classes/class-1/courses/c1/edit?step=4');
    await waitFor(() => expect(screen.getByText(/199\.000đ \/ 30 ngày/)).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: 'Xuất bản' }));

    await waitFor(() => expect(calls.some((c) => c.url === `${API}/products/p1/publish`)).toBe(true));
    expect(calls.findIndex((c) => c.url === `${API}/courses/c1/publish`)).toBeLessThan(calls.findIndex((c) => c.url === `${API}/products/p1/publish`));
  });

  it('tells the user what remains when the product cannot be published (no STORE:PUBLISH)', async () => {
    currentClassroom = staff(['COURSE:EDIT', 'COURSE:PUBLISH', 'STORE:VIEW']);
    const calls = mockServer((url, method) => {
      if (url === `${API}/courses/c1` && method === 'GET') return readyCourse({ accessMode: 'PURCHASE_REQUIRED', productId: 'p1' });
      if (url === `${API}/classes/class-1/studio/products`) return [{ id: 'p1', status: 'DRAFT', price: 199000, durationDays: 30, kind: 'STANDARD' }];
      if (url === `${API}/courses/c1/publish`) return {};
      return undefined;
    });
    renderWizard('/studio/classes/class-1/courses/c1/edit?step=4');
    await waitFor(() => expect(screen.getByText(/199\.000đ \/ 30 ngày/)).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: 'Xuất bản' }));

    expect(await screen.findByText(/sản phẩm bán khóa học chưa mở bán/)).toBeInTheDocument();
    expect(calls.some((c) => c.url === `${API}/products/p1/publish`)).toBe(false);
  });

  it('disables publishing without COURSE:PUBLISH and explains it', async () => {
    currentClassroom = staff(['COURSE:EDIT']);
    mockServer((url) => (url === `${API}/courses/c1` ? readyCourse() : undefined));
    renderWizard('/studio/classes/class-1/courses/c1/edit?step=4');

    const publish = await screen.findByRole('button', { name: 'Xuất bản' });
    expect(publish).toBeDisabled();
    expect(screen.getByText(/chưa có quyền xuất bản khóa học này/, { selector: 'div' })).toBeInTheDocument();
  });

  it('shows "Ngừng xuất bản" for a published course and archives it after confirmation', async () => {
    const calls = mockServer((url, method) => {
      if (url === `${API}/courses/c1` && method === 'GET') return readyCourse({ status: 'PUBLISHED' });
      if (url === `${API}/courses/c1/archive`) return {};
      return undefined;
    });
    renderWizard('/studio/classes/class-1/courses/c1/edit?step=4');
    expect(screen.queryByRole('button', { name: 'Xuất bản' })).not.toBeInTheDocument();
    fireEvent.click(await screen.findByRole('button', { name: 'Ngừng xuất bản' }));
    const dialog = await screen.findByRole('alertdialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Ngừng xuất bản' }));

    await waitFor(() => expect(calls.some((c) => c.url === `${API}/courses/c1/archive`)).toBe(true));
    expect(await screen.findByText('Danh sách khóa học')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Lưu' })).not.toBeInTheDocument();
  });
});

describe('StudioCourseWizard - permissions and read-only classes', () => {
  it('does not offer creation without COURSE:CREATE', async () => {
    currentClassroom = staff(['COURSE:EDIT']);
    mockServer();
    renderWizard('/studio/classes/class-1/courses/new');
    expect(await screen.findByText('Bạn chưa có quyền tạo khóa học')).toBeInTheDocument();
    expect(screen.queryByLabelText(/Tên khóa học/)).not.toBeInTheDocument();
  });

  it('does not open an existing course without COURSE:EDIT on it', async () => {
    currentClassroom = staff(['COURSE:CREATE']);
    mockServer((url) => (url === `${API}/courses/c1` ? draft() : undefined));
    renderWizard('/studio/classes/class-1/courses/c1/edit');
    expect(await screen.findByText('Bạn chưa có quyền sửa khóa học này')).toBeInTheDocument();
  });

  it('accepts a course-scoped COURSE:EDIT grant', async () => {
    currentClassroom = staff([], { studioScopedPermissions: [{ module: 'COURSE', action: 'EDIT', courseId: 'c1' }] });
    mockServer((url) => (url === `${API}/courses/c1` ? draft() : undefined));
    renderWizard('/studio/classes/class-1/courses/c1/edit');
    expect(await screen.findByLabelText(/Tên khóa học/)).toBeInTheDocument();
  });

  it('refuses to create a course in a suspended class', async () => {
    currentClassroom = { ...owner, status: 'SUSPENDED' } as Classroom;
    mockServer();
    renderWizard('/studio/classes/class-1/courses/new');
    expect(await screen.findByText('Không thể tạo khóa học lúc này')).toBeInTheDocument();
  });

  it('opens an existing course read-only in a suspended class (no save, no publish)', async () => {
    currentClassroom = { ...owner, status: 'SUSPENDED' } as Classroom;
    const calls = mockServer((url) => (url === `${API}/courses/c1` ? draft() : undefined));
    renderWizard('/studio/classes/class-1/courses/c1/edit?step=1');
    const name = (await screen.findByLabelText(/Tên khóa học/)) as HTMLInputElement;
    expect(name).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Lưu nháp' })).toBeDisabled();
    expect(calls.filter((c) => c.method !== 'GET')).toHaveLength(0);
  });

  it('opens an archived course read-only; its step 4 offers "Khôi phục khóa học"', async () => {
    const calls = mockServer((url, method) => {
      if (url === `${API}/courses/c1` && method === 'GET') return draft({ status: 'ARCHIVED', sections: [section] as any });
      if (url === `${API}/courses/c1/restore`) return draft();
      return undefined;
    });
    renderWizard('/studio/classes/class-1/courses/c1/edit?step=4');
    expect(await screen.findByRole('heading', { name: 'Quản lý khóa học' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Xuất bản' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Xóa khóa học' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Khôi phục khóa học' }));
    await waitFor(() => expect(calls.some((c) => c.url === `${API}/courses/c1/restore` && c.method === 'POST')).toBe(true));
  });
});

describe('StudioCourseWizard - quản lý khóa học (moved from the list)', () => {
  const ready = (over: Partial<Course> = {}) => draft({ sections: [section] as any, ...over });

  it('archives a DRAFT course after confirmation and goes back to the list', async () => {
    const calls = mockServer((url, method) => {
      if (url === `${API}/courses/c1` && method === 'GET') return ready();
      if (url === `${API}/courses/c1/archive`) return {};
      return undefined;
    });
    renderWizard('/studio/classes/class-1/courses/c1/edit?step=4');
    fireEvent.click(await screen.findByRole('button', { name: 'Lưu trữ khóa học' }));
    fireEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Lưu trữ' }));
    await waitFor(() => expect(calls.some((c) => c.url === `${API}/courses/c1/archive`)).toBe(true));
    expect(await screen.findByText('Danh sách khóa học')).toBeInTheDocument();
  });

  it('R18-07: a delete refused with a 409 explains itself inside the danger zone and keeps the page', async () => {
    const message = 'Không thể xóa khóa học vì đang có quyền trợ giảng gắn riêng với khóa này: Lan (COURSE:EDIT).';
    const calls = mockServer((url, method) => {
      if (url === `${API}/courses/c1` && method === 'GET') return ready();
      if (url === `${API}/courses/c1` && method === 'DELETE') {
        return new Response(JSON.stringify({ success: false, error: { code: 'CONFLICT', message } }), { status: 409 });
      }
      return undefined;
    });
    renderWizard('/studio/classes/class-1/courses/c1/edit?step=4');
    fireEvent.click(await screen.findByRole('button', { name: 'Xóa khóa học' }));
    fireEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Xóa khóa học' }));

    const zone = await screen.findByRole('region', { name: 'Quản lý khóa học' });
    expect(await within(zone).findByText(message)).toBeInTheDocument();
    expect(calls.some((c) => c.method === 'DELETE')).toBe(true);
    expect(screen.queryByText('Danh sách khóa học')).not.toBeInTheDocument();
  });

  it('a draft without lessons (step 4 still locked) can be deleted from step 1', async () => {
    const calls = mockServer((url, method) => {
      if (url === `${API}/courses/c1` && method === 'GET') return draft({ sections: [] });
      if (url === `${API}/courses/c1` && method === 'DELETE') return {};
      return undefined;
    });
    renderWizard('/studio/classes/class-1/courses/c1/edit?step=1');
    fireEvent.click(await screen.findByRole('button', { name: 'Xóa khóa học' }));
    expect(stepButton(4)).toBeDisabled();
    fireEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Xóa khóa học' }));
    await waitFor(() => expect(calls.some((c) => c.method === 'DELETE')).toBe(true));
    expect(await screen.findByText('Danh sách khóa học')).toBeInTheDocument();
  });

  it('offers deleting only for drafts and managing only to people who may edit the course', async () => {
    mockServer((url) => (url === `${API}/courses/c1` ? ready({ status: 'PUBLISHED' }) : undefined));
    renderWizard('/studio/classes/class-1/courses/c1/edit?step=4');
    await screen.findByRole('heading', { name: 'Quản lý khóa học' });
    expect(screen.queryByRole('button', { name: 'Xóa khóa học' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Lưu trữ khóa học' })).toBeInTheDocument();
  });
});

describe('StudioCourseWizard - content studio (step 3)', () => {
  const lessonA = { ...lesson, id: 'la', title: 'Bài A', type: 'VIDEO', position: 1 };
  const lessonB = { ...lesson, id: 'lb', title: 'Bài B', type: 'VIDEO', position: 2, archived: true };
  const withLessons = () => draft({ sections: [{ ...section, lessons: [lessonA, lessonB] }] as any });

  it('shows the tree with a "Nháp" chip on hidden lessons and an empty detail until one is chosen', async () => {
    mockServer((url) => (url === `${API}/courses/c1` ? withLessons() : undefined));
    renderWizard('/studio/classes/class-1/courses/c1/edit?step=3');
    expect(await screen.findByText('Bài A')).toBeInTheDocument();
    expect(within(screen.getByRole('region', { name: 'Cấu trúc nội dung' })).getByText('Nháp')).toBeInTheDocument();
    expect(screen.getByText(/Chọn một bài học ở cột bên trái/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Bài A' }));
    expect(await screen.findByLabelText(/Tên bài học/)).toHaveValue('Bài A');
    expect(screen.getByRole('button', { name: 'Bài A' })).toHaveAttribute('aria-current', 'true');
    expect(screen.getByTestId('lesson-preview')).toHaveTextContent('Bài A');
    const checks = screen.getByRole('region', { name: 'Danh sách kiểm tra' });
    expect(within(checks).getByText(/Đã nhập tiêu đề bài học/)).toBeInTheDocument();
    expect(within(checks).getByText(/Đã thêm nội dung/).closest('li')).toHaveTextContent('chưa có');
  });

  it('adds a lesson at the end of its category (position) and selects it', async () => {
    const calls = mockServer((url, method) => {
      if (url === `${API}/courses/c1` && method === 'GET') return withLessons();
      if (url === `${API}/sections/s1/lessons` && method === 'POST') return { ...lesson, id: 'new', title: 'Bài mới', position: 3 };
      return undefined;
    });
    renderWizard('/studio/classes/class-1/courses/c1/edit?step=3');
    await screen.findByText('Bài A');
    fireEvent.click(screen.getByRole('button', { name: 'Thêm bài học' }));
    fireEvent.change(screen.getByLabelText('Tên bài học mới'), { target: { value: 'Bài mới' } });
    fireEvent.click(screen.getByRole('button', { name: 'Tạo bài' }));
    await waitFor(() => expect(calls.some((c) => c.url === `${API}/sections/s1/lessons`)).toBe(true));
    expect(calls.find((c) => c.url.endsWith('/lessons'))!.body).toMatchObject({ title: 'Bài mới', type: 'VIDEO', position: 3 });
  });

  it('saves the lesson with the explicit button, using a blank media id to detach', async () => {
    let stored = withLessons();
    const calls = mockServer((url, method, body) => {
      if (url === `${API}/courses/c1` && method === 'GET') return stored;
      if (url === `${API}/lessons/la` && method === 'PUT') {
        stored = draft({ sections: [{ ...section, lessons: [{ ...lessonA, ...body }, lessonB] }] as any });
        return {};
      }
      return undefined;
    });
    renderWizard('/studio/classes/class-1/courses/c1/edit?step=3');
    fireEvent.click(await screen.findByRole('button', { name: 'Bài A' }));
    const title = await screen.findByLabelText(/Tên bài học/);
    expect(screen.getByRole('button', { name: 'Lưu bài học' })).toBeDisabled();
    fireEvent.change(title, { target: { value: 'Bài A đổi tên' } });
    fireEvent.change(screen.getByLabelText('Phụ đề WebVTT'), { target: { value: 'WEBVTT' } });
    expect(screen.getByText('Có thay đổi chưa lưu')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Lưu bài học' }));
    await waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true));
    expect(calls.find((c) => c.method === 'PUT')!.body).toMatchObject({ title: 'Bài A đổi tên', captionsVtt: 'WEBVTT', mediaAssetId: '', type: 'VIDEO' });
    expect(await screen.findByText(/Đã lưu lúc/, { selector: 'span' })).toBeInTheDocument();
  });

  it('toggles "Nháp" through the archive endpoint and deletes a lesson after confirmation', async () => {
    const calls = mockServer((url, method) => {
      if (url === `${API}/courses/c1` && method === 'GET') return withLessons();
      if (url.startsWith(`${API}/lessons/la/archive`)) return {};
      if (url === `${API}/lessons/la` && method === 'DELETE') return {};
      return undefined;
    });
    renderWizard('/studio/classes/class-1/courses/c1/edit?step=3');
    fireEvent.click(await screen.findByRole('button', { name: 'Bài A' }));
    fireEvent.click(await screen.findByRole('switch', { name: /Nháp/ }));
    await waitFor(() => expect(calls.some((c) => c.url === `${API}/lessons/la/archive?archived=true`)).toBe(true));

    fireEvent.click(screen.getByRole('button', { name: 'Xoá bài học' }));
    fireEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Xác nhận' }));
    await waitFor(() => expect(calls.some((c) => c.method === 'DELETE' && c.url === `${API}/lessons/la`)).toBe(true));
  });

  it('reorders lessons by drag and drop and by Alt+arrow on the handle', async () => {
    const calls = mockServer((url, method) => {
      if (url === `${API}/courses/c1` && method === 'GET') return withLessons();
      if (url === `${API}/sections/s1/lessons/reorder`) return {};
      return undefined;
    });
    renderWizard('/studio/classes/class-1/courses/c1/edit?step=3');
    await screen.findByText('Bài A');

    fireEvent.keyDown(screen.getByRole('button', { name: /Sắp xếp bài học "Bài A"/ }), { key: 'ArrowDown', altKey: true });
    await waitFor(() => expect(calls.filter((c) => c.url.endsWith('/lessons/reorder'))).toHaveLength(1));
    expect(calls.find((c) => c.url.endsWith('/lessons/reorder'))!.body).toEqual(['lb', 'la']);

    const rows = within(screen.getByRole('region', { name: 'Cấu trúc nội dung' })).getAllByRole('listitem');
    const data: Record<string, string> = {};
    const dataTransfer = { setData: (k: string, v: string) => { data[k] = v; }, getData: (k: string) => data[k], effectAllowed: '' };
    fireEvent.dragStart(rows[1], { dataTransfer });
    fireEvent.dragOver(rows[0], { dataTransfer, clientY: 0 });
    fireEvent.drop(rows[0], { dataTransfer, clientY: 0 });
    await waitFor(() => expect(calls.filter((c) => c.url.endsWith('/lessons/reorder'))).toHaveLength(2));
    expect(calls.filter((c) => c.url.endsWith('/lessons/reorder'))[1].body).toEqual(['lb', 'la']);
  });

  it('edits a text lesson with the Markdown toolbar and previews it', async () => {
    const textLesson = { ...lesson, id: 'lt', title: 'Đọc', type: 'TEXT', contentText: 'xin chào' };
    mockServer((url) => (url === `${API}/courses/c1` ? draft({ sections: [{ ...section, lessons: [textLesson] }] as any }) : undefined));
    renderWizard('/studio/classes/class-1/courses/c1/edit?step=3');
    fireEvent.click(await screen.findByRole('button', { name: 'Đọc' }));
    const body = (await screen.findByLabelText('Nội dung bài viết')) as HTMLTextAreaElement;
    body.setSelectionRange(0, 3);
    fireEvent.click(screen.getByRole('button', { name: 'In đậm' }));
    expect(body.value).toBe('**xin** chào');

    fireEvent.click(screen.getByRole('button', { name: 'Xem trước nội dung' }));
    expect(within(screen.getByTestId('markdown-preview')).getByText('xin').tagName).toBe('STRONG');
    // Text lessons also have the "Tài liệu" tab for the attached file.
    fireEvent.click(screen.getByRole('tab', { name: 'Tài liệu' }));
    expect(screen.getByLabelText('Tệp đính kèm')).toBeInTheDocument();
  });

  it('video lessons offer upload / YouTube / Google Drive sources and no document tab', async () => {
    mockServer((url) => (url === `${API}/courses/c1` ? withLessons() : undefined));
    renderWizard('/studio/classes/class-1/courses/c1/edit?step=3');
    fireEvent.click(await screen.findByRole('button', { name: 'Bài A' }));
    expect(await screen.findByLabelText(/Video bài học/)).toHaveAttribute('type', 'file');
    const tabs = within(screen.getByRole('tablist', { name: 'Nguồn video' })).getAllByRole('tab');
    expect(tabs.map((t) => t.textContent)).toEqual(['Tải lên', 'YouTube', 'Google Drive']);
    expect(tabs[0]).toHaveAttribute('aria-selected', 'true');
    expect(screen.queryByRole('tab', { name: 'Tài liệu' })).not.toBeInTheDocument();
  });

  const ytLesson = { ...lessonA, videoProvider: 'YOUTUBE', videoUrl: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ', embedUrl: 'https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ' };
  const withYoutube = () => draft({ sections: [{ ...section, lessons: [ytLesson] }] as any });

  it('validates a pasted YouTube link, previews it in a sandboxed iframe and saves videoUrl with no upload', async () => {
    const calls = mockServer((url, method) => {
      if (url === `${API}/courses/c1` && method === 'GET') return withLessons();
      if (url === `${API}/lessons/la` && method === 'PUT') return {};
      return undefined;
    });
    renderWizard('/studio/classes/class-1/courses/c1/edit?step=3');
    fireEvent.click(await screen.findByRole('button', { name: 'Bài A' }));
    fireEvent.click(await screen.findByRole('tab', { name: 'YouTube' }));
    const field = screen.getByLabelText(/Liên kết video YouTube/);

    fireEvent.change(field, { target: { value: 'https://vimeo.com/123' } });
    expect(screen.getByText(/Liên kết YouTube không hợp lệ/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Lưu bài học' })).toBeDisabled();
    expect(screen.queryByTitle(/Xem trước video/)).not.toBeInTheDocument();

    fireEvent.change(field, { target: { value: 'https://youtu.be/dQw4w9WgXcQ?si=abc' } });
    const frame = screen.getByTitle('Xem trước video YouTube');
    expect(frame).toHaveAttribute('src', 'https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ');
    expect(frame).toHaveAttribute('sandbox', 'allow-scripts allow-same-origin allow-presentation allow-popups');
    expect(screen.getByTestId('lesson-preview').parentElement).toHaveTextContent('Có video · YouTube');

    fireEvent.click(screen.getByRole('button', { name: 'Lưu bài học' }));
    await waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true));
    expect(calls.find((c) => c.method === 'PUT')!.body).toMatchObject({ videoUrl: 'https://youtu.be/dQw4w9WgXcQ?si=abc', mediaAssetId: '' });
  });

  it('rejects a Drive link on the YouTube tab with a hint to switch tab', async () => {
    mockServer((url) => (url === `${API}/courses/c1` ? withLessons() : undefined));
    renderWizard('/studio/classes/class-1/courses/c1/edit?step=3');
    fireEvent.click(await screen.findByRole('button', { name: 'Bài A' }));
    fireEvent.click(await screen.findByRole('tab', { name: 'YouTube' }));
    fireEvent.change(screen.getByLabelText(/Liên kết video YouTube/), { target: { value: 'https://drive.google.com/file/d/1AbCdEfGhIj/view' } });
    expect(screen.getByText(/hãy chọn tab Google Drive/)).toBeInTheDocument();
  });

  it('asks before discarding the current source and clears the link with videoUrl ""', async () => {
    const calls = mockServer((url, method) => {
      if (url === `${API}/courses/c1` && method === 'GET') return withYoutube();
      if (url === `${API}/lessons/la` && method === 'PUT') return {};
      return undefined;
    });
    renderWizard('/studio/classes/class-1/courses/c1/edit?step=3');
    fireEvent.click(await screen.findByRole('button', { name: 'Bài A' }));
    expect(await screen.findByLabelText(/Liên kết video YouTube/)).toHaveValue('https://www.youtube.com/watch?v=dQw4w9WgXcQ');
    expect(screen.getByRole('tab', { name: 'YouTube' })).toHaveAttribute('aria-selected', 'true');

    fireEvent.click(screen.getByRole('tab', { name: 'Tải lên' }));
    const dialog = await screen.findByRole('alertdialog', { name: 'Đổi nguồn video?' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Giữ nguồn hiện tại' }));
    expect(screen.getByRole('tab', { name: 'YouTube' })).toHaveAttribute('aria-selected', 'true');

    fireEvent.click(screen.getByRole('tab', { name: 'Tải lên' }));
    fireEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Đổi nguồn video' }));
    expect(screen.getByLabelText(/Video bài học/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Lưu bài học' }));
    await waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true));
    expect(calls.find((c) => c.method === 'PUT')!.body).toMatchObject({ videoUrl: '', mediaAssetId: '' });
  });

  it('shows the server 400 text when the backend refuses the link', async () => {
    mockServer((url, method) => {
      if (url === `${API}/courses/c1` && method === 'GET') return withLessons();
      if (url === `${API}/lessons/la` && method === 'PUT') {
        return new Response(JSON.stringify({ success: false, error: { code: 'BAD_REQUEST', message: 'Chỉ hỗ trợ liên kết video YouTube hoặc Google Drive (dạng https://…)' } }), { status: 400 });
      }
      return undefined;
    });
    renderWizard('/studio/classes/class-1/courses/c1/edit?step=3');
    fireEvent.click(await screen.findByRole('button', { name: 'Bài A' }));
    fireEvent.click(await screen.findByRole('tab', { name: 'Google Drive' }));
    fireEvent.change(screen.getByLabelText(/Liên kết video Google Drive/), { target: { value: 'https://drive.google.com/file/d/1AbCdEfGhIj/view' } });
    fireEvent.click(screen.getByRole('button', { name: 'Lưu bài học' }));
    expect(await screen.findByText('Chỉ hỗ trợ liên kết video YouTube hoặc Google Drive (dạng https://…)')).toBeInTheDocument();
  });

  it('is read-only without COURSE:EDIT handled by the wizard guard; a read-only class hides the tree actions', async () => {
    currentClassroom = { ...owner, status: 'SUSPENDED' } as Classroom;
    mockServer((url) => (url === `${API}/courses/c1` ? withLessons() : undefined));
    renderWizard('/studio/classes/class-1/courses/c1/edit?step=3');
    await screen.findByText('Bài A');
    expect(screen.queryByRole('button', { name: 'Thêm danh mục' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Sắp xếp bài học/ })).not.toBeInTheDocument();
  });
});
