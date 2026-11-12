import { readFile, mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { randomUUID } from 'node:crypto';
import assert from 'node:assert/strict';
import { chromium, request, expect } from '@playwright/test';
import { createServer } from 'vite';

/** 显式运行的真实联调：凭证仅在内存中使用，临时账号和文章均通过业务接口清理。 */
const root = resolve(import.meta.dirname, '..');
const backend = 'http://127.0.0.1:8080';
const origin = 'http://127.0.0.1:5177';
const values = new Map();
for (const line of (await readFile(resolve(root, '../backend/.env'), 'utf8')).split(/\r?\n/)) {
  const match = line.match(/^([^#!\s][^=]*)=(.*)$/);
  if (match) values.set(match[1].trim(), match[2].trim().replace(/\\u([0-9a-f]{4})|\\(.)/gi, (_, hex, char) => hex ? String.fromCharCode(parseInt(hex, 16)) : ({ n: '\n', r: '\r', t: '\t' }[char] ?? char)));
}
const adminUsername = process.env.EDITOR_LIVE_ADMIN_USERNAME ?? values.get('ADMIN_USERNAME');
const adminPassword = process.env.EDITOR_LIVE_ADMIN_PASSWORD ?? values.get('ADMIN_PASSWORD');
if (!adminUsername || !adminPassword) throw new Error('需要有效的本机管理员联调凭证；不会输出或保存凭证');
const result = { textRoundTrip: false, uploadProxy: false, mediaBinding: false, bodyImageBinding: false, articleCleaned: false, accountCleaned: false };
const admin = await request.newContext({ baseURL: backend });
let server, browser, context, accountId, articleId;
async function csrf(client) { const response = await client.get('/api/auth/csrf'); assert.equal(response.status(), 200, 'CSRF 读取失败'); const token = await response.json(); return { [token.headerName]: token.token }; }
try {
  const login = await admin.post('/api/auth/login', { headers: await csrf(admin), data: { username: adminUsername, password: adminPassword } });
  assert.equal(login.status(), 200, '本机管理员凭证不可用');
  const username = `editor_it_${Date.now()}`, password = randomUUID();
  const account = await admin.post('/api/admin/users', { headers: await csrf(admin), data: { username, password, role: 'USER' } });
  assert.equal(account.status(), 201, '临时账号创建失败'); accountId = (await account.json()).userId;
  server = await createServer({ root, server: { host: '127.0.0.1', port: 5177, strictPort: true } }); await server.listen();
  browser = await chromium.launch({ headless: true }); context = await browser.newContext({ baseURL: origin, viewport: { width: 1440, height: 1024 } });
  const page = await context.newPage();
  await page.goto(`${origin}/articles/new`); await page.getByLabel('账号', { exact: true }).fill(username); await page.getByLabel('密码', { exact: true }).fill(password);
  await page.getByRole('button', { name: '登录', exact: true }).click(); await expect(page).toHaveURL(`${origin}/articles/new`);
  const text = (value, marks = []) => ({ type: 'text', text: value, ...(marks.length ? { marks: marks.map((type) => ({ type })) } : {}) });
  const paragraph = (value) => ({ type: 'paragraph', content: [text(value)] });
  const doc = { type: 'doc', content: [
    ...[1, 2, 3, 4, 5, 6].map((level) => ({ type: 'heading', attrs: { level }, content: [text(`标题${level}`)] })),
    { type: 'paragraph', content: ['bold', 'italic', 'strike', 'underline', 'code'].map((mark) => text(mark, [mark])).concat([{ type: 'hardBreak' }, { type: 'text', text: '链接', marks: [{ type: 'link', attrs: { href: 'https://example.com/path' } }] }]) },
    { type: 'blockquote', content: [paragraph('引用')] },
    { type: 'orderedList', attrs: { start: 7 }, content: [{ type: 'listItem', content: [paragraph('第七项')] }] },
    { type: 'bulletList', content: [{ type: 'listItem', content: [paragraph('无序项')] }] },
    { type: 'codeBlock', attrs: { language: 'java' }, content: [text('int n = 1;')] }, { type: 'horizontalRule' },
  ] };
  const created = await context.request.post(`${origin}/api/articles`, { headers: await csrf(context.request), data: {
    title: '编辑页临时联调', content: { schemaVersion: 1, doc }, coverMediaId: null, tagIds: [],
  } });
  assert.equal(created.status(), 200, '文章创建失败'); articleId = (await created.json()).articleId;
  const read = async () => { const response = await context.request.get(`${origin}/api/articles/${articleId}/edit`); assert.equal(response.status(), 200, '编辑读取失败'); return response.json(); };
  const before = await read();
  await page.goto(`${origin}/articles/${articleId}/edit`); await page.getByRole('textbox', { name: '文章标题' }).fill('编辑页临时联调：已修改');
  await page.getByRole('button', { name: '保存修改' }).click(); await expect(page.getByRole('dialog', { name: '保存成功' })).toBeVisible();
  await page.getByRole('button', { name: '继续编辑' }).click();
  const after = await read(); assert.deepEqual(after.content, before.content, '正文格式往返不一致'); assert.equal(after.publishedAt, before.publishedAt);
  result.textRoundTrip = true;
  // 现有后台仍使用旧上传端点时，仅联调夹具重定向授权 URL；不修改后端运行配置。
  await page.route('**/api/media/uploads', async (route) => {
    const response = await route.fetch(); const grant = await response.json();
    if (response.status() === 201) grant.upload.url = `${origin}/__media/upload/${values.get('MEDIA_STORAGE_BUCKET') || 'easy-community-dev'}`;
    await route.fulfill({ response, json: grant });
  });
  const uploaded = page.waitForResponse((response) => response.url().startsWith(`${origin}/__media/upload/`));
  await page.getByLabel('选择封面图片').setInputFiles(resolve(root, 'src/assets/home-banner.png'));
  const uploadResponse = await uploaded; result.uploadProxy = uploadResponse.status() === 200 && (await uploadResponse.json()).success === true;
  if (result.uploadProxy) {
    const image = page.getByAltText('文章封面'); await expect(image).toBeVisible();
    // 只在验收脚本中观察异步 Webhook；产品上传函数不等待、不轮询。
    try { await expect.poll(async () => (await context.request.get(await image.getAttribute('src'))).status(), { timeout: 6000 }).toBe(200); }
    catch { /* 发布配置未就绪时记录部分结果，仍清理临时业务数据。 */ }
    await page.getByRole('button', { name: '保存修改' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    try {
      await expect(page.getByRole('dialog', { name: '保存成功' })).toBeVisible({ timeout: 5000 }); result.mediaBinding = !!(await read()).coverMediaId;
      await page.getByRole('button', { name: '继续编辑' }).click();
      if (await page.getByRole('button', { name: '重新加载预览' }).count()) await page.getByRole('button', { name: '重新加载预览' }).click();
      await page.getByLabel('选择正文图片').setInputFiles(resolve(root, 'src/assets/home-banner.png'));
      await expect(page.locator('.tiptap img')).toHaveCount(1);
      await page.locator('.tiptap img').click(); await page.getByRole('button', { name: '图片属性' }).click();
      await page.getByLabel('替代文字').fill('真实正文图片'); await page.getByLabel('图片标题').fill('联调'); await page.getByRole('button', { name: '应用' }).click();
      await page.getByRole('button', { name: '保存修改' }).click(); await expect(page.getByRole('dialog', { name: '保存成功' })).toBeVisible();
      const saved = await read(); result.bodyImageBinding = JSON.stringify(saved.content).includes('真实正文图片');
      await page.getByRole('button', { name: '继续编辑' }).click();
    }
    catch { /* 保留真实未就绪结果，不自动重发保存。 */ }
  }
  await mkdir(resolve(root, 'test-results/live-editor'), { recursive: true });
  await page.screenshot({ path: resolve(root, 'test-results/live-editor/editor-live.png'), fullPage: true });
} catch (error) {
  // 不输出请求体、响应正文、Cookie 或管理员配置。
  console.error('真实联调未完整通过：', error instanceof assert.AssertionError ? error.message.split('\n')[0] : error.name);
  process.exitCode = 1;
} finally {
  if (articleId && context) {
    // 先解绑测试图片再隐藏文章，避免隐藏文章保留引用而让测试文件永久驻留。
    const detached = await context.request.post(`${origin}/api/articles`, { headers: await csrf(context.request), data: { articleId,
      title: '编辑页联调已结束', content: { schemaVersion: 1, doc: { type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: '临时联调内容已清理' }] }] } }, coverMediaId: null, tagIds: [] } });
    const response = await context.request.delete(`${origin}/api/articles/${articleId}`, { headers: await csrf(context.request) }); result.articleCleaned = detached.status() === 200 && response.status() === 204;
  }
  if (accountId) { const response = await admin.delete(`/api/admin/users/${accountId}`, { headers: await csrf(admin) }); result.accountCleaned = response.status() === 204; }
  await context?.close(); await browser?.close(); await server?.close(); await admin.dispose();
  console.log(JSON.stringify(result));
}
