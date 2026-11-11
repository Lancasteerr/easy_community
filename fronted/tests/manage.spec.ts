import { expect, test, type Page } from '@playwright/test';
import { article, profile } from '../src/test/fixtures';

/** 所有演示数据只存在于浏览器拦截中；真实联调另行记录。 */
async function signedIn(page: Page) {
  await page.route('**/api/users/me', (route) => route.fulfill({ json: profile }));
  await page.route('**/api/auth/csrf', (route) => route.fulfill({ json: { token: 'masked', headerName: 'X-CSRF-TOKEN' } }));
  await page.route('**/api/articles/feed?**', (route) => route.fulfill({ json: { items: [], nextCursor: null } }));
  await page.route('**/test-cover.png', (route) => route.fulfill({ path: 'src/assets/home-banner.png', contentType: 'image/png' }));
  await page.route('**/missing-cover.png', (route) => route.fulfill({ status: 404, body: '' }));
}
const rows = Array.from({ length: 12 }, (_, index) => ({ ...article(String(index + 1)),
  title: index === 1 ? '从社区小白到内容创作者：我整理了这份入门清单，分享那些值得记录的日常与灵感'.repeat(3) : `我的文章 ${index + 1}`,
  coverUrl: index === 2 ? null : index === 3 ? '/missing-cover.png' : '/test-cover.png',
  viewCount: index === 2 ? 0 : 128000, likeCount: index === 2 ? 0 : 86,
}));

for (const viewport of [{ name: 'desktop', width: 1440, height: 1024 }, { name: 'wide', width: 1920, height: 1080 }, { name: 'short', width: 1440, height: 768 }]) {
  test(`管理布局、固定区域及触底续页（${viewport.name}）`, async ({ page }, info) => {
    await signedIn(page);
    await page.setViewportSize(viewport);
    let reads = 0;
    await page.route('**/api/users/me/articles?**', (route) => {
      reads++;
      const query = new URL(route.request().url()).searchParams;
      expect(query.get('size')).toBe('20');
      expect([...query.keys()].every((key) => ['size', 'cursor'].includes(key))).toBe(true);
      return route.fulfill({ json: query.has('cursor') ? { items: [article('13')], nextCursor: null } : { items: rows, nextCursor: 'next+/=' } });
    });
    await page.goto('/manage/articles');
    await expect(page.locator('.manage-row')).toHaveCount(12);
    await page.evaluate(() => document.fonts.ready);
    const x = (viewport.width - 1227) / 2;
    expect(await page.locator('.manage-panel').boundingBox()).toMatchObject({ x: x + 242, y: 167, width: 985, height: viewport.height - 189 });
    const list = page.getByRole('region', { name: '已发布文章列表' });
    expect(await list.boundingBox()).toMatchObject({ x: x + 242, y: 231, height: viewport.height - 253 });
    expect((await page.locator('.manage-row').first().locator('.manage-cover').boundingBox())?.width).toBe(160);
    expect((await page.locator('.manage-row').nth(1).locator('h2').boundingBox())!.height).toBeLessThanOrEqual(48);
    await expect(page.getByText('暂无封面', { exact: true })).toHaveCount(2);
    await expect(page.getByText('封面加载失败')).toHaveCount(0);
    expect(await page.locator('.manage-cover-placeholder img').first().boundingBox()).toMatchObject({ width: 36, height: 36 });
    const fixed = page.locator('.home-header, .manage-sidebar, .manage-toolbar');
    const before = await Promise.all((await fixed.all()).map((element) => element.boundingBox()));
    await page.screenshot({ path: info.outputPath(`manage-${viewport.name}.png`) });
    await list.evaluate((element) => { element.scrollTop = element.scrollHeight; });
    await expect(page.locator('.manage-row')).toHaveCount(13);
    await expect(page.getByText('没有更多内容了')).toBeVisible();
    expect(reads).toBe(2);
    expect(await Promise.all((await fixed.all()).map((element) => element.boundingBox()))).toEqual(before);
    expect(await page.evaluate(() => ({ scroll: scrollY, height: document.documentElement.scrollHeight }))).toEqual({ scroll: 0, height: viewport.height });
  });
}

