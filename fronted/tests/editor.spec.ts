import { expect, test, type Page } from '@playwright/test';
import { profile } from '../src/test/fixtures';
import { emptyForm } from '../src/editor/model';

const imageId = 'aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa';
const content = { schemaVersion: 1, doc: { type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: '已有正文' }] }] } };
const edit = { ...emptyForm(), articleId: '12', title: '已有标题', content, createdAt: '', updatedAt: '', publishedAt: '' };
async function setup(page: Page) {
  await page.route('**/api/users/me', (route) => route.fulfill({ json: profile }));
  await page.route('**/api/auth/csrf', (route) => route.fulfill({ json: { token: 'masked', headerName: 'X-CSRF-TOKEN' } }));
  await page.route('**/api/articles/feed?**', (route) => route.fulfill({ json: { items: [], nextCursor: null } }));
  await page.route('**/api/articles/12/edit', (route) => route.fulfill({ json: edit }));
  await page.route('**/test-image.png', (route) => route.fulfill({ path: 'src/assets/home-banner.png', contentType: 'image/png' }));
}
async function fill(page: Page) {
  await page.getByRole('textbox', { name: '文章标题' }).fill('新文章');
  await page.getByRole('textbox', { name: '文章正文' }).fill('正文内容');
}
async function uploads(page: Page, fail = false) {
  await page.route('**/api/media/uploads', (route) => route.fulfill({ status: 201, json: { mediaId: imageId, publicUrl: '/test-image.png', upload: {
    method: 'POST', url: 'http://127.0.0.1:5175/test-upload', fields: { key: 'signed-object', policy: 'policy' }, expiresAt: '',
  } } }));
  await page.route('**/test-upload', (route) => route.fulfill({ json: { success: !fail, stage: fail ? 'MODERATION' : 'COMPLETE', code: fail ? 'MODERATION_REJECTED' : 'SUCCESS' } }));
}

test('空表单校验、成功留页转编辑，后续保存携带原 ID', async ({ page }, info) => {
  await setup(page);
  const bodies: Record<string, unknown>[] = [];
  await page.route('**/api/articles', async (route) => {
    bodies.push(route.request().postDataJSON()); expect(route.request().headers()['x-csrf-token']).toBe('masked');
    await route.fulfill({ json: { articleId: '12' } });
  });
  await page.goto('/articles/new');
  await expect(page.getByRole('search')).toHaveCount(0);
  await page.getByRole('button', { name: '发布文章', exact: true }).click();
  await expect(page.getByText('请输入文章标题', { exact: true })).toBeVisible();
  await expect(page.getByRole('textbox', { name: '文章标题' })).toBeFocused(); expect(bodies).toHaveLength(0);
  await fill(page); await page.getByRole('button', { name: '发布文章', exact: true }).click();
  await expect(page.getByRole('dialog', { name: '发布成功' })).toBeVisible(); await expect(page).toHaveURL(/\/articles\/12\/edit$/);
  await page.getByRole('button', { name: '继续编辑', exact: true }).click();
  await expect(page.getByRole('textbox', { name: '文章标题' })).toHaveValue('新文章');
  await page.getByRole('textbox', { name: '文章正文' }).fill('修改后的正文');
  await page.getByRole('button', { name: '保存修改' }).click(); await expect(page.getByRole('dialog', { name: '保存成功' })).toBeVisible();
  expect(bodies).toHaveLength(2); expect(bodies[0]).not.toHaveProperty('articleId'); expect(bodies[1].articleId).toBe('12');
  await page.screenshot({ path: info.outputPath('editor-save-success.png') });
});

