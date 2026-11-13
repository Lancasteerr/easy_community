import { expect, test, type Page } from '@playwright/test';
import { article, profile } from '../src/test/fixtures';

/** 接口只在测试浏览器内拦截；正式页面不带演示文章或模拟登录开关。 */
async function guest(page: Page) {
  await page.route('**/api/users/me', (route) => route.fulfill({ status: 401, json: { code: 'AUTHENTICATION_REQUIRED' } }));
  await page.route('**/test-cover.png', (route) => route.fulfill({ path: 'src/assets/home-banner.png', contentType: 'image/png' }));
}

const cover = '/test-cover.png';
const firstPage = [1, 2, 3].map((id) => ({ ...article(String(id)), coverUrl: cover }));

test('首页搜索聚焦外框不跳动，导航悬浮与选中保持区分', async ({ page }, testInfo) => {
  await guest(page);
  await page.route('**/api/articles/feed?**', (route) => route.fulfill({ json: { items: firstPage, nextCursor: null } }));
  await page.goto('/');
  await page.evaluate(() => document.fonts.ready);
  const search = page.getByRole('searchbox', { name: '搜索内容' });
  const form = page.getByRole('search');
  const originalBounds = await form.boundingBox();
  await search.click();
  await expect(search).toBeFocused();
  await expect(search).toHaveCSS('outline-style', 'none');
  await expect(form).toHaveCSS('box-shadow', 'rgb(155, 206, 255) 0px 0px 0px 2px');
  expect(await form.boundingBox()).toEqual(originalBounds);
  // 在输入框与提交按钮之间切换，完整外环持续可见，按钮有独立键盘焦点。
  await search.press('Tab');
  const submit = page.getByRole('button', { name: '搜索', exact: true });
  await expect(submit).toBeFocused();
  await expect(submit).toHaveCSS('outline-style', 'solid');
  await submit.press('Shift+Tab');
  await expect(search).toBeFocused();
  await expect(form).toHaveCSS('box-shadow', 'rgb(155, 206, 255) 0px 0px 0px 2px');

  const popular = page.getByRole('button', { name: '热门', exact: true });
  await popular.hover();
  await expect(popular).toHaveCSS('background-color', 'rgba(60, 60, 67, 0.6)');
  await expect(popular).toHaveCSS('color', 'rgb(117, 117, 117)');
  await expect(popular.locator('.icon')).toHaveCSS('color', 'rgb(117, 117, 117)');
  await expect(popular).toHaveCSS('border-radius', '10px');
  // 共享标题线紧贴标题组，左右两侧都采用相同的圆头线条。
  for (const heading of await page.locator('.panel-heading').all()) {
    const line = await heading.evaluate((element) => {
      const style = getComputedStyle(element, '::after');
      return { height: style.height, radius: style.borderRadius, width: parseFloat(style.width), headingWidth: element.getBoundingClientRect().width };
    });
    expect(line.height).toBe('2px');
    expect(line.radius).toBe('1px');
    expect(line.width).toBeCloseTo(line.headingWidth, 1);
  }
  await page.screenshot({ path: testInfo.outputPath('home-search-focus-nav-hover.png') });
  const selected = page.getByRole('button', { name: '推荐', exact: true });
  await selected.hover();
  await expect(selected).toHaveCSS('color', 'rgb(155, 206, 255)');
  await expect(selected).toHaveCSS('background-color', 'rgba(155, 206, 255, 0.15)');
  await expect(popular).toHaveCSS('background-color', 'rgba(0, 0, 0, 0)');
  await selected.click();
  await expect(form).toHaveCSS('box-shadow', 'none');
});

