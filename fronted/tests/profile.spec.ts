import { expect, test, type Page } from '@playwright/test';
import { article, profile } from '../src/test/fixtures';

/** 接口与所有新标签页共享同一模拟会话，页面、菜单和滚动使用生产实现。 */
async function mockProfile(page: Page, signedIn = true, items = 12) {
  let authenticated = signedIn;
  let reads = 0;
  let likes = 0;
  const context = page.context();
  await context.route('**/api/auth/session', (route) => route.fulfill(authenticated
    ? { json: { userId: profile.userId, role: profile.role } }
    : { status: 401, json: { code: 'AUTHENTICATION_REQUIRED' } }));
  await context.route(/\/api\/users\/\d+$/, (route) => route.fulfill({ json: {
    ...profile, userId: new URL(route.request().url()).pathname.split('/').at(-1), username: 'Flame', bio: '记录设计、开发与构建社区产品的过程。',
  } }));
  await context.route('**/profile-cover-test.png', (route) => route.fulfill({ path: 'src/assets/home-banner.png', contentType: 'image/png' }));
  await context.route('**/api/articles/search?**', (route) => {
    reads++;
    const query = new URL(route.request().url()).searchParams;
    expect(query.get('sort')).toBe('latest'); expect(query.get('size')).toBe('20');
    return route.fulfill({ json: { items: Array.from({ length: items }, (_, index) => ({
      ...article(String(index + 1)), authorId: query.get('authorId'), coverUrl: index % 2 ? null : '/profile-cover-test.png',
    })), nextCursor: null } });
  });
  await context.route(/\/api\/articles\/\d+$/, (route) => route.fulfill({ json: {
    ...article(new URL(route.request().url()).pathname.split('/').at(-1)), coverMediaId: null, contentHtml: '<h2>正文</h2><p>阅读后返回个人主页。</p>',
  } }));
  await context.route('**/api/auth/csrf', (route) => route.fulfill({ json: { token: 'test', headerName: 'X-CSRF-TOKEN' } }));
  await context.route('**/api/auth/login', (route) => { authenticated = true; return route.fulfill({ json: profile }); });
  await context.route('**/api/auth/logout', (route) => { authenticated = false; return route.fulfill({ status: 204 }); });
  await context.route('**/api/articles/*/like', (route) => {
    likes++;
    return route.fulfill({ status: 202, json: { accepted: true, liked: route.request().method() === 'PUT',
      operationId: route.request().headers()['x-operation-id'], version: '1' } });
  });
  return { reads: () => reads, likes: () => likes };
}

async function openProfile(page: Page, id = profile.userId) {
  await page.goto(`/users/${id}`);
  await expect(page.getByRole('heading', { name: 'Flame', exact: true })).toBeVisible();
  await expect(page.locator('.profile-article-card')).not.toHaveCount(0);
  await page.evaluate(() => document.fonts.ready);
}

test('本人完整资料、真实文章与只读样例，编辑资料打开新标签页', async ({ page }, testInfo) => {
  await mockProfile(page);
  await openProfile(page);
  expect((await page.locator('.profile-main > div > .profile-header').boundingBox())!.height).toBe(376);
  expect((await page.locator('.profile-columns').boundingBox())!.y).toBe(560);
  expect(await page.locator('.profile-article-card .article-author-row').count()).toBe(0);
  await expect(page.locator('.profile-article-card').first().getByRole('button', { name: '点赞 328' })).toBeVisible();
  expect(await page.locator('.profile-stats').last().getByRole('button').count()).toBe(0);
  await page.screenshot({ path: testInfo.outputPath('profile-self.png') });
  const popup = page.waitForEvent('popup');
  await page.getByRole('link', { name: '编辑资料', exact: true }).click();
  const settings = await popup;
  await expect(settings).toHaveURL(/\/settings\/profile$/);
  await settings.close();
});

test('访客关注仅提示未开放，滚动后资料简要吸顶，回顶恢复', async ({ page }, testInfo) => {
  const mock = await mockProfile(page, false);
  await openProfile(page, '8');
  await page.getByRole('button', { name: '+ 关注', exact: true }).click();
  await expect(page.getByRole('status').last()).toHaveText('功能暂未开放');
  await expect(page).toHaveURL(/\/users\/8$/);
  await page.screenshot({ path: testInfo.outputPath('profile-visitor.png') });
  const scroll = page.locator('.profile-scroll');
  const first = page.locator('.profile-article-card').first();
  const y = (await first.boundingBox())!.y;
  await scroll.evaluate((element) => { element.dispatchEvent(new Event('wheel')); element.scrollTop = 700; });
  await expect(page.locator('.profile-compact-positioner')).toBeVisible();
  expect((await page.locator('.profile-header-compact').boundingBox())!.height).toBe(88);
  expect((await page.locator('.profile-header-compact').boundingBox())!.y).toBe(167);
  expect((await first.boundingBox())!.y).toBe(y - 700);
  expect((await page.locator('.profile-info').boundingBox())!.y).toBe(-140);
  await expect(page.getByRole('button', { name: '+ 关注', exact: true })).toHaveCount(1);
  expect(mock.reads()).toBe(1);
  await page.screenshot({ path: testInfo.outputPath('profile-compact.png') });
  await scroll.evaluate((element) => { element.scrollTop = 0; });
  await expect(page.locator('.profile-compact-positioner')).toBeHidden();
  await expect(page.getByRole('heading', { name: 'Flame', exact: true })).toBeVisible();
});

for (const viewport of [{ width: 1440, height: 768 }, { width: 1920, height: 1080 }, { width: 390, height: 844 }]) {
  test(`统一滚动与桌面画布 ${viewport.width}×${viewport.height}`, async ({ page }, testInfo) => {
    await page.setViewportSize(viewport);
    await mockProfile(page);
    await openProfile(page);
    const scroll = page.locator('.profile-scroll');
    await scroll.evaluate((element) => { element.dispatchEvent(new Event('wheel')); element.scrollTop = 800; });
    await expect(page.locator('.profile-compact-positioner')).toBeVisible();
    expect((await scroll.boundingBox())!.height).toBe(viewport.height);
    expect(await page.locator('.profile-articles').evaluate((element) => element.scrollTop)).toBe(0);
    if (viewport.width < 1440) {
      await scroll.evaluate((element) => { element.scrollLeft = 800; });
      expect(await scroll.evaluate((element) => element.scrollLeft)).toBeGreaterThan(0);
      expect((await page.locator('.profile-header-compact').boundingBox())!.x).toBe((await page.locator('.profile-main').boundingBox())!.x);
    }
    expect(await page.evaluate(() => ({ x: scrollX, y: scrollY }))).toEqual({ x: 0, y: 0 });
    await page.screenshot({ path: testInfo.outputPath('profile-viewport.png') });
  });
}
