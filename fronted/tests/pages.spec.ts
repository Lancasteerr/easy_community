import { expect, test, type Page } from '@playwright/test';
import { article, profile } from '../src/test/fixtures';

/** 接口只在测试浏览器内拦截；正式页面不带演示文章或模拟登录开关。 */
async function guest(page: Page) {
  await page.route('**/api/users/me', (route) => route.fulfill({ status: 401, json: { code: 'AUTHENTICATION_REQUIRED' } }));
  await page.route('**/test-cover.png', (route) => route.fulfill({ path: 'src/assets/home-banner.png', contentType: 'image/png' }));
}

const cover = '/test-cover.png';
const firstPage = [1, 2, 3].map((id) => ({ ...article(String(id)), coverUrl: cover }));

test('主页还原三栏、触底续页、吸顶与卡片缺图状态', async ({ page }, testInfo) => {
  await guest(page);
  let requests = 0;
  await page.route('**/api/articles/feed?**', (route) => {
    requests++;
    return route.fulfill({ json: new URL(route.request().url()).searchParams.has('cursor')
      ? { items: [article('4'), { ...article('5'), coverUrl: '/missing-cover.png' }, { ...article('6'), title: '很长的标题'.repeat(30), summary: '很长的摘要'.repeat(60), likedByMe: true }], nextCursor: null }
      : { items: firstPage, nextCursor: 'next+/=' } });
  });
  await page.goto('/');
  await expect(page.locator('.article-card')).toHaveCount(3);
  await page.evaluate(() => document.fonts.ready);
  const header = await page.locator('.home-header').boundingBox();
  expect(header).toMatchObject({ x: 106.5, y: 34, width: 1227, height: 90 });
  expect((await page.locator('.search-form').boundingBox())?.x).toBe(736.5);
  expect((await page.locator('.home-main').boundingBox())?.x).toBe(348.5);
  expect(requests).toBe(1);
  await page.screenshot({ path: testInfo.outputPath('home-desktop.png') });
  await page.evaluate(() => window.scrollTo(0, 1400));
  await expect(page.locator('.article-card')).toHaveCount(6);
  await expect(page.getByText('已经到底了')).toBeVisible();
  expect((await page.locator('.home-header').boundingBox())?.y).toBe(34);
  expect((await page.locator('.home-sidebar').boundingBox())?.y).toBe(167);
  expect((await page.locator('.publish-panel').boundingBox())?.y).toBe(167);
  await expect(page.getByText('封面暂时无法显示')).toHaveCount(1);
  await expect(page.locator('.article-card').nth(3).locator('.article-cover')).toHaveCount(0);
  await expect(page.locator('.article-card').last().getByRole('button', { name: '点赞 328' })).toHaveAttribute('aria-pressed', 'true');
  const title = page.locator('.article-card').last().locator('h2');
  expect((await title.boundingBox())!.height).toBeLessThanOrEqual(52);
  expect(await page.locator('.home-main').evaluate((element) => element.scrollTop)).toBe(0);
  await page.screenshot({ path: testInfo.outputPath('home-scrolled.png') });
});

test('主页窄屏允许横滚，宽屏背景持续覆盖窗口', async ({ page }, testInfo) => {
  await guest(page);
  await page.route('**/api/articles/feed?**', (route) => route.fulfill({ json: { items: [], nextCursor: null } }));
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/');
  await expect(page.getByText('还没有推荐内容，稍后再来看看吧')).toBeVisible();
  expect(await page.evaluate(() => ({ width: document.documentElement.scrollWidth, x: scrollX }))).toEqual({ width: 1440, x: 0 });
  expect(await page.locator('.home-page').evaluate((element) => getComputedStyle(element, '::before').width)).toBe('390px');
  await page.evaluate(() => window.scrollTo(1000, 0));
  expect(await page.evaluate(() => scrollX)).toBeGreaterThan(0);
  await page.setViewportSize({ width: 1920, height: 1080 });
  expect((await page.locator('.home-header').boundingBox())?.x).toBe(346.5);
  expect(await page.locator('.home-page').evaluate((element) => getComputedStyle(element, '::before').height)).toBe('1080px');
  await page.screenshot({ path: testInfo.outputPath('home-wide.png') });
});