test('主页作者头像支持真实加载、空地址及加载失败回退', async ({ page }) => {
  await guest(page);
  const avatar = '/test-author-avatar.jpg';
  const userRequests: string[] = [];
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname;
    if (path.startsWith('/api/users/')) userRequests.push(path);
  });
  // 使用现有本地图片和确定的404，实际验证浏览器解码与组件回退。
  await page.route('**/test-author-avatar.jpg', (route) => route.fulfill({ path: 'src/assets/default-avatar.jpg', contentType: 'image/jpeg' }));
  await page.route('**/missing-author-avatar.jpg', (route) => route.fulfill({ status: 404, body: '' }));
  await page.route('**/api/articles/feed?**', (route) => route.fulfill({ json: {
    items: [
      { ...article('1'), authorAvatarUrl: avatar },
      article('2'),
      { ...article('3'), authorAvatarUrl: '/missing-author-avatar.jpg' },
    ],
    nextCursor: null,
  } }));
  await page.goto('/');
  const avatars = page.locator('.article-card .author-entry img');
  await expect(avatars).toHaveCount(3);
  await expect(avatars.nth(0)).toHaveAttribute('src', avatar);
  await expect(avatars.nth(1)).toHaveAttribute('src', /default-avatar/);
  await expect(avatars.nth(2)).toHaveAttribute('src', /default-avatar/);
  for (const image of await avatars.all()) {
    await expect.poll(() => image.evaluate((element: HTMLImageElement) => element.complete && element.naturalWidth > 0)).toBe(true);
    await expect(image).toHaveAttribute('width', '36');
    await expect(image).toHaveAttribute('height', '36');
  }
  await expect(page.locator('.article-card h2')).toHaveText(['文章 1', '文章 2', '文章 3']);
  await expect(page.getByText('已经到底了')).toBeVisible();
  // 除既有登录态读取外，不能为信息流作者额外查询用户资料。
  expect(userRequests.filter((path) => path !== '/api/users/me')).toEqual([]);
});