test('提交互斥，结果未知统一失败，手动重试保留内容', async ({ page }) => {
  await setup(page); let writes = 0;
  let release!: () => void;
  const pending = new Promise<void>((resolve) => { release = resolve; });
  await page.route('**/api/articles', async (route) => { writes++; if (writes === 1) { await pending; await route.fulfill({ status: 503, json: { code: 'ARTICLE_WRITE_RESULT_UNKNOWN' } }); } else await route.fulfill({ json: { articleId: '12' } }); });
  await page.goto('/articles/new'); await fill(page);
  await page.getByRole('button', { name: '发布文章', exact: true }).click();
  await expect(page.getByRole('dialog', { name: '发布中…' })).toBeVisible(); await expect(page.getByRole('textbox', { name: '文章标题' })).toBeDisabled();
  await page.keyboard.press('Escape'); await expect(page.getByRole('dialog')).toBeVisible(); expect(writes).toBe(1);
  release(); await expect(page.getByRole('dialog', { name: '发布失败' })).toBeVisible(); expect(writes).toBe(1);
  await page.getByRole('button', { name: '重新发布' }).click(); await expect(page.getByRole('dialog', { name: '发布成功' })).toBeVisible(); expect(writes).toBe(2);
});

for (const [status, code, heading] of [[404, 'ARTICLE_NOT_FOUND', '文章不存在'], [403, 'ARTICLE_FORBIDDEN', '没有编辑权限'], [409, 'ARTICLE_HIDDEN', '文章已删除'], [503, 'DEPENDENCY_UNAVAILABLE', '文章加载失败']] as const) {
  test(`读取状态：${heading}`, async ({ page }) => {
    await setup(page); await page.route('**/api/articles/12/edit', (route) => route.fulfill({ status, json: { code } }));
    await page.goto('/articles/12/edit'); await expect(page.getByRole('heading', { name: heading })).toBeVisible();
    await expect(page.getByRole('button', { name: '保存修改' })).toHaveCount(0);
  });
}

test('返回和浏览器后退受保护，取消恢复焦点，未修改直接离开', async ({ page }) => {
  await setup(page); await page.goto('/'); await page.getByRole('button', { name: '发布中心' }).click();
  await page.getByRole('textbox', { name: '文章标题' }).fill('尚未保存');
  const back = page.getByRole('link', { name: '返回首页' }); await back.click();
  await expect(page.getByRole('button', { name: '继续编辑', exact: true })).toBeFocused(); await page.keyboard.press('Escape'); await expect(back).toBeFocused();
  const backward = page.goBack({ timeout: 1000 }).catch(() => null);
  await expect(page.getByRole('dialog', { name: '离开编辑页？' })).toBeVisible(); await backward;
  await page.getByRole('button', { name: '放弃修改' }).click(); await expect(page).toHaveURL('http://127.0.0.1:5175/');
  await page.getByRole('button', { name: '发布中心' }).click(); await page.getByRole('link', { name: '返回首页' }).click(); await expect(page.getByRole('dialog')).toHaveCount(0);
});

test('会话过期先暂存，同账号登录后恢复原文且不自动发布', async ({ page }) => {
  await setup(page); let expired = false, writes = 0;
  await page.route('**/api/users/me', (route) => expired ? route.fulfill({ status: 401, json: { code: 'AUTHENTICATION_REQUIRED' } }) : route.fulfill({ json: profile }));
  await page.route('**/api/articles', (route) => { writes++; expired = true; return route.fulfill({ status: 401, json: { code: 'AUTHENTICATION_REQUIRED' } }); });
  await page.route('**/api/auth/login', (route) => { expired = false; return route.fulfill({ json: profile }); });
  await page.goto('/articles/new'); await fill(page); await page.getByRole('button', { name: '发布文章', exact: true }).click();
  await expect(page).toHaveURL(/\/login$/); expect(await page.evaluate(() => !!sessionStorage.getItem('ec:article-editor:recovery:v1'))).toBe(true);
  await page.getByLabel('账号', { exact: true }).fill('Alice_1'); await page.getByLabel('密码', { exact: true }).fill('password123'); await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page).toHaveURL(/\/articles\/new$/); await expect(page.getByRole('textbox', { name: '文章标题' })).toHaveValue('新文章');
  await expect(page.getByRole('textbox', { name: '文章正文' })).toContainText('正文内容'); expect(writes).toBe(1);
  expect(await page.evaluate(() => sessionStorage.getItem('ec:article-editor:recovery:v1'))).toBeNull();
});

