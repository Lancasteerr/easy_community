import { expect, test, type Locator, type Page } from '@playwright/test';
import { article, profile } from '../src/test/fixtures';

/** 多级长目录和长代码刻意撑出溢出，验收真实浏览器的滚动归属。 */
const contentHtml = Array.from({ length: 36 }, (_, index) =>
  `<h${index % 2 ? 3 : 2}>章节 ${index + 1}</h${index % 2 ? 3 : 2}><p>${'社区阅读内容。'.repeat(30)}</p>`).join('')
  + `<pre><code>${'long_code_'.repeat(100)}</code></pre>`;

/** 按真实鼠标坐标点击吸顶元素，避免自动 scrollIntoView 改变待采集的阅读位置。 */
async function clickSticky(page: Page, target: Locator) {
  await expect(target).toBeVisible();
  const bounds = (await target.boundingBox())!;
  await page.mouse.click(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
}

async function openArticle(page: Page, html = contentHtml, cover = true) {
  await page.route('**/api/users/me', (route) => route.fulfill({ status: 401, json: { code: 'AUTHENTICATION_REQUIRED' } }));
  await page.route('**/detail-cover.png', (route) => route.fulfill({ path: 'src/assets/home-banner.png', contentType: 'image/png' }));
  await page.route('**/api/articles/2', (route) => route.fulfill({ json: {
    ...article('2'), coverMediaId: null, coverUrl: cover ? '/detail-cover.png' : null, contentHtml: html,
  } }));
  await page.goto('/articles/2', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { name: '文章 2', exact: true })).toBeVisible();
  await page.evaluate(() => document.fonts.ready);
}

for (const viewport of [
  { width: 1440, height: 1024 }, { width: 1280, height: 844 },
  { width: 1440, height: 560 }, { width: 390, height: 844 },
]) {
  test(`统一滚动与吸顶布局 ${viewport.width}×${viewport.height}`, async ({ page }, testInfo) => {
    await page.setViewportSize(viewport);
    await openArticle(page);
    const scroll = page.getByRole('region', { name: '文章详情滚动区域' });
    const card = page.getByRole('region', { name: '文章阅读区域' });
    expect(await scroll.boundingBox()).toMatchObject({ x: 0, y: 0, ...viewport });
    expect((await card.boundingBox())!.y).toBe(167);
    await page.screenshot({ path: testInfo.outputPath('detail-top.png') });
    await scroll.focus();
    await expect(scroll).toHaveCSS('outline-style', 'solid');
    await scroll.evaluate((element) => {
      element.dispatchEvent(new Event('wheel'));
      element.scrollTo({ top: 700, left: 0, behavior: 'instant' });
    });
    expect((await card.boundingBox())!.y).toBe(-533);
    expect(await card.evaluate((element) => element.scrollTop)).toBe(0);
    expect((await page.locator('.detail-top-frame').boundingBox())!.y).toBe(0);
    expect((await page.locator('.home-header').boundingBox())!.y).toBe(34);
    expect((await page.locator('.detail-sidebar').boundingBox())!.y).toBe(167);
    expect((await page.locator('.detail-actions').boundingBox())!.y).toBe(viewport.height <= 600 ? 167 : 323);
    // 原生吸顶遮挡层必须实际覆盖文章，而不只是坐标不变。
    expect(await page.evaluate(() => !!document.elementFromPoint(200, 150)?.closest('.detail-top-frame'))).toBe(true);
    const sidebar = (await page.locator('.detail-sidebar').boundingBox())!;
    const visibleHeight = await scroll.evaluate((element) => element.clientHeight);
    expect(sidebar.y + sidebar.height).toBeLessThanOrEqual(visibleHeight - 34 + 1);
    if (viewport.width < 1440) {
      await scroll.evaluate((element) => element.scrollTo({ left: 700, behavior: 'instant' }));
      const articleX = (await card.boundingBox())!.x;
      expect((await page.locator('.detail-actions').boundingBox())!.x).toBe(articleX - 72);
      expect((await page.locator('.detail-sidebar').boundingBox())!.x).toBe(articleX + 905);
    }
    expect(await page.evaluate(() => ({ x: scrollX, y: scrollY, width: document.documentElement.scrollWidth,
      height: document.documentElement.scrollHeight }))).toEqual({ x: 0, y: 0, ...viewport });
    await page.screenshot({ path: testInfo.outputPath('detail-scrolled.png') });
    await scroll.press('PageDown');
    await expect.poll(() => scroll.evaluate((element) => element.scrollTop)).toBeGreaterThan(700);
    await scroll.press('End');
    await expect.poll(() => scroll.evaluate((element) => element.scrollHeight - element.clientHeight - element.scrollTop)).toBeLessThan(2);
    await expect(page.locator('.detail-toc-list [aria-current="location"]')).toHaveText('章节 36');
    await scroll.press('Home');
    await expect.poll(() => scroll.evaluate((element) => element.scrollTop)).toBe(0);
  });
}