for (const viewport of [
  { name: 'desktop', width: 1440, height: 1024 },
  { name: 'wide', width: 1920, height: 1080 },
  { name: 'short', width: 1440, height: 768 },
]) {
  test(`主页横幅与信息流统一滚动、顶部裁切与触底续页（${viewport.name}）`, async ({ page }, testInfo) => {
    await guest(page);
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
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
    const headerX = (viewport.width - 1227) / 2;
    expect(header).toMatchObject({ x: headerX, y: 34, width: 1227, height: 90 });
    expect((await page.locator('.search-form').boundingBox())?.x).toBe(headerX + 630);
    expect((await page.locator('.home-main').boundingBox())?.x).toBe(headerX + 242);
    const feed = page.getByRole('region', { name: '推荐文章' });
    const scroller = page.getByRole('region', { name: '首页滚动区域' });
    // 原生滚动轨道从窗口顶端延伸到底端，内容可见边界仍保持在 167px。
    expect(await scroller.boundingBox()).toMatchObject({ x: 0, y: 0, width: viewport.width, height: viewport.height });
    expect(await feed.boundingBox()).toMatchObject({ x: headerX + 242, y: 411, width: 681 });
    await expect(feed).not.toHaveAttribute('tabindex');
    await expect(feed).toHaveCSS('overflow-y', 'visible');
    expect(await feed.evaluate((element) => element.clientHeight)).toBeGreaterThan(viewport.height - 411);
    // 文档不滚动，只有通栏增长；导航和两侧栏的位置与尺寸始终不变。
    expect(await page.evaluate(() => document.documentElement.scrollHeight)).toBe(viewport.height);
    const fixedRegions = page.locator('.home-header, .home-sidebar, .publish-panel');
    const originalBounds = await Promise.all((await fixedRegions.all()).map((region) => region.boundingBox()));
    expect(requests).toBe(1);
    await page.screenshot({ path: testInfo.outputPath(`home-${viewport.name}.png`) });
    const bannerBefore = (await page.locator('.home-banner').boundingBox())!;
    const feedBefore = (await feed.boundingBox())!;
    // 导航也在全视口滚动容器中，在导航上使用滚轮同样推动中间内容。
    await page.mouse.move(bannerBefore.x + 300, 80);
    await page.mouse.wheel(0, 100);
    await expect.poll(() => scroller.evaluate((element) => element.scrollTop)).toBeGreaterThan(0);
    const firstScroll = await scroller.evaluate((element) => element.scrollTop);
    expect((await page.locator('.home-banner').boundingBox())!.y).toBeCloseTo(bannerBefore.y - firstScroll, 1);
    expect((await feed.boundingBox())!.y).toBeCloseTo(feedBefore.y - firstScroll, 1);
    // 部分横幅滚过裁切线时，上方留白不能命中横幅，区域内仍可以点击横幅。
    expect(await page.evaluate((x) => ({
      above: !!document.elementFromPoint(x, 150)?.closest('.home-banner, .article-card'),
      inside: !!document.elementFromPoint(x, 180)?.closest('.home-banner'),
    }), bannerBefore.x + 300)).toEqual({ above: false, inside: true });
    await page.mouse.click(bannerBefore.x + 300, 180);
    await expect(page.getByText('功能暂未开放')).toBeVisible();
    await page.mouse.wheel(0, 1400);
    await expect(page.locator('.article-card')).toHaveCount(6);
    expect(requests).toBe(2);
    await scroller.evaluate((element) => element.scrollTo({ top: element.scrollHeight, behavior: 'instant' }));
    await expect(page.getByText('已经到底了')).toBeVisible();
    // 触底继续滚动不传递给页面；导航及其周围留白不能被滚动内容命中覆盖。
    await page.mouse.wheel(0, 1000);
    expect(await page.evaluate(() => scrollY)).toBe(0);
    expect(await page.evaluate(() => document.documentElement.scrollHeight)).toBe(viewport.height);
    for (const [index, region] of (await fixedRegions.all()).entries()) {
      const bounds = (await region.boundingBox())!;
      expect(bounds).toMatchObject({ x: originalBounds[index]!.x, width: originalBounds[index]!.width, height: originalBounds[index]!.height });
      // 触底高度取整可能带来不足半像素的 sticky 边界差异。
      expect(bounds.y).toBeCloseTo(originalBounds[index]!.y, 0);
    }
    expect(await page.evaluate((x) => [20, 80, 150, 166].every((y) =>
      !document.elementFromPoint(x, y)?.closest('.home-banner, .article-card')), bannerBefore.x + 300)).toBe(true);
    await page.getByRole('searchbox', { name: '搜索内容' }).fill('滚动后搜索');
    await page.getByRole('button', { name: '搜索', exact: true }).click();
    await expect(page.getByText('功能暂未开放')).toBeVisible();
    await expect(page.getByText('封面暂时无法显示')).toHaveCount(1);
    await expect(page.locator('.article-card').nth(3).locator('.article-cover')).toHaveCount(0);
    await expect(page.locator('.article-card').last().getByRole('button', { name: '取消点赞 328' })).toHaveAttribute('aria-pressed', 'true');
    const title = page.locator('.article-card').last().locator('h2');
    expect((await title.boundingBox())!.height).toBeLessThanOrEqual(52);
    expect(await page.locator('.home-main').evaluate((element) => element.scrollTop)).toBe(0);
    expect(await feed.evaluate((element) => element.scrollTop)).toBe(0);
    await page.screenshot({ path: testInfo.outputPath(`home-${viewport.name}-scrolled.png`) });
  });
}

test('滚动回调未执行时顶部边界仍稳定，连续双向滚动不会侵入导航留白', async ({ page }) => {
  await guest(page);
  await page.route('**/api/articles/feed?**', (route) => route.fulfill({ json: { items: firstPage, nextCursor: null } }));
  // 屏蔽滚动回调，重现原生滚动已发生、JS 裁切参数尚未更新的情况。
  await page.addInitScript(() => window.addEventListener('scroll', (event) => event.stopImmediatePropagation(), true));
  await page.goto('/');
  await expect(page.locator('.article-card')).toHaveCount(3);
  await page.evaluate(() => document.fonts.ready);
  const scroller = page.getByRole('region', { name: '首页滚动区域' });
  const x = (await page.locator('.home-main').boundingBox())!.x + 300;
  await page.mouse.move(x, 450);
  let top = 0;
  for (const delta of [64, 64, 64, 64, -64, -64, -64, -64]) {
    top += delta;
    await page.mouse.wheel(0, delta);
    await expect.poll(() => scroller.evaluate((element) => element.scrollTop)).toBe(top);
    // 对顶部最后一行像素也做命中验证，避免仅检测远离边界的位置而漏掉闪动。
    expect(await page.evaluate((left) => [150, 166, 166.5].every((y) =>
      !document.elementFromPoint(left, y)?.closest('.home-main')), x)).toBe(true);
    expect((await page.locator('.home-header').boundingBox())!.y).toBe(34);
    expect((await page.locator('.home-sidebar').boundingBox())!.y).toBe(167);
  }
});

