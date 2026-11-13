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
  await expect(page.getByRole('textbox', { name: '文章标题' })).toHaveCSS('font-weight', '700');
  await expect(page.getByRole('textbox', { name: '文章正文' })).toHaveCSS('font-weight', '500');
  await expect(page.getByRole('button', { name: '代码块', exact: true })).toHaveCSS('font-weight', '600');
  expect(await page.locator('.home-header').boundingBox()).toMatchObject({ x: 106.5, y: 34, width: 1227, height: 90 });
  expect(await page.getByRole('region', { name: '文章编辑滚动区域' }).boundingBox()).toMatchObject({ x: 0, y: 0, width: 1440, height: 1024 });
  expect(await page.getByRole('main', { name: '文章编辑区域' }).boundingBox()).toMatchObject({ y: 167, width: 1227 });
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
  const card = page.getByRole('main', { name: '文章编辑区域' });
  const scroll = page.getByRole('region', { name: '文章编辑滚动区域' });
  const cardBounds = (await card.boundingBox())!;
  // 整张卡片从顶部遮挡层下方经过，工具栏停在内容可视区域上沿。
  await scroll.evaluate((element) => element.scrollTo({ top: 700, behavior: 'instant' }));
  await expect.poll(async () => (await toolbar.boundingBox())!.y).toBe(cardBounds.y);
  expect(await card.boundingBox()).toMatchObject({ y: cardBounds.y - 700, height: cardBounds.height });
  expect(await card.evaluate((element) => element.scrollTop)).toBe(0);
  expect((await page.locator('.home-header').boundingBox())!.y).toBe(34);
  expect(await page.evaluate(() => ({ x: scrollX, y: scrollY, height: document.documentElement.scrollHeight }))).toEqual({ x: 0, y: 0, height: 1024 });
  const toolbarBounds = (await toolbar.boundingBox())!;
  expect(await page.evaluate(({ x, y }) => !!document.elementFromPoint(x, y)?.closest('.editor-toolbar'),
    { x: toolbarBounds.x + toolbarBounds.width / 2, y: toolbarBounds.y + 5 })).toBe(true);
  expect(await page.evaluate(() => !!document.elementFromPoint(200, 150)?.closest('.article-editor-top-frame'))).toBe(true);
  await page.screenshot({ path: info.outputPath('white-editor-sticky-toolbar.png') });
  await page.setViewportSize({ width: 1440, height: 300 });
  await page.getByRole('button', { name: '保存修改' }).scrollIntoViewIfNeeded();
  expect((await toolbar.boundingBox())!.y).toBeLessThan(cardBounds.y);
  await expect(page.getByRole('button', { name: '保存修改' })).toBeInViewport();
  expect(await page.locator('.tiptap').evaluate((element) => element.scrollHeight <= element.clientHeight + 2)).toBe(true);
});

test('链接浮层不改变正文高度，取消和外部关闭不修改文档，确认应用于原选区', async ({ page }, info) => {
  await setup(page, { type: 'doc', content: [
    { type: 'paragraph', content: [{ type: 'text', text: '选择这段文字' }] },
    ...Array.from({ length: 30 }, () => ({ type: 'paragraph', content: [{ type: 'text', text: '后续正文保持原样。' }] })),
  ] });
  let saved: any;
  await page.route('**/api/articles', (route) => { saved = route.request().postDataJSON(); return route.fulfill({ json: { articleId: '12' } }); });
  await page.goto('/articles/12/edit');
  const body = page.getByRole('textbox', { name: '文章正文' });
  // 只选择首段，再滚动至吸顶位置，验证浮层仍操作原选区。
  await body.locator('p').first().click(); await body.press('Home'); await body.press('Shift+End');
  const scroll = page.getByRole('region', { name: '文章编辑滚动区域' });
  await scroll.evaluate((element) => element.scrollTo({ top: 700, behavior: 'instant' }));
  const before = await body.boundingBox(); const link = page.getByRole('button', { name: '链接', exact: true }); await link.click();
  const popup = page.getByRole('dialog', { name: '编辑链接' }); await expect(popup).toBeVisible(); expect(await body.boundingBox()).toEqual(before);
  // 整页滚动继续触发已有定位监听，浮层跟随工具栏而不撑开正文。
  await scroll.evaluate((element) => element.scrollTo({ top: 0, behavior: 'instant' }));
  await expect.poll(async () => (await popup.boundingBox())!.y).toBeCloseTo((await link.boundingBox())!.y + (await link.boundingBox())!.height + 8, 0);
  await page.getByLabel('链接地址').fill('https://cancel.example'); await page.keyboard.press('Escape'); await expect(link).toBeFocused(); await expect(body.locator('a')).toHaveCount(0);
  await link.click(); await page.getByLabel('链接地址').fill('https://outside.example'); await page.getByRole('heading', { name: '编辑文章', exact: true }).click(); await expect(popup).toHaveCount(0); await expect(body.locator('a')).toHaveCount(0);
  await link.click(); await page.getByLabel('链接地址').fill('https://example.com'); await page.screenshot({ path: info.outputPath('white-editor-link-popover.png') });
  await page.getByRole('button', { name: '应用', exact: true }).click(); await expect(body.locator('a')).toHaveText('选择这段文字'); await expect(link).toBeFocused();
  await page.getByRole('button', { name: '保存修改' }).click(); await expect(page.getByRole('dialog', { name: '保存成功' })).toBeVisible();
  expect(saved.content.doc.content[0].content[0].marks).toEqual([{ type: 'link', attrs: { href: 'https://example.com' } }]);
});