test('登录页桌面布局、固定反馈、密码显隐与认证失败', async ({ page }, testInfo) => {
  await guest(page);
  await page.route('**/api/auth/csrf', (route) => route.fulfill({ json: { token: 'masked', headerName: 'X-CSRF-TOKEN' } }));
  await page.route('**/api/auth/login', (route) => route.fulfill({ status: 401, json: { code: 'INVALID_CREDENTIALS' } }));
  await page.goto('/login');
  await page.evaluate(() => document.fonts.ready);
  expect(await page.locator('.login-panel').boundingBox()).toMatchObject({ x: 720, y: 0, width: 720, height: 1024 });
  expect(await page.locator('.login-form').boundingBox()).toMatchObject({ x: 880, y: 408, width: 400, height: 296 });
  await page.screenshot({ path: testInfo.outputPath('login-desktop.png') });
  const submit = page.getByRole('button', { name: '登录', exact: true });
  await submit.click();
  await expect(page.getByLabel('账号', { exact: true })).toBeFocused();
  expect((await submit.boundingBox())?.y).toBe(656);
  await page.getByLabel('账号', { exact: true }).fill('Alice_1');
  await page.getByLabel('密码', { exact: true }).fill('secret123');
  await page.getByRole('button', { name: '显示密码' }).click();
  await expect(page.getByLabel('密码', { exact: true })).toHaveAttribute('type', 'text');
  await page.getByRole('button', { name: '隐藏密码' }).click();
  await page.getByLabel('密码', { exact: true }).press('Enter');
  await expect(page.getByText('账号或密码错误，请重试')).toBeVisible();
  expect((await submit.boundingBox())?.y).toBe(656);
  await page.screenshot({ path: testInfo.outputPath('login-error.png') });
});

test('窄屏登录优先显示表单，短窗口可以滚动访问返回入口', async ({ page }, testInfo) => {
  await guest(page);
  await page.setViewportSize({ width: 390, height: 500 });
  await page.goto('/login');
  await expect(page.locator('.login-decoration')).toBeHidden();
  expect((await page.locator('.login-form').boundingBox())?.width).toBe(342);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
  await page.getByRole('link', { name: '返回首页' }).scrollIntoViewIfNeeded();
  expect(await page.evaluate(() => scrollY)).toBeGreaterThan(0);
  await page.screenshot({ path: testInfo.outputPath('login-narrow.png'), fullPage: true });
});

test('游客头像、CSRF、HttpOnly 会话与登录后刷新推荐形成闭环', async ({ page, context }) => {
  let signedIn = false;
  let feedReads = 0;
  let csrfReads = 0;
  let loginWrites = 0;
  const businessPaths = new Set<string>();
  page.on('request', (request) => { const path = new URL(request.url()).pathname; if (path.startsWith('/api/')) businessPaths.add(path); });
  await page.route('**/api/users/me', (route) => route.fulfill(signedIn ? { json: { ...profile, avatarUrl: '/missing-avatar.png' } } : { status: 401, json: { code: 'AUTHENTICATION_REQUIRED' } }));
  await page.route('**/api/articles/feed?**', (route) => {
    feedReads++;
    return route.fulfill({ json: { items: [{ ...article(), likedByMe: signedIn }], nextCursor: null } });
  });
  await page.route('**/api/auth/csrf', (route) => {
    csrfReads++;
    return route.fulfill({ json: { token: `masked-${csrfReads}`, headerName: 'X-CSRF-TOKEN' } });
  });
  await page.route('**/api/auth/login', (route) => {
    loginWrites++;
    expect(route.request().headers()['x-csrf-token']).toBe('masked-1');
    expect(route.request().postDataJSON()).toEqual({ username: 'Alice_1', password: '  secret123  ' });
    signedIn = true;
    return route.fulfill({ json: profile, headers: { 'Set-Cookie': 'SESSION=browser-test; HttpOnly; Path=/; SameSite=Lax' } });
  });
  await page.goto('/');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page).toHaveURL(/\/login$/);
  await page.getByLabel('账号', { exact: true }).fill(' Alice_1 ');
  await page.getByLabel('密码', { exact: true }).fill('  secret123  ');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page).toHaveURL(/:5175\/$/);
  await expect(page.getByRole('button', { name: `${profile.username} 的个人中心` })).toBeVisible();
  await expect(page.locator('.avatar-entry img')).toHaveAttribute('src', /default-avatar/);
  await expect(page.getByRole('button', { name: '点赞 328' })).toHaveAttribute('aria-pressed', 'true');
  expect(feedReads).toBe(2);
  expect(csrfReads).toBe(2);
  expect(loginWrites).toBe(1);
  expect((await context.cookies()).find((cookie) => cookie.name === 'SESSION')?.httpOnly).toBe(true);
  expect(await page.evaluate(() => document.cookie)).not.toContain('SESSION');
  await page.getByRole('searchbox', { name: '搜索内容' }).fill('设计');
  await page.getByRole('button', { name: '搜索', exact: true }).click();
  await expect(page.getByText('功能暂未开放')).toBeVisible();
  await page.getByRole('button', { name: '热门', exact: true }).click();
  expect(businessPaths).toEqual(new Set(['/api/users/me', '/api/articles/feed', '/api/auth/csrf', '/api/auth/login']));
  await page.goto('/login');
  await expect(page).toHaveURL(/:5175\/$/);
});