test('刷新失败复用设计稿失败态，重试成功后仅列表回顶', async ({ page }, info) => {
  await signedIn(page);
  await page.setViewportSize({ width: 1280, height: 768 });
  let reads = 0;
  await page.route('**/api/users/me/articles?**', async (route) => {
    reads++;
    if (reads === 2) return route.fulfill({ status: 503, json: { code: 'ARTICLE_SEARCH_UNAVAILABLE' } });
    return route.fulfill({ json: { items: reads === 1 ? rows : [article('new')], nextCursor: null } });
  });
  await page.goto('/manage/articles');
  await expect(page.locator('.manage-row')).toHaveCount(12);
  const list = page.locator('.manage-load-content .manage-list');
  await list.evaluate((element) => { element.scrollTop = 500; });
  await page.evaluate(() => window.scrollTo(100, 0));
  const horizontal = await page.evaluate(() => scrollX);
  await page.getByRole('button', { name: '刷新', exact: true }).click();
  await expect(page.getByRole('heading', { name: '内容加载失败' })).toBeVisible();
  await expect(page.getByText('请检查网络连接后重试')).toBeVisible();
  await expect(page.locator('.manage-toolbar-feedback')).toHaveCount(0);
  await expect(page.locator('.manage-row').first()).not.toBeVisible();
  await expect(page.locator('.manage-row')).toHaveCount(12);
  expect(await list.evaluate((element) => element.scrollTop)).toBe(500);
  await page.screenshot({ path: info.outputPath('manage-refresh-failure.png') });
  await page.getByRole('button', { name: '重新加载', exact: true }).click();
  await expect(page.locator('.manage-row')).toHaveCount(1);
  expect(await list.evaluate((element) => element.scrollTop)).toBe(0);
  expect(await page.evaluate(() => scrollX)).toBe(horizontal);
});

test('原生删除弹窗、Esc、焦点恢复与最后一项删除', async ({ page }, info) => {
  await signedIn(page);
  let writes = 0;
  await page.route('**/api/users/me/articles?**', (route) => route.fulfill({ json: { items: [article('1'), article('2')], nextCursor: null } }));
  await page.route('**/api/articles/*', (route) => {
    expect(route.request().method()).toBe('DELETE');
    expect(route.request().headers()['x-csrf-token']).toBe('masked');
    writes++;
    return route.fulfill({ status: 204, body: '' });
  });
  await page.goto('/manage/articles');
  const first = page.getByRole('button', { name: '删除 文章 1', exact: true });
  await first.click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole('button', { name: '取消' })).toBeFocused();
  expect(await dialog.boundingBox()).toMatchObject({ width: 480, height: 270 });
  // 反向Tab也留在模态层，背景操作不会获得焦点。
  await page.keyboard.press('Shift+Tab');
  await expect(dialog.getByRole('button', { name: '删除', exact: true })).toBeFocused();
  await page.screenshot({ path: info.outputPath('manage-delete-confirm.png') });
  await page.keyboard.press('Escape');
  await expect(dialog).not.toBeVisible();
  await expect(first).toBeFocused();
  expect(writes).toBe(0);
  await first.click();
  await dialog.getByRole('button', { name: '删除', exact: true }).click();
  await expect(page.locator('.manage-row')).toHaveCount(1);
  await expect(page.getByRole('button', { name: '删除 文章 2', exact: true })).toBeFocused();
  await page.getByRole('button', { name: '删除 文章 2', exact: true }).click();
  await dialog.getByRole('button', { name: '删除', exact: true }).click();
  await expect(page.getByText('暂无已发布内容')).toBeVisible();
  expect(writes).toBe(2);
  // 新查询返回短暂旧卡片也不能恢复本页已经删除的文章。
  await page.getByRole('button', { name: '刷新', exact: true }).click();
  await expect(page.locator('.manage-row')).toHaveCount(0);
});