test('正文、留白与作者区域滚轮作用于通栏，目录和代码块独立滚动', async ({ page }) => {
  await openArticle(page);
  const scroll = page.locator('.detail-scroll');
  for (const point of [{ x: 500, y: 500 }, { x: 20, y: 500 }, { x: 1200, y: 200 }]) {
    const before = await scroll.evaluate((element) => element.scrollTop);
    await page.mouse.move(point.x, point.y);
    await page.mouse.wheel(0, 200);
    await expect.poll(() => scroll.evaluate((element) => element.scrollTop)).toBeGreaterThan(before);
  }
  const list = page.locator('.detail-toc-list');
  const articleTop = await scroll.evaluate((element) => element.scrollTop);
  await list.hover();
  await page.mouse.wheel(0, 10000);
  await expect.poll(() => list.evaluate((element) => element.scrollHeight - element.clientHeight - element.scrollTop)).toBeLessThan(2);
  await page.mouse.wheel(0, 500);
  expect(await scroll.evaluate((element) => element.scrollTop)).toBe(articleTop);
  await scroll.evaluate((element) => element.scrollTo({ top: element.scrollHeight, behavior: 'instant' }));
  const code = page.locator('.detail-prose pre');
  await code.hover();
  await page.mouse.wheel(500, 0);
  await expect.poll(() => code.evaluate((element) => element.scrollLeft)).toBeGreaterThan(0);
  expect(await scroll.evaluate((element) => element.scrollLeft)).toBe(0);
});

test('目录跳转扣除导航高度，保持横向位置且高亮对应章节', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 844 });
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await openArticle(page);
  const scroll = page.locator('.detail-scroll');
  await scroll.evaluate((element) => {
    element.dispatchEvent(new Event('wheel'));
    element.scrollLeft = 100;
  });
  await page.getByRole('button', { name: '章节 3', exact: true }).click();
  await expect.poll(async () => (await page.locator('#article-section-3').boundingBox())!.y).toBe(191);
  await expect(page.getByRole('button', { name: '章节 3', exact: true })).toHaveAttribute('aria-current', 'location');
  expect(await scroll.evaluate((element) => element.scrollLeft)).toBe(100);
  await page.getByRole('button', { name: '收起', exact: true }).click();
  await expect(page.locator('.detail-toc-list')).toHaveCount(0);
  await page.getByRole('button', { name: '展开', exact: true }).click();
  await expect(page.getByRole('button', { name: '章节 3', exact: true })).toBeInViewport();
});

for (const entry of ['avatar', 'like']) {
  test(`游客通过 ${entry} 登录后恢复纵横位置与阅读锚点`, async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 844 });
    await openArticle(page);
    let authenticated = false;
    await page.route('**/api/users/me', (route) => authenticated ? route.fulfill({ json: profile })
      : route.fulfill({ status: 401, json: { code: 'AUTHENTICATION_REQUIRED' } }));
    await page.route('**/api/auth/csrf', (route) => route.fulfill({ json: { token: 'masked', headerName: 'X-CSRF-TOKEN' } }));
    await page.route('**/api/auth/login', (route) => { authenticated = true; return route.fulfill({ json: profile }); });
    const scroll = page.locator('.detail-scroll');
    // 留出左侧按钮可点击空间，避免浏览器为显示按钮而主动横向回滚。
    await scroll.evaluate((element) => {
      element.dispatchEvent(new Event('wheel'));
      element.scrollTo({ top: 900, left: 24, behavior: 'instant' });
    });
    const anchor = page.locator('[data-read-block="5"]');
    const anchorTop = (await anchor.boundingBox())!.y;
    let likes = 0;
    page.on('request', (request) => { if (request.url().endsWith('/like')) likes++; });
    await clickSticky(page, entry === 'avatar' ? page.getByRole('button', { name: '登录', exact: true })
      : page.getByRole('button', { name: /^点赞 / }));
    await page.getByLabel('账号', { exact: true }).fill('Alice_1');
    await page.getByLabel('密码', { exact: true }).fill('password');
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await expect(page).toHaveURL(/\/articles\/2$/);
    await expect.poll(() => scroll.evaluate((element) => ({ top: element.scrollTop, left: element.scrollLeft }))).toEqual({ top: 900, left: 24 });
    expect((await anchor.boundingBox())!.y).toBeCloseTo(anchorTop, 0);
    expect(likes).toBe(0);
  });
}