test('主页窄屏允许横滚，宽屏背景持续覆盖窗口', async ({ page }, testInfo) => {
  await guest(page);
  await page.route('**/api/articles/feed?**', (route) => route.fulfill({ json: { items: [], nextCursor: null } }));
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/');
  await expect(page.getByText('还没有推荐内容，稍后再来看看吧')).toBeVisible();
  const scroller = page.getByRole('region', { name: '首页滚动区域' });
  expect(await page.evaluate(() => ({ width: document.documentElement.scrollWidth, x: scrollX }))).toEqual({ width: 390, x: 0 });
  expect(await scroller.evaluate((element) => element.scrollWidth)).toBe(1440);
  expect(await scroller.boundingBox()).toMatchObject({ x: 0, width: 390 });
  expect(await page.locator('.home-page').evaluate((element) => getComputedStyle(element, '::before').width)).toBe('390px');
  await scroller.evaluate((element) => element.scrollTo({ left: 700, behavior: 'instant' }));
  await expect.poll(() => page.locator('.home-header').evaluate((element) => element.getBoundingClientRect().x)).toBe(106.5 - 700);
  expect((await page.locator('.home-main').boundingBox())!.x).toBe(106.5 + 242 - 700);
  // 导航与三栏共用画布，聚焦屏幕外的导航操作仍保持横向对齐。
  await page.getByRole('link', { name: 'Easy Community', exact: true }).focus();
  await expect.poll(() => scroller.evaluate((element) => element.scrollLeft)).toBeLessThan(700);
  expect((await page.locator('.home-main').boundingBox())!.x - (await page.locator('.home-header').boundingBox())!.x).toBe(242);
  await page.setViewportSize({ width: 1920, height: 1080 });
  await expect.poll(() => page.locator('.home-header').evaluate((element) => element.getBoundingClientRect().x)).toBe(346.5);
  expect(await page.locator('.home-page').evaluate((element) => getComputedStyle(element, '::before').height)).toBe('1080px');
  await page.screenshot({ path: testInfo.outputPath('home-wide.png') });
});

test('首页短列表自动补页，通栏焦点支持键盘翻页', async ({ page }) => {
  await guest(page);
  let reads = 0;
  await page.route('**/api/articles/feed?**', (route) => {
    reads++;
    return route.fulfill({ json: new URL(route.request().url()).searchParams.has('cursor')
      ? { items: firstPage, nextCursor: null }
      : { items: [article('10')], nextCursor: 'short-page' } });
  });
  await page.goto('/');
  await expect(page.locator('.article-card')).toHaveCount(4);
  expect(reads).toBe(2);
  const scroller = page.getByRole('region', { name: '首页滚动区域' });
  await scroller.focus();
  await expect(scroller).toHaveCSS('outline-style', 'solid');
  await scroller.press('PageDown');
  await expect.poll(() => scroller.evaluate((element) => element.scrollTop)).toBeGreaterThan(0);
  expect(await page.evaluate(() => scrollY)).toBe(0);
});