test('删除结果未知，关闭重开保持核对态，作者接口核对不触发公开详情', async ({ page }) => {
  await signedIn(page);
  let writes = 0;
  let checks = 0;
  await page.route('**/api/users/me/articles?**', (route) => route.fulfill({ json: { items: [article('1')], nextCursor: null } }));
  await page.route('**/api/articles/1', (route) => { writes++; return route.fulfill({ status: 503, json: { code: 'ARTICLE_WRITE_RESULT_UNKNOWN' } }); });
  await page.route('**/api/articles/1/edit', (route) => {
    checks++;
    return route.fulfill(checks === 1 ? { json: { articleId: '1' } } : { status: 409, json: { code: 'ARTICLE_HIDDEN' } });
  });
  await page.goto('/manage/articles');
  await page.getByRole('button', { name: '删除 文章 1', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('button', { name: '删除', exact: true }).click();
  await expect(dialog.getByRole('heading')).toHaveText('删除这篇内容？');
  await expect(dialog.getByRole('status')).toContainText('删除结果尚未确认');
  await page.keyboard.press('Escape');
  await page.getByRole('button', { name: '核对删除结果 文章 1', exact: true }).click();
  await dialog.getByRole('button', { name: '核对结果' }).click();
  await expect(dialog.getByRole('status')).toContainText('仍可读取');
  await expect(page.locator('.manage-row')).toHaveCount(1);
  await dialog.getByRole('button', { name: '核对结果' }).click();
  await expect(page.locator('.manage-row')).toHaveCount(0);
  expect(writes).toBe(1);
  expect(checks).toBe(2);
});

test('游客由管理作品入口登录回跳，离开重进读取首批', async ({ page }) => {
  let authenticated = false;
  let reads = 0;
  await signedIn(page);
  await page.unroute('**/api/users/me');
  await page.route('**/api/users/me', (route) => route.fulfill(authenticated ? { json: profile } : { status: 401, json: { code: 'AUTHENTICATION_REQUIRED' } }));
  await page.route('**/api/auth/login', (route) => { authenticated = true; return route.fulfill({ json: profile }); });
  await page.route('**/api/users/me/articles?**', (route) => { reads++; return route.fulfill({ json: { items: rows, nextCursor: null } }); });
  await page.goto('/');
  await page.getByRole('link', { name: '管理作品' }).click();
  await expect(page).toHaveURL(/\/login$/);
  expect(reads).toBe(0);
  await page.getByLabel('账号', { exact: true }).fill('Alice_1');
  await page.getByLabel('密码', { exact: true }).fill('password');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page).toHaveURL(/\/manage\/articles$/);
  await expect(page.locator('.manage-row')).toHaveCount(12);
  const list = page.getByRole('region', { name: '已发布文章列表' });
  await list.evaluate((element) => { element.scrollTop = 400; });
  await page.getByRole('link', { name: 'Easy Community' }).click();
  await page.goBack();
  await expect(page.locator('.manage-row')).toHaveCount(12);
  expect(await list.evaluate((element) => element.scrollTop)).toBe(0);
  expect(reads).toBe(2);
});

test('首次骨架、失败、重试及窄屏空状态', async ({ page }, info) => {
  await signedIn(page);
  let complete: (() => void) | undefined;
  const hold = new Promise<void>((resolve) => { complete = resolve; });
  let reads = 0;
  await page.route('**/api/users/me/articles?**', async (route) => {
    reads++;
    if (reads === 1) { await hold; return route.fulfill({ status: 503, json: { code: 'ARTICLE_SEARCH_UNAVAILABLE' } }); }
    return route.fulfill({ json: { items: [], nextCursor: null } });
  });
  await page.goto('/manage/articles');
  await expect(page.getByRole('status', { name: '正在加载已发布文章' })).toBeVisible();
  await page.screenshot({ path: info.outputPath('manage-skeleton.png') });
  complete!();
  await expect(page.getByRole('heading', { name: '内容加载失败' })).toBeVisible();
  await page.screenshot({ path: info.outputPath('manage-initial-failure.png') });
  await page.getByRole('button', { name: '重新加载', exact: true }).click();
  await expect(page.getByText('暂无已发布内容')).toBeVisible();
  await page.screenshot({ path: info.outputPath('manage-empty.png') });
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(1440);
  await page.evaluate(() => window.scrollTo(800, 0));
  expect(await page.evaluate(() => scrollX)).toBeGreaterThan(0);
});

test('身份复核静默，失败复用原稿加载失败且恢复原位置，401清理', async ({ page }, info) => {
  await signedIn(page);
  let mode = 'ok';
  let reads = 0;
  let release: (() => void) | undefined;
  const held = new Promise<void>((resolve) => { release = resolve; });
  await page.unroute('**/api/users/me');
  await page.route('**/api/users/me', async (route) => {
    if (mode === 'checking') await held;
    return route.fulfill(mode === 'ok' ? { json: profile }
      : { status: mode === 'expired' ? 401 : 503, json: { code: mode === 'expired' ? 'AUTHENTICATION_REQUIRED' : 'DEPENDENCY_UNAVAILABLE' } });
  });
  await page.route('**/api/users/me/articles?**', (route) => { reads++; return route.fulfill({ json: { items: rows, nextCursor: null } }); });
  await page.goto('/manage/articles');
  await expect(page.locator('.manage-row')).toHaveCount(12);
  const list = page.locator('.manage-load-content .manage-list');
  await list.evaluate((element) => { element.scrollTop = 400; });
  const originalBounds = await list.boundingBox();
  mode = 'checking';
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(page.getByRole('button', { name: '刷新', exact: true })).toBeDisabled();
  await expect(list).toBeVisible();
  expect(await list.boundingBox()).toEqual(originalBounds);
  await expect(page.locator('.manage-notice')).toHaveCount(0);
  mode = 'failed';
  release!();
  await expect(page.getByRole('heading', { name: '内容加载失败' })).toBeVisible();
  await expect(page.getByText('请检查网络连接后重试')).toBeVisible();
  expect(await list.evaluate((element) => element.scrollTop)).toBe(400);
  await expect(list).not.toBeVisible();
  await expect(page.getByRole('button', { name: '刷新', exact: true })).toBeDisabled();
  const state = page.locator('.manage-load-overlay .manage-centered-state');
  expect(await state.locator('img').boundingBox()).toMatchObject({ y: 461, width: 64, height: 64 });
  expect(await state.getByRole('heading').boundingBox()).toMatchObject({ y: 547, height: 28 });
  expect(await state.getByRole('button', { name: '重新加载' }).boundingBox()).toMatchObject({ y: 633, width: 104, height: 36 });
  await page.screenshot({ path: info.outputPath('manage-identity-failure.png') });
  mode = 'ok';
  await page.getByRole('button', { name: '重新加载', exact: true }).click();
  await expect(page.getByRole('button', { name: '刷新', exact: true })).toBeEnabled();
  await expect(list).toBeVisible();
  expect(await list.evaluate((element) => element.scrollTop)).toBe(400);
  expect(reads).toBe(1);
  mode = 'expired';
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.locator('.manage-row')).toHaveCount(0);
});

