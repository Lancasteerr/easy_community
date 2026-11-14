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
  await context.route('**/api/articles/feed?**', (route) => route.fulfill({ json: { items: [article('5')], nextCursor: null } }));
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
  expect((await page.locator('.profile-header').boundingBox())!.height).toBe(376);
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
  await expect(page.locator('.profile-scroll[data-profile-collapsed="true"]')).toBeVisible();
  expect((await page.locator('.profile-header').boundingBox())!.height).toBe(88);
  expect((await page.locator('.profile-header').boundingBox())!.y).toBe(167);
  expect((await first.boundingBox())!.y).toBe(y - 700);
  expect((await page.locator('.profile-info').boundingBox())!.y).toBe(-140);
  await expect(page.getByRole('button', { name: '+ 关注', exact: true })).toHaveCount(1);
  expect(mock.reads()).toBe(1);
  await page.screenshot({ path: testInfo.outputPath('profile-compact.png') });
  await scroll.evaluate((element) => { element.scrollTop = 0; });
  await expect(page.locator('.profile-scroll[data-profile-collapsed="true"]')).toBeHidden();
  await expect(page.getByRole('heading', { name: 'Flame', exact: true })).toBeVisible();
});

test('资料卡连续收缩，头像沿用同一节点，正反滚动保持上下间距与不透明底色', async ({ page }, testInfo) => {
  const mock = await mockProfile(page);
  await openProfile(page);
  const scroll = page.locator('.profile-scroll');
  const card = page.locator('.profile-header');
  const avatar = page.locator('.profile-identity-row > .avatar');
  const originalAvatar = await avatar.elementHandle();
  const edit = page.getByRole('link', { name: '编辑资料', exact: true });
  await edit.evaluate(element => element.focus({ preventScroll: true }));
  const measures: { scroll: number; avatar: number; height: number }[] = [];
  for (const selector of ['.profile-header', '.profile-info', '.profile-articles', '.profile-list-heading']) {
    await expect(page.locator(selector)).toHaveCSS('background-color', 'rgb(33, 33, 33)');
  }
  await expect(page.locator('.profile-article-card').first()).toHaveCSS('background-color', 'rgba(0, 0, 0, 0)');
  for (const top of [0, 96, 176, 200, 222, 244, 266, 288, 400, 700, 266, 222, 96, 0]) {
    await scroll.evaluate((element, value) => { element.dispatchEvent(new Event('wheel')); element.scrollTop = value; }, top);
    await expect.poll(async () => (await card.boundingBox())!.height).toBe(Math.max(88, 376 - top));
    const bounds = (await card.boundingBox())!;
    const header = (await page.locator('.home-header').boundingBox())!;
    const heading = (await page.locator('.profile-list-heading').boundingBox())!;
    expect(bounds.y - header.y - header.height).toBeCloseTo(43, 1);
    expect(heading.y - bounds.y - bounds.height).toBeCloseTo(17, 1);
    expect(await avatar.evaluate((element, original) => element === original, originalAvatar)).toBe(true);
    expect(await page.locator('.profile-header').count()).toBe(1);
    expect(await page.getByRole('link', { name: '编辑资料', exact: true }).count()).toBe(1);
    await expect(edit).toBeFocused();
    measures.push({ scroll: top, avatar: (await avatar.boundingBox())!.width, height: bounds.height });
    if ([0, 244, 288].includes(top)) await page.screenshot({ path: testInfo.outputPath(`profile-morph-${top}.png`) });
  }
  const shrinking = measures.filter((_, index) => index >= 3 && index <= 7).map(item => item.avatar);
  expect(shrinking[0]).toBe(96);
  expect(shrinking.at(-1)).toBe(48);
  expect(shrinking.every((size, index) => index === 0 || size < shrinking[index - 1])).toBe(true);
  expect(measures.find(item => item.scroll === 222)?.avatar).toBeCloseTo(measures.at(-3)!.avatar, 2);
  expect(measures.at(-1)!.avatar).toBe(96);
  expect(mock.reads()).toBe(1);
});

test('空列表不强行增加滚动距离，卡片和发布栏始终在同一个滚动口', async ({ page }) => {
  await mockProfile(page, true, 0);
  await page.goto(`/users/${profile.userId}`);
  await expect(page.getByRole('link', { name: '去发布' })).toBeVisible();
  const scroll = page.locator('.profile-scroll');
  expect(await scroll.evaluate(element => element.scrollHeight)).toBeLessThanOrEqual(1024);
  await expect(page.locator('.profile-header')).toHaveCSS('height', '376px');
  await expect(page.locator('.profile-scroll')).toHaveAttribute('data-profile-collapsed', 'false');
});