test('清除格式作用于当前文本块且一步撤销，保留其他块和图片', async ({ page }) => {
  await setup(page, { type: 'doc', content: [
    { type: 'heading', attrs: { level: 2 }, content: [{ type: 'text', text: '加粗标题', marks: [{ type: 'bold' }] }] },
    { type: 'paragraph', content: [{ type: 'text', text: '斜体段落', marks: [{ type: 'italic' }] }] },
    { type: 'image', attrs: { fileId: 'aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa', src: '/style-image.png', alt: '配图' } },
  ] });
  await page.route('**/style-image.png', (route) => route.fulfill({ path: 'src/assets/home-banner.png', contentType: 'image/png' }));
  await page.goto('/articles/12/edit'); const body = page.getByRole('textbox', { name: '文章正文' });
  await body.locator('h2').click(); await page.getByRole('button', { name: '清除格式' }).click();
  await expect(body.locator('h2')).toHaveCount(0); await expect(body.locator('p').first()).toHaveText('加粗标题'); await expect(body.locator('strong')).toHaveCount(0);
  await expect(body.locator('em')).toHaveText('斜体段落'); await expect(body.locator('img')).toHaveCount(1);
  await page.getByRole('button', { name: '撤销', exact: true }).click(); await expect(body.locator('h2 strong')).toHaveText('加粗标题');
  await body.click(); await body.press('ControlOrMeta+a'); await page.getByRole('button', { name: '清除格式' }).click();
  await expect(body.locator('h2, strong, em')).toHaveCount(0); await expect(body.locator('img')).toHaveCount(1);
});

test('代码语言、高亮和缩进沿用协议，换行不计字数', async ({ page }, info) => {
  const code = 'public class Demo {\n  int value = 123;\n}';
  await setup(page, { type: 'doc', content: [{ type: 'codeBlock', attrs: { language: 'java' }, content: [{ type: 'text', text: code }] }, { type: 'paragraph', content: [{ type: 'text', text: '中😀 A，' }] }] });
  let saved: any; await page.route('**/api/articles', (route) => { saved = route.request().postDataJSON(); return route.fulfill({ json: { articleId: '12' } }); });
  await page.goto('/articles/12/edit'); const body = page.getByRole('textbox', { name: '文章正文' }); const language = page.getByRole('combobox', { name: '代码语言' });
  await body.locator('pre').click(); await body.press('ControlOrMeta+Home');
  await expect(language).toHaveValue('java'); await expect(body.locator('.hljs-keyword').first()).toBeVisible();
  const count = Array.from(code.replace(/\n/g, '') + '中😀 A，').length;
  await expect(page.getByLabel('正文字数')).toHaveText(`${count} 字`);
  await body.press('Tab'); await expect(body.locator('pre code')).toHaveText(`  ${code}`); await expect(page.getByLabel('正文字数')).toHaveText(`${count + 2} 字`);
  await body.press('Shift+Tab'); await expect(body.locator('pre code')).toHaveText(code);
  await language.selectOption('plaintext'); await expect(body.locator('pre span[class*="hljs-"]')).toHaveCount(0);
  await language.selectOption('java'); await expect(body.locator('.hljs-keyword').first()).toBeVisible();
  await expect(body.locator('pre code')).toHaveCSS('font-weight', '400');
  await page.screenshot({ path: info.outputPath('white-editor-code-highlight.png'), fullPage: true });
  await page.getByRole('button', { name: '保存修改' }).click(); await expect(page.getByRole('dialog', { name: '保存成功' })).toBeVisible();
  expect(saved.content.doc.content[0]).toEqual({ type: 'codeBlock', attrs: { language: 'java' }, content: [{ type: 'text', text: code }] });
  expect(JSON.stringify(saved)).not.toMatch(/hljs-|className/);
});

test('未知代码语言原样保存，自动识别不回写语言，显示操作不触发离开确认', async ({ page }) => {
  await setup(page, { type: 'doc', content: [{ type: 'codeBlock', attrs: { language: 'future_lang' }, content: [{ type: 'text', text: 'public class Demo { int value = 123; }' }] }] });
  let saved: any; await page.route('**/api/articles', (route) => { saved = route.request().postDataJSON(); return route.fulfill({ json: { articleId: '12' } }); });
  await page.route('**/api/articles/feed?**', (route) => route.fulfill({ json: { items: [], nextCursor: null } }));
  await page.goto('/articles/12/edit'); const body = page.getByRole('textbox', { name: '文章正文' }); await body.locator('pre').click();
  const language = page.getByRole('combobox', { name: '代码语言' }); await expect(language).toHaveValue('future_lang'); await expect(body.locator('pre span[class*="hljs-"]')).toHaveCount(0);
  await page.getByRole('button', { name: '保存修改' }).click(); await expect(page.getByRole('dialog', { name: '保存成功' })).toBeVisible(); expect(saved.content.doc.content[0].attrs.language).toBe('future_lang');
  await page.getByRole('button', { name: '继续编辑', exact: true }).click(); await body.locator('pre').click(); await language.selectOption('');
  await expect(body.locator('pre span[class*="hljs-"]').first()).toBeVisible();
  await page.getByRole('button', { name: '保存修改' }).click(); await expect(page.getByRole('dialog', { name: '保存成功' })).toBeVisible(); expect(saved.content.doc.content[0]).not.toHaveProperty('attrs');
  await page.getByRole('button', { name: '继续编辑', exact: true }).click();
  await body.click(); await body.press('ArrowLeft'); await page.getByRole('button', { name: '链接', exact: true }).click(); await page.keyboard.press('Escape');
  await page.getByRole('link', { name: '返回首页' }).click(); await expect(page).toHaveURL('http://127.0.0.1:5175/'); await expect(page.getByRole('dialog')).toHaveCount(0);
});