test('追加加载、失败保留、手动重试和完成状态', async ({ page }, info) => {
  await signedIn(page);
  let release: (() => void) | undefined;
  const held = new Promise<void>((resolve) => { release = resolve; });
  let appends = 0;
  await page.route('**/api/users/me/articles?**', async (route) => {
    if (!new URL(route.request().url()).searchParams.has('cursor')) return route.fulfill({ json: { items: rows, nextCursor: 'next' } });
    appends++;
    if (appends === 1) { await held; return route.fulfill({ status: 503, json: { code: 'ARTICLE_SEARCH_UNAVAILABLE' } }); }
    return route.fulfill({ json: { items: [article('13')], nextCursor: null } });
  });
  await page.goto('/manage/articles');
  const list = page.getByRole('region', { name: '已发布文章列表' });
  await expect(page.locator('.manage-row')).toHaveCount(12);
  await list.evaluate((element) => { element.scrollTop = element.scrollHeight; });
  await expect(page.getByText('正在加载更多内容')).toBeVisible();
  await page.screenshot({ path: info.outputPath('manage-append-loading.png') });
  release!();
  await expect(page.getByRole('button', { name: '加载失败，点击重试' })).toBeVisible();
  await expect(page.locator('.manage-tail p')).toHaveCount(0);
  await expect(page.locator('.manage-row')).toHaveCount(12);
  await page.screenshot({ path: info.outputPath('manage-append-failure.png') });
  await page.getByRole('button', { name: '加载失败，点击重试' }).click();
  await expect(page.locator('.manage-row')).toHaveCount(13);
  await list.evaluate((element) => { element.scrollTop = element.scrollHeight; });
  await expect(page.getByText('没有更多内容了')).toBeVisible();
  await page.screenshot({ path: info.outputPath('manage-end.png') });
});

