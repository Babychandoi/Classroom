'use strict';
// Hành trình E2E chính (J1-J6) chạy trên trình duyệt thật. Xem README.md.
// Tạo người dùng/lớp học e2e.* MỚI trên hệ thống đích ở mỗi lần chạy - không dọn dẹp sau khi chạy.
const fs = require('fs');
const path = require('path');
const {
  BASE, FIXTURES, SHOTS_ROOT, log, newRunId, launchBrowser, newCtx, createRunner, settle,
  register, login, isLoggedIn, createClassViaUI, overflowCheck, saveState, assertSafeTarget, unexpectedEvents,
} = require('./lib');

assertSafeTarget();

const RUN = newRunId();
const OWNER = { name: `E2E Owner ${RUN}`, email: `e2e.owner.${RUN}@example.com` };
const STUDENT = { name: `E2E Student ${RUN}`, email: `e2e.student.${RUN}@example.com` };
// slug do máy chủ sinh từ tên lớp (đọc lại sau khi tạo); giá trị dưới đây chỉ là dự đoán ban đầu.
const CLASS = { title: `E2E Class ${RUN}`, slug: `e2e-class-${RUN}`, desc: 'Lop kiem thu E2E trinh duyet that', category: 'Ôn thi' };
const PAID_CLASS = { title: `E2E Lớp Có Phí ${RUN}`, desc: 'Lop co phi co anh bia', category: 'Tiếng Anh', price: '79.000' };
const COURSE = `E2E Course ${RUN}`;
const SECTION = `Chuong 1 ${RUN}`;
const LESSON = `Bai video ${RUN}`;
const EXAM = `E2E Exam ${RUN}`;
const PRODUCT = `E2E Product ${RUN}`;
const DOC = `E2E Doc ${RUN}`;
const MINIO_PORT = process.env.E2E_MINIO_PORT || '9000';

const SHOTS = path.join(SHOTS_ROOT, process.env.RUNDIR || `run-${RUN}`);
const { results, events, step, shot, summarize, instrument } = createRunner(SHOTS);
const state = {};

const persist = () => saveState({
  run: RUN, owner: OWNER, student: STUDENT, classSlug: CLASS.slug, classId: state.classId,
  examTitle: EXAM, courseTitle: COURSE, createdAt: new Date().toISOString(),
});

