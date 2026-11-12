import { expect, test, type Page } from '@playwright/test';
import { profile } from '../src/test/fixtures';
import { emptyForm } from '../src/editor/model';

/** 外观和编辑操作使用真实编辑器；接口仅在浏览器测试夹具中替换。 */
async function setup(page: Page, doc = emptyForm().content.doc) {
  await page.route('**/api/users/me', (route) => route.fulfill({ json: profile }));
  await page.route('**/api/auth/csrf', (route) => route.fulfill({ json: { token: 'masked', headerName: 'X-CSRF-TOKEN' } }));
  await page.route('**/api/articles/12/edit', (route) => route.fulfill({ json: { ...emptyForm(), articleId: '12', title: '写作与日常', content: { schemaVersion: 1, doc } } }));
}
test('白色卡片覆盖表单与读取状态，暗色共享顶栏保持原尺寸', async ({ page }, info) => {
  await setup(page); await page.goto('/articles/12/edit'); await page.evaluate(() => document.fonts.ready);
  await expect(page.locator('.article-editor-panel')).toHaveCSS('background-color', 'rgb(255, 255, 255)');
  await expect(page.getByRole('textbox', { name: '文章标题' })).toHaveCSS('color', 'rgb(36, 41, 47)');
  expect(await page.locator('.home-header').boundingBox()).toMatchObject({ x: 106.5, y: 34, width: 1227, height: 90 });
  expect(await page.locator('.publish-entry').boundingBox()).toMatchObject({ width: 40, height: 40 });
  expect((await page.locator('.article-editor-inner').boundingBox())?.width).toBe(920);
  await expect(page.getByRole('button', { name: '保存修改' })).toHaveCSS('background-color', 'rgb(155, 206, 255)');
  await page.screenshot({ path: info.outputPath('white-editor-default.png'), fullPage: true });
  await page.route('**/api/articles/13/edit', (route) => route.fulfill({ status: 403, json: { code: 'ARTICLE_FORBIDDEN' } }));
  await page.goto('/articles/13/edit'); await expect(page.getByRole('heading', { name: '没有编辑权限' })).toBeVisible();
  await expect(page.locator('.editor-read-state')).toHaveCSS('color', 'rgb(36, 41, 47)');
});
test('白色保存与离开弹窗保持清晰的反馈和焦点', async ({ page }, info) => {
  await setup(page); await page.route('**/api/articles', (route) => route.fulfill({ status: 503, json: { code: 'DEPENDENCY_UNAVAILABLE' } }));
  await page.goto('/articles/new'); await page.getByRole('textbox', { name: '文章标题' }).fill('新的记录'); await page.getByRole('textbox', { name: '文章正文' }).fill('值得记录的文字。');
  await page.getByRole('button', { name: '发布文章', exact: true }).click();
  await expect(page.getByRole('dialog', { name: '发布失败' })).toHaveCSS('background-color', 'rgb(255, 255, 255)');
  await page.getByRole('button', { name: '继续编辑', exact: true }).click();
  await page.getByRole('link', { name: '返回首页' }).click();
  await expect(page.getByRole('dialog', { name: '离开编辑页？' })).toHaveCSS('background-color', 'rgb(255, 255, 255)');
  await expect(page.getByRole('button', { name: '继续编辑', exact: true })).toBeFocused();
  await page.screenshot({ path: info.outputPath('white-editor-leave-dialog.png') });
});

test('工具栏单行分组，长文吸顶且受正文底部约束', async ({ page }, info) => {
  await setup(page, { type: 'doc', content: Array.from({ length: 45 }, (_, index) => ({ type: 'paragraph', content: [{ type: 'text', text: `第${index + 1}段：让每一次分享都留下值得回看的痕迹。` }] })) });
  await page.goto('/articles/12/edit');
  const toolbar = page.getByRole('toolbar', { name: '正文格式' });
  await expect(toolbar).toBeVisible();
  const buttons = await toolbar.locator('button').all(); const bounds = await Promise.all(buttons.map((button) => button.boundingBox()));
  expect(new Set(bounds.map((box) => box!.y)).size).toBe(1);
  expect(await toolbar.getByRole('group').count()).toBe(6);
  await page.evaluate(() => window.scrollTo(0, 700)); await expect.poll(async () => (await toolbar.boundingBox())!.y).toBe(16);
  expect((await page.locator('.home-header').boundingBox())!.y).toBeLessThan(0);
  await page.screenshot({ path: info.outputPath('white-editor-sticky-toolbar.png') });
  await page.setViewportSize({ width: 1440, height: 300 });
  await page.getByRole('button', { name: '保存修改' }).scrollIntoViewIfNeeded();
  expect((await toolbar.boundingBox())!.y).toBeLessThan(16);
  expect(await page.locator('.tiptap').evaluate((element) => element.scrollHeight <= element.clientHeight + 2)).toBe(true);
});

test('链接浮层不改变正文高度，取消和外部关闭不修改文档，确认应用于原选区', async ({ page }, info) => {
  await setup(page, { type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: '选择这段文字' }] }] });
  let saved: any;
  await page.route('**/api/articles', (route) => { saved = route.request().postDataJSON(); return route.fulfill({ json: { articleId: '12' } }); });
  await page.goto('/articles/12/edit');
  const body = page.getByRole('textbox', { name: '文章正文' }); await body.click(); await body.press('ControlOrMeta+a');
  const before = await body.boundingBox(); const link = page.getByRole('button', { name: '链接', exact: true }); await link.click();
  const popup = page.getByRole('dialog', { name: '编辑链接' }); await expect(popup).toBeVisible(); expect(await body.boundingBox()).toEqual(before);
  await page.getByLabel('链接地址').fill('https://cancel.example'); await page.keyboard.press('Escape'); await expect(link).toBeFocused(); await expect(body.locator('a')).toHaveCount(0);
  await link.click(); await page.getByLabel('链接地址').fill('https://outside.example'); await page.getByRole('heading', { name: '编辑文章', exact: true }).click(); await expect(popup).toHaveCount(0); await expect(body.locator('a')).toHaveCount(0);
  await link.click(); await page.getByLabel('链接地址').fill('https://example.com'); await page.screenshot({ path: info.outputPath('white-editor-link-popover.png') });
  await page.getByRole('button', { name: '应用', exact: true }).click(); await expect(body.locator('a')).toHaveText('选择这段文字'); await expect(link).toBeFocused();
  await page.getByRole('button', { name: '保存修改' }).click(); await expect(page.getByRole('dialog', { name: '保存成功' })).toBeVisible();
  expect(saved.content.doc.content[0].content[0].marks).toEqual([{ type: 'link', attrs: { href: 'https://example.com' } }]);
});