test('文章详情使用通栏滚动，后退恢复首页通栏阅读位置和横向对齐', async ({ page }) => {
  await guest(page);
  await page.setViewportSize({ width: 1280, height: 844 });
  let reads = 0;
  await page.route('**/api/articles/feed?**', (route) => {
    reads++;
    return route.fulfill({ json: { items: firstPage, nextCursor: null } });
  });
  await page.route('**/api/articles/2', (route) => route.fulfill({ json: {
    ...article('2'), coverMediaId: null, contentHtml: '<p>详情阅读内容</p>'.repeat(60),
  } }));
  await page.goto('/');
  await expect(page.locator('.article-card')).toHaveCount(3);
  await page.evaluate(() => document.fonts.ready);
  const scroller = page.getByRole('region', { name: '首页滚动区域' });
  await page.mouse.move(650, 450);
  await page.mouse.wheel(0, 700);
  await expect.poll(() => scroller.evaluate((element) => element.scrollTop)).toBe(700);
  await scroller.evaluate((element) => element.scrollTo({ left: 100, top: 700, behavior: 'instant' }));
  const position = await scroller.evaluate((element) => ({ top: element.scrollTop, left: element.scrollLeft }));
  const secondArticle = page.getByRole('link', { name: '文章 2', exact: true });
  const articleTop = (await secondArticle.boundingBox())!.y;
  await secondArticle.click();
  await expect(page).toHaveURL(/\/articles\/2$/);
  const reader = page.getByRole('region', { name: '文章详情滚动区域' });
  await expect(reader).toBeVisible();
  await reader.press('PageDown');
  await expect.poll(() => reader.evaluate((element) => element.scrollTop)).toBeGreaterThan(0);
  expect(await page.evaluate(() => scrollY)).toBe(0);
  await page.goBack();
  await expect(page.locator('.article-card')).toHaveCount(3);
  await expect.poll(() => scroller.evaluate((element) => ({ top: element.scrollTop, left: element.scrollLeft }))).toEqual(position);
  expect((await secondArticle.boundingBox())!.y).toBeCloseTo(articleTop, 1);
  await expect.poll(async () => (await page.locator('.home-main').boundingBox())!.x
    - (await page.locator('.home-header').boundingBox())!.x).toBe(242);
  expect(reads).toBe(1);
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
  await expect(page.getByRole('button', { name: '取消点赞 328' })).toHaveAttribute('aria-pressed', 'true');
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

test('游标过期保留文章，重载让横幅与信息流回顶并保留横向位置', async ({ page }) => {
  await guest(page);
  await page.setViewportSize({ width: 1280, height: 768 });
  let firstReads = 0;
  await page.route('**/api/articles/feed?**', (route) => {
    if (new URL(route.request().url()).searchParams.has('cursor')) return route.fulfill({ status: 409, json: { code: 'ARTICLE_CURSOR_EXPIRED' } });
    firstReads++;
    // 刷新前后均提供超过一屏的文章，避免短列表自然回顶掩盖重置逻辑缺失。
    return route.fulfill({ json: {
      items: firstPage.map((item, index) => ({ ...item, articleId: `${firstReads}-${index}`, title: index === 0 ? (firstReads === 1 ? '已读文章' : '刷新后的文章') : item.title })),
      nextCursor: firstReads === 1 ? 'expired' : null,
    } });
  });
  await page.goto('/');
  await expect(page.locator('.article-card')).toHaveCount(3);
  const scroller = page.getByRole('region', { name: '首页滚动区域' });
  await scroller.evaluate((element) => element.scrollTo({ left: 100, behavior: 'instant' }));
  const horizontalPosition = await scroller.evaluate((element) => element.scrollLeft);
  expect(horizontalPosition).toBeGreaterThan(0);
  await page.mouse.move(650, 450);
  await page.mouse.wheel(0, 2500);
  await expect(page.getByText('列表已过期，已读内容仍保留')).toBeVisible();
  await expect(page.getByRole('heading', { name: '已读文章' })).toBeVisible();
  expect(firstReads).toBe(1);
  expect(await scroller.evaluate((element) => element.scrollTop)).toBeGreaterThan(0);
  await page.getByRole('button', { name: '重新加载' }).click();
  await expect(page.getByRole('heading', { name: '刷新后的文章' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '已读文章' })).toHaveCount(0);
  expect(firstReads).toBe(2);
  expect(await scroller.evaluate((element) => element.scrollTop)).toBe(0);
  expect((await page.locator('.home-banner').boundingBox())!.y).toBe(167);
  expect(await page.evaluate(() => scrollY)).toBe(0);
  expect(await scroller.evaluate((element) => element.scrollLeft)).toBe(horizontalPosition);
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