test('封面更换失败保留旧图，正文图片上传后只提交文件身份', async ({ page }) => {
  await setup(page); await uploads(page);
  await page.route('**/api/articles/12/edit', (route) => route.fulfill({ json: { ...edit, coverMediaId: imageId, coverUrl: '/test-image.png' } }));
  let body: Record<string, any> = {};
  await page.route('**/api/articles', (route) => { body = route.request().postDataJSON(); return route.fulfill({ json: { articleId: '12' } }); });
  await page.goto('/articles/12/edit');
  await page.getByLabel('选择正文图片').setInputFiles({ name: 'body.png', mimeType: 'image/png', buffer: Buffer.from('test') });
  await expect(page.locator('.tiptap img')).toHaveCount(1);
  await page.route('**/test-upload', (route) => route.fulfill({ json: { success: false, code: 'MODERATION_REJECTED' } }));
  await page.getByLabel('选择封面图片').setInputFiles({ name: 'cover.png', mimeType: 'image/png', buffer: Buffer.from('test') });
  await expect(page.getByText(/更换失败，保留原封面/)).toBeVisible(); await expect(page.getByAltText('文章封面')).toHaveAttribute('src', '/test-image.png');
  await page.getByRole('button', { name: '保存修改' }).click(); await expect(page.getByRole('dialog', { name: '保存成功' })).toBeVisible();
  expect(body.coverMediaId).toBe(imageId); const image = body.content.doc.content.find((node: any) => node.type === 'image'); expect(image.attrs.fileId).toBe(imageId); expect(image.attrs).not.toHaveProperty('src');
});

test('正文工具栏、代码语言、链接和粘贴拖入共用上传', async ({ page }) => {
  await setup(page); await uploads(page); await page.goto('/articles/new'); await fill(page);
  const body = page.getByRole('textbox', { name: '文章正文' }); await body.press('ControlOrMeta+a'); await page.getByRole('button', { name: '粗体', exact: true }).click();
  await expect(body.locator('strong')).toHaveText('正文内容');
  await page.getByRole('button', { name: '链接', exact: true }).click(); await page.getByLabel('链接地址').fill('https://example.com'); await page.getByRole('button', { name: '应用' }).click(); await expect(body.locator('a')).toHaveAttribute('href', 'https://example.com');
  await body.press('End'); await body.press('Enter');
  for (const name of ['paste', 'drop']) await body.evaluate((element, type) => {
    const transfer = new DataTransfer(); transfer.items.add(new File(['image'], `${type}.png`, { type: 'image/png' }));
    element.dispatchEvent(type === 'paste' ? new ClipboardEvent('paste', { clipboardData: transfer, bubbles: true, cancelable: true })
      : new DragEvent('drop', { dataTransfer: transfer, bubbles: true, cancelable: true, clientX: element.getBoundingClientRect().x + 30, clientY: element.getBoundingClientRect().y + 30 }));
  }, name);
  await expect(body.locator('img')).toHaveCount(2);
});