test('历史前进恢复锚点并在上方图片延迟增高时校正，用户阅读后停止校正', async ({ page }) => {
  let release!: () => void;
  const pending = new Promise<void>((resolve) => { release = resolve; });
  await page.route('**/late-image.png', async (route) => {
    await pending;
    await route.fulfill({ path: 'src/assets/home-banner.png', contentType: 'image/png' });
  });
  await openArticle(page, '<p><img src="/late-image.png" alt="延迟图片" /></p>' + contentHtml, false);
  const scroll = page.locator('.detail-scroll');
  await scroll.evaluate((element) => {
    element.dispatchEvent(new Event('wheel'));
    element.scrollTop = 1000;
  });
  const anchor = page.locator('[data-read-block="10"]');
  const before = (await anchor.boundingBox())!.y;
  // 切换历史项后恢复态仍持续，图片加载应校正上方高度变化。
  await page.route('**/api/articles/feed?**', (route) => route.fulfill({ json: { items: [], nextCursor: null } }));
  await clickSticky(page, page.getByRole('link', { name: 'Easy Community' }));
  await page.goBack();
  await expect.poll(() => scroll.evaluate((element) => element.scrollTop)).toBe(1000);
  release();
  await expect.poll(() => page.locator('.detail-prose img').evaluate((element: HTMLImageElement) => element.naturalWidth)).toBeGreaterThan(0);
  await expect.poll(async () => Math.abs((await anchor.boundingBox())!.y - before)).toBeLessThan(2);
  await scroll.evaluate((element) => element.dispatchEvent(new Event('wheel')));
  // 隔离浏览器原生锚定，只验证应用层自动恢复已停止。
  await scroll.evaluate((element) => element.style.overflowAnchor = 'none');
  const position = await scroll.evaluate((element) => element.scrollTop);
  await page.locator('.detail-prose img').evaluate((element: HTMLImageElement) => element.style.height = '800px');
  expect(await scroll.evaluate((element) => element.scrollTop)).toBe(position);
  await clickSticky(page, page.getByRole('link', { name: 'Easy Community' }));
  await page.goBack();
  await page.goForward();
  await expect(page).toHaveURL(/\/$/);
});

test('短文章与加载、错误、不存在状态共用视口框架', async ({ page }) => {
  await openArticle(page, '<p>短文章正文</p>', false);
  await expect(page.locator('.detail-toc')).toHaveCount(0);
  await expect(page.locator('.detail-cover')).toHaveCount(0);
  const scroll = page.locator('.detail-scroll');
  expect(await scroll.evaluate((element) => element.scrollHeight)).toBe(await scroll.evaluate((element) => element.clientHeight));
  for (const status of [404, 500]) {
    await page.route('**/api/articles/3', (route) => route.fulfill({ status, json: { code: status === 404 ? 'ARTICLE_NOT_FOUND' : 'INTERNAL_ERROR' } }));
    await page.goto('/articles/3');
    await expect(status === 404 ? page.getByRole('heading', { name: '文章不存在或已删除' }) : page.getByRole('button', { name: '重新加载' })).toBeVisible();
    expect((await page.locator('.detail-reader').boundingBox())!.y).toBe(167);
    expect(await page.evaluate(() => scrollY)).toBe(0);
  }
  await page.route('**/api/articles/4', () => { /* 保持请求待定，检查加载框架。 */ });
  await page.goto('/articles/4');
  await expect(page.getByRole('status', { name: '正在加载文章' })).toBeVisible();
  expect((await scroll.boundingBox())!.height).toBe(1024);
});