for (const viewport of [{ width: 1440, height: 768 }, { width: 1920, height: 1080 }, { width: 390, height: 844 }]) {
  test(`统一滚动与桌面画布 ${viewport.width}×${viewport.height}`, async ({ page }, testInfo) => {
    await page.setViewportSize(viewport);
    await mockProfile(page);
    await openProfile(page);
    const scroll = page.locator('.profile-scroll');
    await scroll.evaluate((element) => { element.dispatchEvent(new Event('wheel')); element.scrollTop = 800; });
    await expect(page.locator('.profile-scroll[data-profile-collapsed="true"]')).toBeVisible();
    expect((await scroll.boundingBox())!.height).toBe(viewport.height);
    expect(await page.locator('.profile-articles').evaluate((element) => element.scrollTop)).toBe(0);
    if (viewport.width < 1440) {
      await scroll.evaluate((element) => { element.scrollLeft = 800; });
      expect(await scroll.evaluate((element) => element.scrollLeft)).toBeGreaterThan(0);
      expect((await page.locator('.profile-header').boundingBox())!.x).toBe((await page.locator('.profile-main').boundingBox())!.x);
    }
    expect(await page.evaluate(() => ({ x: scrollX, y: scrollY }))).toEqual({ x: 0, y: 0 });
    await page.screenshot({ path: testInfo.outputPath('profile-viewport.png') });
  });
}

test('菜单主页新标签打开，作者入口当前页导航，文章后退恢复列表和锚点', async ({ page }) => {
  const mock = await mockProfile(page);
  await openProfile(page);
  await page.locator('.avatar-entry').hover();
  const popup = page.waitForEvent('popup');
  await page.getByRole('menuitem', { name: '个人主页' }).click();
  const personal = await popup;
  await expect(personal).toHaveURL(new RegExp(`/users/${profile.userId}$`));
  await personal.close();
  const scroll = page.locator('.profile-scroll');
  await scroll.evaluate((element) => { element.dispatchEvent(new Event('wheel')); element.scrollTop = 1700; });
  await expect(page.locator('.profile-scroll[data-profile-collapsed="true"]')).toBeVisible();
  // 先将目标文章放入实际可见区域，点击之后采集离页位置，避免测试自身自动滚入影响断言。
  const link = page.getByRole('link', { name: '文章 5', exact: true });
  await link.scrollIntoViewIfNeeded();
  const position = await scroll.evaluate((element) => element.scrollTop);
  const reads = mock.reads();
  await link.click();
  await expect(page).toHaveURL(/\/articles\/5$/);
  await expect(page.getByRole('link', { name: '查看作者 Flame' })).toHaveAttribute('href', `/users/${profile.userId}`);
  await page.goBack();
  await expect(page.locator('.profile-article-card')).toHaveCount(12);
  await expect.poll(() => scroll.evaluate((element) => element.scrollTop)).toBeCloseTo(position, 0);
  expect(mock.reads()).toBe(reads);
  await expect(page.locator('.profile-scroll[data-profile-collapsed="true"]')).toBeVisible();
  await page.goto('/articles/5');
  await page.getByRole('link', { name: '查看作者 Flame' }).click();
  await expect(page).toHaveURL(new RegExp(`/users/${profile.userId}$`));
  await page.goto('/');
  await page.getByRole('link', { name: '查看作者 Flame' }).click();
  await expect(page).toHaveURL(new RegExp(`/users/${profile.userId}$`));
});

test('游客点赞登录后返回公开主页，不重放旧点赞，登出后保留主页地址', async ({ page }) => {
  const mock = await mockProfile(page, false);
  await openProfile(page, '8');
  await page.getByRole('button', { name: '点赞 328', exact: true }).first().click();
  await expect(page).toHaveURL(/\/login$/);
  await page.getByRole('textbox', { name: '账号' }).fill('test_user');
  await page.getByLabel('密码', { exact: true }).fill('test_password');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page).toHaveURL(/\/users\/8$/);
  await expect(page.locator('.profile-article-card')).toHaveCount(12);
  expect(mock.likes()).toBe(0);
  await page.getByRole('button', { name: '点赞 328', exact: true }).first().click();
  await expect(page.getByRole('button', { name: '取消点赞 329', exact: true })).toBeVisible();
  expect(mock.likes()).toBe(1);
  await page.locator('.avatar-entry').hover();
  await page.getByRole('menuitem', { name: '退出登录' }).click();
  await expect(page.locator('.header-login')).toBeVisible();
  await expect(page).toHaveURL(/\/users\/8$/);
  await expect(page.getByRole('button', { name: '取消点赞 329', exact: true })).toHaveCount(0);
});

test('非法及不存在用户不启动列表，资料故障可手动重试，空列表保留样例统计', async ({ page }) => {
  const mock = await mockProfile(page, false, 0);
  await page.goto('/users/0');
  await expect(page.getByRole('heading', { name: '用户不存在' })).toBeVisible();
  expect(mock.reads()).toBe(0);
  await page.route('**/api/users/9', (route) => route.fulfill({ status: 404, json: { code: 'USER_NOT_FOUND' } }));
  await page.goto('/users/9');
  await expect(page.getByRole('heading', { name: '用户不存在' })).toBeVisible();
  expect(mock.reads()).toBe(0);
  let failed = true;
  await page.route('**/api/users/8', (route) => route.fulfill(failed
    ? { status: 503, json: { code: 'USER_BASIC_UNAVAILABLE' } }
    : { json: { ...profile, userId: '8' } }));
  await page.goto('/users/8');
  await expect(page.getByText('加载失败，请稍后重试')).toBeVisible();
  expect(mock.reads()).toBe(0);
  failed = false;
  await page.getByRole('button', { name: '重新加载' }).click();
  await expect(page.getByText('该用户还没有发布任何文章。')).toBeVisible();
  await expect(page.locator('.profile-stats').last()).toHaveText('12文章19关注1.2k粉丝');
  await expect(page.getByRole('link', { name: '去发布' })).toHaveCount(0);
});