test('长文自然增高，标题和标签换行，标准桌面几何与底部截图', async ({ page }, info) => {
  await setup(page);
  await page.route('**/api/articles/12/edit', (route) => route.fulfill({ json: { ...edit, title: '记录创作中的灵感与日常'.repeat(8), tags: Array.from({ length: 5 }, (_, index) => ({ tagId: String(index + 1), name: '社区功能体验与长期记录建议' + index })),
    content: { schemaVersion: 1, doc: { type: 'doc', content: Array.from({ length: 35 }, (_, index) => ({ type: 'paragraph', content: [{ type: 'text', text: `第${index + 1}段：让每一次分享都留下值得回看的痕迹。` }] })) } } } }));
  await page.goto('/articles/12/edit'); await page.evaluate(() => document.fonts.ready);
  expect(await page.locator('.article-editor-panel').boundingBox()).toMatchObject({ x: 106.5, y: 183, width: 1227 });
  expect((await page.locator('.article-editor-inner').boundingBox())?.width).toBe(920);
  const before = (await page.locator('.home-header').boundingBox())!.y;
  await page.getByRole('button', { name: '保存修改' }).scrollIntoViewIfNeeded(); expect((await page.locator('.home-header').boundingBox())!.y).toBeLessThan(before);
  expect(await page.locator('.tiptap').evaluate((element) => element.scrollHeight <= element.clientHeight + 2)).toBe(true);
  await page.screenshot({ path: info.outputPath('editor-long-bottom.png') });
});

test('临时存储不可写时保留原页及正文，不提前跳转登录', async ({ page }) => {
  await setup(page);
  await page.addInitScript(() => { Storage.prototype.setItem = () => { throw new DOMException('quota', 'QuotaExceededError'); }; });
  await page.route('**/api/articles', (route) => route.fulfill({ status: 401, json: { code: 'AUTHENTICATION_REQUIRED' } }));
  await page.goto('/articles/new'); await fill(page); await page.getByRole('button', { name: '发布文章', exact: true }).click();
  await page.getByRole('button', { name: '继续编辑', exact: true }).click();
  await expect(page.getByText(/暂时无法保存编辑内容/)).toBeVisible(); await expect(page).toHaveURL(/\/articles\/new$/);
  await expect(page.getByRole('textbox', { name: '文章正文' })).toContainText('正文内容');
  await expect(page.getByRole('button', { name: '发布文章', exact: true })).toBeDisabled();
});

test('异账号登录不恢复原账号暂存，已有文章无权限时不恢复内容', async ({ page }) => {
  await setup(page);
  await page.addInitScript(({ value }) => sessionStorage.setItem('ec:article-editor:recovery:v1', JSON.stringify(value)), { value: {
    version: 1, ownerId: 'another-user', articleId: null, form: { ...emptyForm(), title: '私密旧内容' }, baseline: emptyForm(), savedAt: Date.now(),
  } });
  await page.goto('/articles/new'); await expect(page.getByRole('textbox', { name: '文章标题' })).toHaveValue('');
  expect(await page.evaluate(() => sessionStorage.getItem('ec:article-editor:recovery:v1'))).toBeNull();
  await page.evaluate(({ value }) => sessionStorage.setItem('ec:article-editor:recovery:v1', JSON.stringify(value)), { value: {
    version: 1, ownerId: profile.userId, articleId: '12', form: { ...emptyForm(), title: '不能显示的内容' }, baseline: emptyForm(), savedAt: Date.now(),
  } });
  await page.route('**/api/articles/12/edit', (route) => route.fulfill({ status: 403, json: { code: 'ARTICLE_FORBIDDEN' } }));
  await page.getByRole('link', { name: '返回首页' }).click();
  await page.goto('/articles/12/edit'); await expect(page.getByRole('heading', { name: '没有编辑权限' })).toBeVisible(); await expect(page.getByText('不能显示的内容')).toHaveCount(0);
});

