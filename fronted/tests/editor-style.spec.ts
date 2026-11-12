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