for (const failure of [{ status: 400, code: 'ARTICLE_SEARCH_INVALID' }, { status: 409, code: 'ARTICLE_CURSOR_EXPIRED' }]) {
  test(`游标失效${failure.status}保留列表，重新加载开启新查询`, async ({ page }) => {
    await signedIn(page);
    let starts = 0;
    await page.route('**/api/users/me/articles?**', (route) => {
      if (new URL(route.request().url()).searchParams.has('cursor')) return route.fulfill({ status: failure.status, json: { code: failure.code } });
      starts++;
      return route.fulfill({ json: { items: starts === 1 ? rows : [article('fresh')], nextCursor: starts === 1 ? 'expired' : null } });
    });
    await page.goto('/manage/articles');
    await expect(page.locator('.manage-row')).toHaveCount(12);
    const list = page.getByRole('region', { name: '已发布文章列表' });
    await list.evaluate((element) => { element.scrollTop = element.scrollHeight; });
    await page.getByRole('button', { name: '加载失败，点击重试', exact: true }).click();
    await expect(page.getByRole('heading', { name: '文章 fresh' })).toBeVisible();
    expect(await list.evaluate((element) => element.scrollTop)).toBe(0);
    expect(starts).toBe(2);
  });
}

test('删除处理中锁定弹窗，403刷新令牌和429均不自动重发', async ({ page }, info) => {
  await signedIn(page);
  let writes = 0;
  let csrfReads = 0;
  let release: (() => void) | undefined;
  const held = new Promise<void>((resolve) => { release = resolve; });
  await page.unroute('**/api/auth/csrf');
  await page.route('**/api/auth/csrf', (route) => { csrfReads++; return route.fulfill({ json: { token: `token-${csrfReads}`, headerName: 'X-CSRF-TOKEN' } }); });
  await page.route('**/api/users/me/articles?**', (route) => route.fulfill({ json: { items: [article('1')], nextCursor: null } }));
  await page.route('**/api/articles/1', async (route) => {
    writes++;
    if (writes === 1) { await held; return route.fulfill({ status: 403, json: { code: 'ACCESS_OR_CSRF_DENIED' } }); }
    if (writes === 2) return route.fulfill({ status: 429, headers: { 'Retry-After': '1' }, json: { code: 'RATE_LIMITED' } });
    expect(route.request().headers()['x-csrf-token']).toBe('token-2');
    return route.fulfill({ status: 204, body: '' });
  });
  await page.goto('/manage/articles');
  await page.getByRole('button', { name: '删除 文章 1', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('button', { name: '删除', exact: true }).click();
  await expect(dialog.getByRole('button', { name: '删除中…' })).toBeDisabled();
  await page.keyboard.press('Escape');
  await page.keyboard.press('Tab');
  await expect(dialog).toBeVisible();
  await expect(dialog).toBeFocused();
  await page.screenshot({ path: info.outputPath('manage-deleting.png') });
  release!();
  await expect(dialog.getByRole('status')).toContainText('凭证已刷新');
  expect(writes).toBe(1);
  await dialog.getByRole('button', { name: '删除', exact: true }).click();
  await expect(dialog.getByRole('button', { name: /秒后重试/ })).toBeDisabled();
  await expect(dialog.getByRole('button', { name: '删除', exact: true })).toBeEnabled();
  expect(writes).toBe(2);
  await dialog.getByRole('button', { name: '删除', exact: true }).click();
  await expect(page.locator('.manage-row')).toHaveCount(0);
});

test('无观察器时空页仍可手动续读，空页不等于结束', async ({ page }) => {
  await signedIn(page);
  await page.addInitScript(() => { Object.defineProperty(window, 'IntersectionObserver', { value: undefined, configurable: true }); });
  let reads = 0;
  await page.route('**/api/users/me/articles?**', (route) => {
    reads++;
    return route.fulfill({ json: reads === 1 ? { items: [], nextCursor: 'next' } : { items: [article('1')], nextCursor: null } });
  });
  await page.goto('/manage/articles');
  await expect(page.getByRole('button', { name: '继续加载' })).toBeVisible();
  await expect(page.getByText('暂无已发布内容')).toHaveCount(0);
  expect(reads).toBe(1);
  await page.getByRole('button', { name: '继续加载' }).click();
  await expect(page.locator('.manage-row')).toHaveCount(1);
});