test('上传中阻止发布，失败正文图片需要手动移除，媒体未就绪可人工重试', async ({ page }) => {
  await setup(page); await uploads(page);
  let release!: () => void; const pending = new Promise<void>((resolve) => { release = resolve; });
  await page.route('**/test-upload', async (route) => { await pending; await route.fulfill({ json: { success: false, code: 'MODERATION_REJECTED' } }); });
  await page.goto('/articles/new'); await fill(page); await page.getByLabel('选择正文图片').setInputFiles({ name: 'body.png', mimeType: 'image/png', buffer: Buffer.from('test') });
  await expect(page.getByRole('button', { name: '发布文章', exact: true })).toBeDisabled(); release();
  await expect(page.getByText('图片未通过审核，请更换图片')).toBeVisible(); await expect(page.getByRole('button', { name: '发布文章', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: '移除上传项' }).click(); await expect(page.getByRole('button', { name: '发布文章', exact: true })).toBeEnabled();
  let writes = 0; await page.route('**/api/articles', (route) => { writes++; return writes === 1 ? route.fulfill({ status: 409, json: { code: 'MEDIA_STATE_CONFLICT' } }) : route.fulfill({ json: { articleId: '12' } }); });
  await page.getByRole('button', { name: '发布文章', exact: true }).click(); await expect(page.getByRole('dialog', { name: '发布失败' })).toContainText('图片尚未就绪或不可绑定');
  expect(writes).toBe(1); await page.getByRole('button', { name: '重新发布' }).click(); await expect(page.getByRole('dialog', { name: '发布成功' })).toBeVisible(); expect(writes).toBe(2);
});

test('键盘选择标签达到五项，移除后继续选择；刷新触发原生离开提醒', async ({ page }) => {
  await setup(page);
  await page.route('**/api/tags?**', (route) => route.fulfill({ json: Array.from({ length: 6 }, (_, index) => ({ tagId: String(index + 1), name: `标签${index + 1}` })) }));
  await page.goto('/articles/new'); const input = page.getByRole('combobox', { name: '搜索已有标签' }); await input.fill('标签');
  for (let index = 1; index <= 5; index++) await page.getByRole('option', { name: `标签${index}`, exact: true }).click();
  await expect(input).toBeDisabled(); await page.getByRole('button', { name: '移除标签 标签2' }).click(); await expect(input).toBeEnabled();
  await input.focus(); await expect(page.getByRole('option', { name: '标签2', exact: true })).toBeVisible(); await input.press('ArrowDown'); await input.press('Enter');
  await expect(page.getByText('已选 5/5 · 最多选择 5 个标签')).toBeVisible();
  const native = page.waitForEvent('dialog'); const reloading = page.reload({ timeout: 1000 }).catch(() => null);
  const dialog = await native; expect(dialog.type()).toBe('beforeunload'); await dialog.dismiss(); await reloading;
});

test('短视口外层布局不压缩，空白页面视觉截图', async ({ page }, info) => {
  await setup(page); await page.setViewportSize({ width: 1440, height: 768 }); await page.goto('/articles/new'); await page.evaluate(() => document.fonts.ready);
  expect(await page.locator('.home-header').boundingBox()).toMatchObject({ x: 106.5, y: 34, width: 1227, height: 90 });
  expect(await page.locator('.publish-entry').boundingBox()).toMatchObject({ width: 40, height: 40 });
  expect((await page.locator('.tiptap').boundingBox())!.height).toBeGreaterThanOrEqual(480);
  await page.screenshot({ path: info.outputPath('editor-empty-short.png'), fullPage: true });
});

test('其他窗口切换账号后隐藏旧内容，恢复原账号才继续原编辑会话', async ({ page }) => {
  await setup(page); let other = false;
  await page.route('**/api/users/me', (route) => route.fulfill({ json: other ? { ...profile, userId: '8', username: 'another_user' } : profile }));
  await page.goto('/articles/new'); await fill(page); other = true;
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(page.getByText(/账号已切换/)).toBeVisible(); await expect(page.getByRole('textbox', { name: '文章正文' })).toHaveCount(0);
  other = false; await page.getByRole('button', { name: '重新检查登录状态' }).click();
  await expect(page.getByRole('textbox', { name: '文章正文' })).toContainText('正文内容'); await expect(page.getByRole('textbox', { name: '文章标题' })).toHaveValue('新文章');
});