test('游标过期保留文章，用户重载才替换列表', async ({ page }) => {
  await guest(page);
  let firstReads = 0;
  await page.route('**/api/articles/feed?**', (route) => {
    if (new URL(route.request().url()).searchParams.has('cursor')) return route.fulfill({ status: 409, json: { code: 'ARTICLE_CURSOR_EXPIRED' } });
    firstReads++;
    return route.fulfill({ json: { items: [{ ...article(firstReads === 1 ? 'old' : 'fresh'), title: firstReads === 1 ? '已读文章' : '刷新后的文章' }], nextCursor: firstReads === 1 ? 'expired' : null } });
  });
  await page.goto('/');
  await expect(page.getByText('列表已过期，已读内容仍保留')).toBeVisible();
  await expect(page.getByRole('heading', { name: '已读文章' })).toBeVisible();
  expect(firstReads).toBe(1);
  await page.getByRole('button', { name: '重新加载' }).click();
  await expect(page.getByRole('heading', { name: '刷新后的文章' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '已读文章' })).toHaveCount(0);
  expect(await page.evaluate(() => scrollY)).toBe(0);
});

test('登录限流期间不重发请求，倒计时结束恢复按钮', async ({ page }) => {
  await guest(page);
  let attempts = 0;
  await page.route('**/api/auth/csrf', (route) => route.fulfill({ json: { token: 'masked', headerName: 'X-CSRF-TOKEN' } }));
  await page.route('**/api/auth/login', (route) => { attempts++; return route.fulfill({ status: 429, headers: { 'Retry-After': '1' }, json: { code: 'RATE_LIMITED' } }); });
  await page.goto('/login');
  await page.getByLabel('账号', { exact: true }).fill('Alice_1');
  await page.getByLabel('密码', { exact: true }).fill('secret123');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('button', { name: /秒后重试/ })).toBeDisabled();
  await expect(page.getByRole('button', { name: '登录', exact: true })).toBeEnabled();
  expect(attempts).toBe(1);
});

test('无效游标可通过重新加载恢复，保持键盘焦点可见', async ({ page }) => {
  await guest(page);
  let reset = false;
  await page.route('**/api/articles/feed?**', (route) => {
    if (new URL(route.request().url()).searchParams.has('cursor')) return route.fulfill({ status: 400, json: { code: 'ARTICLE_SEARCH_INVALID' } });
    return route.fulfill({ json: { items: [article()], nextCursor: reset ? null : 'invalid' } });
  });
  await page.goto('/');
  await expect(page.getByText('请求参数无效，请重新加载')).toBeVisible();
  const button = page.getByRole('button', { name: '重新加载' });
  await button.focus();
  await expect(button).toBeFocused();
  expect(await button.evaluate((element) => getComputedStyle(element).outlineStyle)).toBe('solid');
  reset = true;
  await button.press('Enter');
  await expect(page.getByText('已经到底了')).toBeVisible();
});