(async () => {
  const browser = await launchBrowser();
  log('browser', browser.version(), 'run', RUN, 'base', BASE);

  // ---------------- Journey 1: đăng ký, tạo lớp, phiên đăng nhập ----------------
  const ownerCtx = await newCtx(browser);
  const owner = await ownerCtx.newPage();
  instrument(owner, 'owner');

  await step('J1', 'register owner via UI', owner, async () => { await register(owner, OWNER); });
  await step('J1', 'create class via UI', owner, async () => {
    // Lời gọi "Tạo lớp học mới" ở trang chủ dẫn tới trang toàn màn hình /classes/new (không còn hộp thoại).
    await owner.getByRole('link', { name: 'Tạo lớp học mới' }).click();
    await owner.waitForURL(/\/classes\/new$/, { timeout: 10000 });
    await owner.getByRole('heading', { name: 'Tạo lớp học', level: 1 }).waitFor({ timeout: 10000 });
    if (await owner.getByRole('button', { name: 'Mở menu tài khoản' }).count()) throw new Error('trang tạo lớp toàn màn hình vẫn hiện thanh điều hướng');
    const { slug, classId } = await createClassViaUI(owner, { title: CLASS.title, desc: CLASS.desc, category: CLASS.category });
    if (slug !== CLASS.slug) throw new Error(`slug máy chủ sinh từ "${CLASS.title}" là ${slug}, mong đợi ${CLASS.slug}`);
    CLASS.slug = slug;
    state.classId = classId;
    await owner.getByTestId('class-category').filter({ hasText: CLASS.category }).waitFor({ timeout: 5000 });
    persist();
    return `slug=${slug} classId=${classId}`;
  });
  await step('J1', 'create a PAID class with a cover image via /classes/new: class page shows the cover, category and price', owner, async () => {
    const { slug, classId } = await createClassViaUI(owner, {
      title: PAID_CLASS.title, desc: PAID_CLASS.desc, category: PAID_CLASS.category, paid: { price: PAID_CLASS.price },
      cover: path.join(FIXTURES, 'cover.png'),
    });
    // slug bỏ dấu tiếng Việt: "E2E Lớp Có Phí <run>" -> e2e-lop-co-phi-<run>
    if (slug !== `e2e-lop-co-phi-${RUN}`) throw new Error(`slug ${slug}`);
    const img = owner.getByRole('img', { name: `Ảnh bìa lớp học ${PAID_CLASS.title}` });
    await img.waitFor({ timeout: 10000 });
    await owner.waitForFunction((alt) => { const el = Array.from(document.images).find((i) => i.alt === alt); return el && el.complete && el.naturalWidth > 0; }, `Ảnh bìa lớp học ${PAID_CLASS.title}`, { timeout: 10000 });
    const natural = await img.evaluate((el) => `${el.naturalWidth}x${el.naturalHeight}`);
    if (natural !== '480x180') throw new Error(`ảnh bìa không phải ảnh đã tải lên: ${natural}`);
    await owner.getByTestId('class-category').filter({ hasText: PAID_CLASS.category }).waitFor({ timeout: 5000 });
    const badge = await owner.getByTestId('badge-paid').innerText();
    if (!badge.includes(`${PAID_CLASS.price}đ`)) throw new Error(`nhãn phí: ${badge}`);
    await shot(owner, 'J1-paid-class-cover');
    return `slug=${slug} classId=${classId} cover=${natural} badge=${badge.replace(/\s+/g, ' ')}`;
  });
  await step('J1', 'back to the first class', owner, async () => {
    await owner.goto(`${BASE}/classes/${CLASS.slug}/feed`);
    await owner.getByRole('heading', { name: CLASS.title, level: 1 }).waitFor({ timeout: 10000 });
  });
  await step('J1', 'F5 keeps session (refresh-cookie bootstrap)', owner, async () => {
    await owner.reload();
    await owner.getByRole('heading', { name: CLASS.title }).waitFor({ timeout: 15000 });
    await owner.getByRole('button', { name: 'Mở menu tài khoản' }).waitFor({ timeout: 10000 });
    await owner.getByRole('link', { name: 'Studio quản trị' }).waitFor({ timeout: 5000 });
  });
  let owner2;
  await step('J1', 'second tab still logged in', owner, async () => {
    owner2 = await ownerCtx.newPage();
    instrument(owner2, 'owner-tab2');
    await owner2.goto(`${BASE}/classes/${CLASS.slug}/feed`);
    await owner2.getByRole('button', { name: 'Mở menu tài khoản' }).waitFor({ timeout: 15000 });
    await owner2.getByRole('link', { name: 'Studio quản trị' }).waitFor({ timeout: 5000 });
  });
  await step('J1', 'classes list shows new class + owner badge', owner, async () => {
    await owner.goto(`${BASE}/classes`);
    // Thẻ lớp mới không còn in slug/nhãn chủ lớp: lớp phải nằm trong rail "Lớp của bạn" (nhận ra là lớp của mình)
    // với lời gọi "Vào lớp" trỏ đúng /classes/<slug>/feed.
    const mine = owner.getByRole('region', { name: 'Lớp của bạn' });
    const card = mine.locator('article', { has: owner.getByRole('heading', { name: CLASS.title, exact: true }) });
    await card.waitFor({ timeout: 10000 });
    const href = await card.getByRole('link', { name: 'Vào lớp' }).getAttribute('href');
    if (href !== `/classes/${CLASS.slug}/feed`) throw new Error(`"Vào lớp" href=${href}`);
    return href;
  });
  await step('J1', 'signed-in user opening /login is sent on, not stranded (R17-01)', owner, async () => {
    await owner.goto(`${BASE}/login`);
    await owner.waitForURL(/\/classes$/, { timeout: 10000 });
  });

  // ---------------- Journey 2: Studio - soạn nội dung ----------------
  const S = () => `${BASE}/studio/classes/${state.classId}`;
  await step('J2', 'studio index redirects to an authorized page', owner, async () => {
    await owner.goto(S());
    await owner.waitForURL(/\/studio\/classes\/[^/]+\/overview/, { timeout: 10000 });
    await settle(owner);
    return owner.url();
  });
  const courseCard = () => owner.locator('article', { has: owner.locator('h3', { hasText: COURSE }) }).first();
  // Mở trình hướng dẫn của khóa vừa tạo từ danh sách ("Chỉnh sửa"; bản nháp mở lại đúng bước đã đi tới), rồi nhảy tới bước n.
  const openWizardStep = async (n) => {
    await owner.goto(`${S()}/courses`);
    await courseCard().getByRole('link', { name: /Chỉnh sửa/ }).click();
    await owner.getByLabel(/Tên khóa học/).or(owner.getByRole('heading', { name: /Thêm bài học|Chọn cách bán|Xem trước & xuất bản/ })).first().waitFor({ timeout: 10000 });
    const stepper = owner.getByRole('navigation', { name: 'Các bước tạo khóa học' }).getByRole('button');
    if ((await stepper.nth(n - 1).getAttribute('aria-current')) !== 'step') await stepper.nth(n - 1).click();
    await settle(owner, 400);
  };
  await step('J2', 'create course (wizard step 1 -> "Lưu nháp")', owner, async () => {
    await owner.goto(`${S()}/courses`);
    await owner.getByRole('link', { name: 'Tạo khóa học' }).first().click();
    await owner.getByLabel(/Tên khóa học/).fill(COURSE);
    await owner.getByLabel(/Mô tả ngắn/).fill('Khoa hoc E2E');
    await owner.getByRole('button', { name: 'Lưu nháp' }).click();
    await owner.getByText(/Đã lưu lúc/).waitFor({ timeout: 10000 });
    await owner.goto(`${S()}/courses`);
    await owner.locator('h3', { hasText: COURSE }).waitFor({ timeout: 10000 });
    if (!(await courseCard().getByRole('link', { name: /Chỉnh sửa/ }).isVisible())) throw new Error('no "Chỉnh sửa" link on the new course');
    for (const gone of ['Thêm chương', 'Xem bài học', 'Xuất bản', 'Lưu trữ', 'Xóa']) {
      if (await courseCard().getByRole('button', { name: gone, exact: true }).count()) throw new Error(`"${gone}" should no longer be on the list`);
    }
  });
  await step('J2', 'create category in wizard step 3', owner, async () => {
    await openWizardStep(3);
    await owner.getByRole('button', { name: 'Thêm danh mục' }).click();
    await owner.getByLabel('Tên danh mục mới').fill(SECTION);
    await owner.getByRole('button', { name: 'Lưu danh mục' }).click();
    await owner.getByText(`Danh mục 1: ${SECTION}`).waitFor({ timeout: 8000 });
  });
  await step('J2', 'create VIDEO lesson, upload mp4 and save it', owner, async () => {
    await owner.getByRole('button', { name: 'Thêm bài học', exact: true }).click();
    await owner.getByLabel('Tên bài học mới').fill(LESSON);
    await owner.getByRole('button', { name: 'Tạo bài' }).click();
    await owner.getByLabel(/Video bài học/).waitFor({ timeout: 8000 });
    const [uploadResp] = await Promise.all([
      owner.waitForResponse((r) => r.request().method() === 'PUT' && new RegExp(`:${MINIO_PORT}/`).test(r.url()), { timeout: 20000 }),
      owner.getByLabel(/Video bài học/).setInputFiles(path.join(FIXTURES, 'tiny.mp4')),
    ]);
    state.uploadHost = new URL(uploadResp.url()).origin;
    if (uploadResp.status() >= 300) throw new Error(`MinIO PUT ${uploadResp.status()}`);
    await owner.getByText(/Đã tải tệp/).waitFor({ timeout: 15000 });
    await owner.getByRole('button', { name: 'Lưu bài học' }).click();
    await owner.getByText(/Đã lưu lúc/, { exact: false }).first().waitFor({ timeout: 10000 });
    return `uploadHost=${state.uploadHost}`;
  });
  await step('J2', 'lesson is in the tree and still there after a reload', owner, async () => {
    await owner.getByRole('button', { name: LESSON, exact: true }).waitFor({ timeout: 4000 });
    await owner.reload();
    await owner.getByRole('button', { name: LESSON, exact: true }).waitFor({ timeout: 10000 });
    await owner.getByRole('button', { name: LESSON, exact: true }).click();
    await owner.getByText('Có video').waitFor({ timeout: 8000 });
  });
  await step('J2', 'publish course from wizard step 4', owner, async () => {
    await owner.getByRole('button', { name: /Tiếp tục: Kiểm tra/ }).click();
    await owner.getByRole('heading', { name: 'Xem trước & xuất bản' }).waitFor({ timeout: 8000 });
    await owner.getByRole('button', { name: 'Xuất bản', exact: true }).click();
    await owner.getByText(/Khóa học đã được xuất bản/).waitFor({ timeout: 10000 });
    if (await owner.getByRole('button', { name: 'Xuất bản', exact: true }).count()) throw new Error('Xuất bản still offered after publish');
    await owner.getByRole('button', { name: 'Ngừng xuất bản' }).waitFor({ timeout: 5000 });
    await owner.goto(`${S()}/courses`);
    await courseCard().getByText('Đã đăng', { exact: true }).waitFor({ timeout: 10000 });
  });

  const examCard = () => owner.locator('article', { has: owner.locator('h3', { hasText: EXAM }) }).first();
  // Bảng soạn đề in số câu dạng <dt>Số câu hỏi</dt><dd>n</dd>.
  const questionCount = (n) => examCard().locator('div', { has: owner.locator('dt', { hasText: 'Số câu hỏi' }) }).locator('dd', { hasText: new RegExp(`^${n}$`) });
  await step('J2', 'create exam', owner, async () => {
    await owner.goto(`${S()}/exams`);
    await owner.getByRole('button', { name: 'Tạo kỳ thi mới' }).click();
    await owner.getByLabel('Tên kỳ thi').fill(EXAM);
    await owner.getByLabel('Số lượt làm bài').fill('2');
    await owner.getByLabel('Mô tả kỳ thi').fill('De thi E2E');
    await owner.getByRole('button', { name: 'Tạo kỳ thi', exact: true }).click();
    await owner.locator('h3', { hasText: EXAM }).waitFor({ timeout: 10000 });
  });
  await step('J2', 'add MCQ question', owner, async () => {
    await examCard().getByRole('button', { name: 'Soạn câu hỏi và công bố' }).click();
    await owner.getByLabel('Nội dung câu hỏi', { exact: true }).fill('2 + 2 = ?');
    await owner.getByLabel('Loại câu hỏi', { exact: true }).selectOption('MULTIPLE_CHOICE');
    await owner.getByLabel('Lựa chọn A', { exact: true }).fill('3');
    await owner.getByLabel('Lựa chọn B', { exact: true }).fill('4');
    await owner.getByLabel('Đáp án đúng', { exact: true }).selectOption('B');
    await owner.getByRole('button', { name: 'Thêm câu hỏi' }).click();
    await questionCount(1).waitFor({ timeout: 10000 });
  });
  await step('J2', 'added question listed in authoring panel (R17-03)', owner, async () => {
    await examCard().getByText('1. 2 + 2 = ?').waitFor({ timeout: 4000 });
  });
  await step('J2', 'add ESSAY question', owner, async () => {
    await owner.getByLabel('Nội dung câu hỏi', { exact: true }).fill('Giai thich vi sao 2 + 2 = 4');
    await owner.getByLabel('Loại câu hỏi', { exact: true }).selectOption('ESSAY');
    await owner.getByRole('button', { name: 'Thêm câu hỏi' }).click();
    await questionCount(2).waitFor({ timeout: 10000 });
    await examCard().getByText('2. Giai thich vi sao 2 + 2 = 4').waitFor({ timeout: 4000 });
  });
  await step('J2', 'publish exam', owner, async () => {
    await examCard().getByRole('button', { name: 'Công bố' }).click();
    await examCard().getByRole('button', { name: 'Đóng kỳ thi' }).waitFor({ timeout: 10000 });
    await examCard().getByText('Đã đăng', { exact: true }).waitFor({ timeout: 5000 });
  });

  await step('J2', 'create product (no target course)', owner, async () => {
    await owner.goto(`${S()}/store`);
    await owner.getByRole('button', { name: 'Tạo gói sản phẩm mới' }).click();
    await owner.getByLabel('Tên sản phẩm').fill(PRODUCT);
    await owner.getByLabel('Mô tả sản phẩm').fill('Goi E2E');
    await owner.getByRole('button', { name: 'Tạo sản phẩm' }).click();
    await owner.getByText(PRODUCT).first().waitFor({ timeout: 10000 });
  });
  await step('J2', 'publish product', owner, async () => {
    await owner.getByRole('button', { name: 'Xuất bản' }).first().click();
    await settle(owner);
    if (await owner.getByRole('button', { name: 'Xuất bản' }).count()) throw new Error('Xuất bản button still visible after publish');
    return (await owner.locator('main').innerText()).match(/Đã đăng|PUBLISHED|Đang bán|ĐANG BÁN/i)?.[0] || 'status text not found';
  });
  await step('J2', 'upload + create document (Studio Documents)', owner, async () => {
    await owner.goto(`${S()}/documents`);
    await owner.getByLabel('Tên tài liệu').fill(DOC);
    await owner.getByLabel('Tệp').setInputFiles(path.join(FIXTURES, 'tiny.pdf'));
    await owner.getByText(/Tệp đã tải lên và sẵn sàng đính kèm: tiny\.pdf\./).waitFor({ timeout: 15000 });
    await owner.getByRole('button', { name: 'Tạo tài liệu' }).click();
    await owner.getByText('Đã tạo tài liệu.').waitFor({ timeout: 10000 });
  });

  // ---------------- Journey 3: học viên ----------------
  const stuCtx = await newCtx(browser);
  const stu = await stuCtx.newPage();
  instrument(stu, 'student');
  const C = () => `${BASE}/classes/${CLASS.slug}`;
  await step('J3', 'register student via UI', stu, async () => { await register(stu, STUDENT); });
  await step('J3', 'join class via UI (immediately, no waiting for the auth bootstrap; R17-01)', stu, async () => {
    await stu.goto(`${C()}/feed`);
    await stu.getByRole('button', { name: 'Tham gia lớp ngay' }).click();
    await stu.getByPlaceholder('Tiêu đề bài viết...').waitFor({ timeout: 10000 });
    if (/\/login/.test(stu.url())) throw new Error(`redirected to ${stu.url()}`);
  });
  await step('J3', 'post in feed', stu, async () => {
    await stu.getByPlaceholder('Tiêu đề bài viết...').fill(`Hello ${RUN}`);
    await stu.getByLabel('Nội dung bài viết mới').fill('Xin chao ca lop, day la bai viet E2E.');
    await stu.getByRole('button', { name: 'Đăng bài' }).click();
    await stu.getByText(`Hello ${RUN}`).first().waitFor({ timeout: 10000 });
    await stu.reload();
    await stu.getByText(`Hello ${RUN}`).first().waitFor({ timeout: 10000 });
  });
  await step('J3', 'open lesson; video loads from MinIO (presigned GET + CSP media-src)', stu, async () => {
    await stu.goto(`${C()}/learn`);
    // Thẻ khóa nổi bật có nút chính "Vào học" (hoặc "Học tiếp") dẫn thẳng vào bài.
    await stu.getByRole('button', { name: /^(Vào học|Học tiếp)$/ }).first().click();
    await stu.waitForURL(/\/learn\/lessons\//, { timeout: 10000 });
    await stu.locator('video').waitFor({ timeout: 10000 });
    await stu.waitForFunction(() => { const v = document.querySelector('video'); return v && (v.readyState >= 2 || v.error); }, null, { timeout: 15000 }).catch(() => {});
    const info = await stu.evaluate(() => { const v = document.querySelector('video'); return { src: v.currentSrc.replace(/\?.*/, ''), readyState: v.readyState, networkState: v.networkState, error: v.error && v.error.code, duration: v.duration }; });
    state.video = info;
    if (info.error || info.readyState < 1) throw new Error(`video not loaded ${JSON.stringify(info)}`);
    await shot(stu, 'J3-lesson-video');
    return JSON.stringify(info);
  });
  await step('J3', 'mark lesson complete', stu, async () => {
    await stu.getByRole('button', { name: 'Đánh dấu hoàn thành' }).click();
    await stu.getByRole('button', { name: 'Đã hoàn thành' }).waitFor({ timeout: 8000 });
  });
  const YT_LESSON = `Bai YouTube ${RUN}`;
  await step('J3', 'owner adds a lesson with a YouTube link in the wizard (tab YouTube, live preview, save)', owner, async () => {
    await openWizardStep(3);
    await owner.getByRole('button', { name: 'Thêm bài học', exact: true }).click();
    await owner.getByLabel('Tên bài học mới').fill(YT_LESSON);
    await owner.getByRole('button', { name: 'Tạo bài' }).click();
    await owner.getByRole('tab', { name: 'YouTube' }).click();
    await owner.getByLabel(/Liên kết video YouTube/).fill('https://youtu.be/dQw4w9WgXcQ?si=e2e');
    await owner.getByTitle('Xem trước video YouTube').waitFor({ timeout: 5000 });
    const [put] = await Promise.all([
      owner.waitForResponse((r) => r.request().method() === 'PUT' && /\/lessons\/[^/]+$/.test(r.url()), { timeout: 10000 }),
      owner.getByRole('button', { name: 'Lưu bài học' }).click(),
    ]);
    if (put.status() !== 200) throw new Error(`PUT lesson -> ${put.status()} ${await put.text()}`);
    state.ytLessonId = new URL(put.url()).pathname.split('/').pop();
    await owner.getByText(/Đã lưu lúc/).first().waitFor({ timeout: 8000 });
    await owner.getByText('Có video · YouTube').waitFor({ timeout: 5000 });
  });
  await step('J3', 'learner page embeds the YouTube video (youtube-nocookie iframe, sandboxed, titled) with a new-tab fallback', stu, async () => {
    await stu.goto(`${C()}/learn/lessons/${state.ytLessonId}`);
    const frame = stu.locator('iframe[title^="Video bài học"]');
    await frame.waitFor({ timeout: 10000 });
    const src = await frame.getAttribute('src');
    if (!/^https:\/\/www\.youtube-nocookie\.com\/embed\/dQw4w9WgXcQ$/.test(src || '')) throw new Error(`iframe src ${src}`);
    const sandbox = await frame.getAttribute('sandbox');
    if (!sandbox || /allow-top-navigation|allow-forms/.test(sandbox)) throw new Error(`sandbox ${sandbox}`);
    await stu.getByRole('link', { name: /Mở trong tab mới/ }).waitFor({ timeout: 3000 });
    if (await stu.locator('video').count()) throw new Error('a <video> is rendered next to the embed');
    await shot(stu, 'J3-lesson-youtube');
    return src;
  });
  await step('J3', 'documents tab: download works', stu, async () => {
    await stu.goto(`${C()}/documents`);
    await stu.getByText(DOC).first().waitFor({ timeout: 10000 });
    const [dl] = await Promise.all([
      stu.waitForEvent('download', { timeout: 15000 }),
      stu.locator('li', { hasText: DOC }).getByRole('button', { name: 'Tải xuống' }).click(),
    ]);
    const p = await dl.path();
    const size = fs.statSync(p).size;
    const head = fs.readFileSync(p).subarray(0, 5).toString('latin1');
    if (head !== '%PDF-') throw new Error(`downloaded file is not the PDF: ${head} size=${size}`);
    return `downloaded ${dl.suggestedFilename()} ${size}B`;
  });
  await step('J3', 'exam: start attempt', stu, async () => {
    await stu.goto(`${C()}/exams`);
    await stu.getByText(EXAM).first().waitFor({ timeout: 10000 });
    await stu.getByRole('link', { name: /^(Làm bài|Làm tiếp)$/ }).first().click();
    await stu.getByRole('button', { name: 'Bắt đầu làm bài' }).click();
    await stu.getByText('2 + 2 = ?').waitFor({ timeout: 10000 });
  });
  await step('J3', 'exam: answer + autosave', stu, async () => {
    await stu.locator('label', { hasText: '4' }).filter({ has: stu.locator('input[type=radio]') }).last().click();
    await stu.getByText('Đã tự động lưu').waitFor({ timeout: 8000 });
    await stu.getByLabel('Câu trả lời tự luận cho câu hỏi 2').fill('Vi hai cong hai bang bon - E2E essay');
    await stu.waitForTimeout(300);
    await stu.getByText('Đã tự động lưu').waitFor({ timeout: 8000 });
    await stu.waitForTimeout(800);
  });
  await step('J3', 'exam: F5 mid-exam resumes same attempt with answers', stu, async () => {
    const before = await stu.getByRole('timer', { name: 'Thời gian còn lại' }).innerText().catch(() => '');
    await stu.reload();
    await stu.getByText('2 + 2 = ?').waitFor({ timeout: 10000 });
    if (await stu.getByRole('button', { name: 'Bắt đầu làm bài' }).isVisible()) throw new Error('reload showed start screen instead of resuming');
    const checked = await stu.locator('input[type=radio]:checked').getAttribute('value');
    const essay = await stu.getByLabel('Câu trả lời tự luận cho câu hỏi 2').inputValue();
    if (checked !== 'B' || !essay.includes('E2E essay')) throw new Error(`answers not restored radio=${checked} essay=${essay}`);
    return `timer before=${before}`;
  });
  await step('J3', 'exam: submit -> result page', stu, async () => {
    await stu.getByRole('button', { name: 'Hoàn tất & nộp bài' }).click();
    // Nộp bài giờ qua hộp thoại xác nhận (nêu số câu đã trả lời).
    const confirm = stu.getByRole('alertdialog', { name: 'Nộp bài và kết thúc lượt làm?' });
    await confirm.getByText('2/2').waitFor({ timeout: 5000 });
    await confirm.getByRole('button', { name: 'Xác nhận nộp bài' }).click();
    await stu.waitForURL(/\/result\?attemptId=/, { timeout: 15000 });
    await settle(stu);
    await shot(stu, 'J3-result-before-grading');
    return (await stu.locator('main').innerText()).replace(/\s+/g, ' ').slice(0, 300);
  });
  await step('J3', 'exam list shows exactly 1 attempt used', stu, async () => {
    await stu.goto(`${C()}/exams`);
    await stu.getByText(EXAM).first().waitFor({ timeout: 10000 });
    const txt = (await stu.locator('main').innerText()).replace(/\s+/g, ' ');
    const m = txt.match(/Lượt làm (\d+)\/2/);
    if (!m || m[1] !== '1') throw new Error(`attempt count text: ${m && m[0]}`);
    return m[0];
  });

  // ---------------- Journey 4: chấm bài + duyệt mọi trang ----------------
  await step('J4', 'grade essay in Studio Grading', owner, async () => {
    await owner.goto(`${S()}/grading`);
    await owner.getByRole('button', { name: new RegExp(EXAM) }).first().click();
    await owner.getByText('Vi hai cong hai bang bon - E2E essay').waitFor({ timeout: 10000 });
    await owner.getByLabel('Điểm', { exact: true }).fill('8');
    await owner.getByLabel('Phản hồi', { exact: true }).fill('Tot');
    await owner.getByRole('button', { name: 'Lưu điểm' }).click();
    await owner.getByText(/Đã công bố · Điểm:/).waitFor({ timeout: 10000 });
    return (await owner.getByText(/Đã công bố · Điểm:/).innerText());
  });
  await step('J4', 'student result page shows graded score', stu, async () => {
    await stu.goto(`${C()}/exams`);
    await stu.getByRole('link', { name: 'Xem kết quả' }).first().click();
    await settle(stu);
    await shot(stu, 'J4-result-after-grading');
    const txt = (await stu.locator('main').innerText()).replace(/\s+/g, ' ');
    if (!/90|18\s*\/\s*20/.test(txt)) throw new Error(`result text lacks expected 90%/18/20: ${txt.slice(0, 300)}`);
    return txt.slice(0, 200);
  });
  await step('J4', 'leaderboard includes student', stu, async () => {
    await stu.goto(`${C()}/leaderboard`);
    await settle(stu, 1200);
    await shot(stu, 'J4-leaderboard');
    const txt = (await stu.locator('main').innerText()).replace(/\s+/g, ' ');
    if (!txt.includes(STUDENT.name)) throw new Error(`leaderboard lacks student: ${txt.slice(0, 300)}`);
    return 'student listed';
  });
  // Mọi trang phải render không có [role=alert] và không phát sinh lỗi console/CSP/mạng (trừ nhiễu đã biết: xem unexpectedEvents trong lib.js).
  for (const p of ['overview', 'members', 'settings', 'leaderboard', 'staff', 'segments', 'audit', 'about', 'feed', 'blog', 'events', 'documents', 'store', 'courses', 'exams', 'grading']) {
    await step('J4', `studio page renders: ${p}`, owner, async () => {
      const before = events.length;
      await owner.goto(`${S()}/${p}`);
      await settle(owner, 800);
      const alerts = await owner.locator('main [role=alert]').allInnerTexts();
      const newEv = unexpectedEvents(events, before);
      await shot(owner, `J4-studio-${p}`);
      if (alerts.length) throw new Error(`alert: ${alerts.join(' / ').slice(0, 200)}`);
      if (newEv.length) throw new Error(`events: ${newEv.map((e) => e.kind + ' ' + e.text).join(' / ').slice(0, 300)}`);
    });
  }
  for (const p of ['blog', 'feed', 'learn', 'exams', 'events', 'documents', 'members', 'store', 'leaderboard', 'about']) {
    await step('J4', `student tab renders: ${p}`, stu, async () => {
      const before = events.length;
      await stu.goto(`${C()}/${p}`);
      await settle(stu, 800);
      const alerts = await stu.locator('main [role=alert]').allInnerTexts();
      const newEv = unexpectedEvents(events, before);
      if (alerts.length) throw new Error(`alert: ${alerts.join(' / ').slice(0, 200)}`);
      if (newEv.length) throw new Error(`events: ${newEv.map((e) => e.kind + ' ' + e.text).join(' / ').slice(0, 300)}`);
    });
  }

  // ---------------- Journey 5: đăng xuất giữa nhiều tab ----------------
  await step('J5', 'logout in tab1 -> tab2 logged out on next action', owner2, async () => {
    await owner2.goto(`${C()}/feed`);
    await owner2.getByRole('button', { name: 'Mở menu tài khoản' }).waitFor({ timeout: 10000 });
    await owner.goto(`${C()}/feed`);
    await owner.getByRole('button', { name: 'Mở menu tài khoản' }).click();
    await owner.getByRole('menuitem', { name: 'Đăng xuất' }).click();
    await owner.waitForURL(/\/login/, { timeout: 10000 });
    await owner2.waitForTimeout(800);
    const immediately = !(await isLoggedIn(owner2));
    // Hành động kế tiếp ở tab 2: mở tab Thi nếu còn là liên kết (vẫn đăng nhập), còn khi đã mất phiên thì tab Thi
    // bị khóa với khách (không phải liên kết) - mở tab Giới thiệu (luôn mở với khách) để vẫn có một điều hướng thật.
    const examsTab = owner2.getByRole('link', { name: 'Thi', exact: true });
    if (await examsTab.count()) await examsTab.click();
    else await owner2.getByRole('link', { name: 'Giới thiệu', exact: true }).click();
    await settle(owner2);
    const loggedIn = await isLoggedIn(owner2);
    if (loggedIn) throw new Error('tab2 still logged in after tab1 logout');
    // tải lại hẳn cũng không được "hồi sinh" phiên
    await owner2.reload();
    await settle(owner2);
    if (await isLoggedIn(owner2)) throw new Error('tab2 logged in again after reload (refresh cookie not revoked)');
    return `clearedImmediately=${immediately}`;
  });
  await step('J5', 'login again', owner, async () => {
    await login(owner, OWNER);
    await owner.goto(`${C()}/feed`);
    await owner.getByRole('link', { name: 'Studio quản trị' }).waitFor({ timeout: 10000 });
  });

  // ---------------- Journey 6: giao diện điện thoại (390px) ----------------
  const phone = { viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2 };
  const mobCtx = await newCtx(browser, phone);
  const mob = await mobCtx.newPage();
  instrument(mob, 'mobile-student');
  await step('J6', 'mobile login student', mob, async () => { await login(mob, STUDENT); });
  const mobilePages = [
    ['classes', `${BASE}/classes`],
    ['blog', `${C()}/blog`],
    ['feed', `${C()}/feed`],
    ['events', `${C()}/events`],
    ['learn', `${C()}/learn`],
    ['exams', `${C()}/exams`],
    ['leaderboard', `${C()}/leaderboard`],
    ['documents', `${C()}/documents`],
    ['store', `${C()}/store`],
    ['profile', `${BASE}/me/profile`],
  ];
  state.mobile = {};
  for (const [name, url] of mobilePages) {
    await step('J6', `mobile ${name}`, mob, async () => {
      await mob.goto(url);
      await settle(mob, 800);
      const o = await overflowCheck(mob);
      state.mobile[name] = o;
      await mob.screenshot({ path: path.join(SHOTS, `J6-mobile-${name}.png`) });
      if (o.over > 1) throw new Error(`horizontal overflow ${o.over}px: ${o.offenders.join(' ; ')}`);
      return `sw=${o.scrollWidth}`;
    });
  }
  await step('J6', 'mobile lesson page', mob, async () => {
    await mob.goto(`${C()}/learn`);
    await mob.getByRole('button', { name: /^(Vào học|Học tiếp)$/ }).first().click();
    // "Học tiếp" lands on the next unfinished lesson, which is the YouTube one: an iframe instead of a <video>.
    await mob.locator('video, iframe[title^="Video bài học"]').first().waitFor({ timeout: 10000 });
    await settle(mob);
    const o = await overflowCheck(mob);
    await mob.screenshot({ path: path.join(SHOTS, 'J6-mobile-lesson.png') });
    if (o.over > 1) throw new Error(`horizontal overflow ${o.over}px: ${o.offenders.join(' ; ')}`);
  });
  const mobOwnerCtx = await newCtx(browser, phone);
  const mobO = await mobOwnerCtx.newPage();
  instrument(mobO, 'mobile-owner');
  await step('J6', 'mobile login owner', mobO, async () => { await login(mobO, OWNER); });
  for (const p of ['courses', 'exams', 'grading', 'store', 'members', 'blog', 'events']) {
    await step('J6', `mobile studio ${p}`, mobO, async () => {
      await mobO.goto(`${S()}/${p}`);
      await settle(mobO, 800);
      const o = await overflowCheck(mobO);
      await mobO.screenshot({ path: path.join(SHOTS, `J6-mobile-studio-${p}.png`) });
      const mainTop = await mobO.locator('main').last().evaluate((el) => Math.round(el.getBoundingClientRect().top + window.scrollY));
      state.mobile[`studio-${p}`] = { ...o, mainTop };
      if (o.over > 1) throw new Error(`horizontal overflow ${o.over}px: ${o.offenders.join(' ; ')}`);
      // R17-05: the Studio nav collapses on phones, so the page content starts near the top.
      if (mainTop > 400) throw new Error(`studio content starts too low on a phone: y=${mainTop}px`);
      return `studio main content starts at y=${mainTop}px`;
    });
  }

  await browser.close();
  fs.writeFileSync(path.join(SHOTS, 'report.json'), JSON.stringify({ run: RUN, owner: OWNER.email, student: STUDENT.email, classSlug: CLASS.slug, state, results, events }, null, 2));
  console.log(`\nẢnh chụp + report.json: ${SHOTS}`);
  process.exitCode = summarize('E2E hành trình chính (e2e.js)');
})().catch((e) => { console.error('FATAL', e); process.exit(2); });
