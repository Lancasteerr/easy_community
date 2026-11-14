import { mockPublicProfiles } from './public-profile-mock';
import { expect, test, type BrowserContext, type Page } from '@playwright/test';
import { article, profile } from '../src/test/fixtures';
import { emptyForm } from '../src/editor/model';

/** 同一浏览器上下文共享测试会话，覆盖新标签打开及随后刷新复核。 */
async function setup(context: BrowserContext) {
  const session = { authenticated: true };
  await context.route('**/api/auth/session', (route) => route.fulfill(session.authenticated
    ? { json: profile } : { status: 401, json: { code: 'AUTHENTICATION_REQUIRED' } }));
  await context.route('**/api/auth/csrf', (route) => route.fulfill({ json: { token: 'masked', headerName: 'X-CSRF-TOKEN' } }));
  await context.route('**/api/articles/feed?**', (route) => route.fulfill({ json: {
    items: Array.from({ length: 20 }, (_, i) => article(String(i + 1))), nextCursor: null,
  } }));
  await context.route('**/api/users/me/articles?**', (route) => route.fulfill({ json: {
    items: Array.from({ length: 12 }, (_, i) => article(String(i + 1))), nextCursor: null,
  } }));
  await context.route('**/api/articles/12', (route) => route.fulfill({ json: {
    ...article('12'), coverMediaId: null, contentHtml: '<p>公开正文</p>'.repeat(60),
  } }));
  await context.route('**/api/articles/12/edit', (route) => route.fulfill({ json: {
    ...emptyForm(), articleId: '12', title: '已有标题', createdAt: '', updatedAt: '', publishedAt: '',
  } }));
  return session;
}
async function openMenu(page: Page) {
  const trigger = page.getByRole('button', { name: `${profile.username} 的个人中心` });
  await expect(trigger).toBeVisible();
  // 直接移动到可见吸顶头像；locator.hover 的自动滚入视口会重置其祖先滚动框。
  const bounds = (await trigger.boundingBox())!;
  await page.mouse.move(bounds.x - 10, bounds.y + bounds.height / 2);
  await page.mouse.move(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
  const menu = page.getByRole('menu', { name: '用户菜单' }); await expect(menu).toBeVisible(); return menu;
}

for (const [path, scrollSelector] of [['/', '.home-scroll'], ['/manage/articles', '.manage-list'],
  ['/articles/12', '.detail-scroll'], ['/articles/new', '.article-editor-scroll']]) {
  test(`${path} 菜单尺寸、配色、三态、桥接和滚动层级符合设计`, async ({ page, context }, info) => {
    await setup(context); await page.goto(path); await page.evaluate(() => document.fonts.ready);
    const menu = await openMenu(page); const header = page.locator('.home-header');
    const bounds = (await header.boundingBox())!; const panel = (await menu.boundingBox())!;
    expect(bounds.height).toBe(90); expect(panel.width).toBe(240); expect(panel.height).toBe(209);
    expect(panel.x + panel.width).toBeCloseTo(bounds.x + bounds.width, 2);
    expect(panel.y - bounds.y - bounds.height).toBe(8);
    await expect(menu).toHaveCSS('background-color', 'rgb(39, 39, 44)');
    await expect(menu).toHaveCSS('border-radius', '12px'); await expect(menu).toHaveCSS('border-color', 'rgb(69, 69, 77)');
    await expect(menu).toHaveCSS('box-shadow', 'rgba(0, 0, 0, 0.24) 0px 8px 24px 0px');
    const first = menu.getByRole('menuitem', { name: '个人主页' });
    const firstBounds = (await first.boundingBox())!;
    expect(firstBounds).toMatchObject({ width: 224, height: 44 });
    await expect(first).toHaveCSS('color', 'rgb(238, 238, 242)');
    await expect(first).toHaveCSS('font-size', '14px'); await expect(first).toHaveCSS('line-height', '22px');
    await expect(first).toHaveCSS('gap', '12px'); await expect(first.locator('.icon')).toHaveCSS('width', '20px');
    // 指针穿过头像下方及 8px 间隙，浮层持续命中，不经过页面内容。
    const avatar = (await page.locator('.avatar-entry').boundingBox())!;
    await page.mouse.move(avatar.x + 20, bounds.y + bounds.height + 4);
    await expect(menu).toBeVisible();
    if (path === '/') {
      await expect(menu).toHaveCSS('opacity', '1');
      await page.screenshot({ path: info.outputPath('banner-menu-default.png'), clip: {
        x: bounds.x - 24, y: bounds.y - 16, width: bounds.width + 48, height: 355,
      } });
    }
    await first.hover(); await expect(first).toHaveCSS('color', 'rgb(152, 204, 255)');
    await expect(first).toHaveCSS('background-color', 'rgba(152, 204, 255, 0.15)');
    const popupPromise = page.waitForEvent('popup');
    await first.click();
    const profilePage = await popupPromise;
    await expect(profilePage).toHaveURL(new RegExp(`/users/${profile.userId}$`));
    await profilePage.close();
    await expect(page).toHaveURL(`http://127.0.0.1:5175${path}`);
    await openMenu(page);
    const settingsPopup = page.waitForEvent('popup');
    await menu.getByRole('menuitem', { name: '设置' }).click();
    const settingsPage = await settingsPopup;
    await expect(settingsPage).toHaveURL(/\/settings\/profile$/);
    await settingsPage.close();
    await expect(page.locator('.toast')).toBeEmpty();
    // 链接激活会关闭菜单；重新打开后再检查 Esc 的焦点归还。
    await openMenu(page);
    await page.keyboard.press('Escape'); const trigger = page.locator('.avatar-entry'); await expect(trigger).toBeFocused();
    await page.keyboard.press('Enter'); await expect(first).toBeFocused();
    await expect(first).toHaveCSS('outline-color', 'rgb(152, 204, 255)'); await expect(first).toHaveCSS('outline-width', '1px');
    await expect(menu).toHaveCSS('opacity', '1');
    await page.screenshot({ path: info.outputPath('banner-menu-focused.png'), clip: {
      x: bounds.x - 24, y: bounds.y - 16, width: bounds.width + 48, height: 355,
    } });
    await page.locator(scrollSelector).evaluate((element) => { element.scrollTop = 400; });
    const afterScroll = (await menu.boundingBox())!; expect(afterScroll).toMatchObject({ x: panel.x, y: panel.y });
    expect(await first.evaluate((element) => {
      const r = element.getBoundingClientRect(); return element.contains(document.elementFromPoint(r.x + 30, r.y + 20));
    })).toBe(true);
  });
}

test('内容管理在新标签页打开，原标签页保留位置；登出进入登录，其他公开标签刷新为游客', async ({ page, context }) => {
  const session = await setup(context); await page.goto('/');
  let writes = 0;
  await context.route('**/api/auth/logout', (route) => {
    writes++; expect(route.request().method()).toBe('POST'); expect(route.request().headers()['x-csrf-token']).toBe('masked');
    session.authenticated = false; return route.fulfill({ status: 204 });
  });
  await expect(page.locator('.article-card')).toHaveCount(20);
  // 使用真实滚轮终止首页的初始位置校正，再核对新标签打开不会改变阅读位置。
  await page.mouse.move(700, 600); await page.mouse.wheel(0, 350);
  await expect.poll(() => page.locator('.home-scroll').evaluate((element) => element.scrollTop)).toBeGreaterThan(300);
  const originalTop = await page.locator('.home-scroll').evaluate((element) => element.scrollTop);
  await openMenu(page);
  expect(await page.locator('.home-scroll').evaluate((element) => element.scrollTop)).toBe(originalTop);
  const popupPromise = page.waitForEvent('popup');
  await page.getByRole('menuitem', { name: '内容管理' }).click(); const popup = await popupPromise;
  await expect(popup).toHaveURL(/\/manage\/articles$/); await expect(popup.locator('.manage-row')).toHaveCount(12);
  await expect(page).toHaveURL('http://127.0.0.1:5175/');
  expect(await page.locator('.home-scroll').evaluate((element) => element.scrollTop)).toBe(originalTop);
  await openMenu(popup); await popup.getByRole('menuitem', { name: '退出登录' }).click();
  await expect(popup).toHaveURL(/\/login\?/); await expect(popup.getByRole('heading', { name: '账号密码登录' })).toBeVisible(); expect(writes).toBe(1);
  await page.reload(); await expect(page.locator('.header-login')).toBeVisible();
  await popup.goto('/manage/articles'); await expect(popup).toHaveURL(/\/login\?/);
});

test('游客直接访问受保护页面及主动发布、点赞均可进入登录', async ({ page, context }) => {
  const session = await setup(context); session.authenticated = false;
  for (const path of ['/manage/articles', '/articles/new', '/articles/12/edit']) {
    await page.goto(path); await expect(page.getByRole('heading', { name: '账号密码登录' })).toBeVisible();
    await expect(page).toHaveURL(/\/login\?/);
  }
  await page.goto('/');
  await expect(page.locator('.header-login')).toHaveCSS('width', '64px');
  await expect(page.locator('.header-login')).toHaveCSS('height', '40px');
  await expect(page.locator('.header-login')).toHaveCSS('background-color', 'rgb(152, 204, 255)');
  await expect(page.locator('.header-login')).toHaveCSS('color', 'rgb(23, 23, 25)');
  await page.getByRole('button', { name: '发布中心' }).click(); await expect(page).toHaveURL(/\/login(?:\?.*)?$/);
  await page.goto('/articles/12'); await expect(page.getByText('公开正文').first()).toBeVisible();
  await expect(page.locator('.header-login')).toBeVisible();
  await page.getByRole('button', { name: /^点赞 / }).click(); await expect(page).toHaveURL(/\/login(?:\?.*)?$/);
});

test('编辑器登出先确认，取消不发送，失败保留内容和离开拦截，重试成功进入登录', async ({ page, context }) => {
  const session = await setup(context); let writes = 0;
  await context.route('**/api/auth/logout', (route) => {
    writes++;
    if (writes === 1) return route.fulfill({ status: 503, json: { code: 'DEPENDENCY_UNAVAILABLE' } });
    session.authenticated = false; return route.fulfill({ status: 204 });
  });
  await page.goto('/articles/new'); await page.getByRole('textbox', { name: '文章标题' }).fill('未保存标题');
  await openMenu(page); await page.getByRole('menuitem', { name: '退出登录' }).click();
  const dialog = page.getByRole('dialog', { name: '离开编辑页？' }); await expect(dialog).toBeVisible(); expect(writes).toBe(0);
  await dialog.getByRole('button', { name: '继续编辑' }).click(); expect(writes).toBe(0);
  await expect(dialog).toBeHidden();
  await expect(page.getByRole('textbox', { name: '文章标题' })).toHaveValue('未保存标题');
  await openMenu(page); await page.getByRole('menuitem', { name: '退出登录' }).click();
  await dialog.getByRole('button', { name: '放弃修改' }).click();
  await expect(page.getByText('服务暂时不可用，请稍后重试')).toBeVisible(); expect(writes).toBe(1);
  await expect(page).toHaveURL(/\/articles\/new$/); await expect(page.getByRole('textbox', { name: '文章标题' })).toHaveValue('未保存标题');
  await page.getByRole('link', { name: '返回首页' }).click(); await expect(dialog).toBeVisible();
  await dialog.getByRole('button', { name: '继续编辑' }).click();
  await expect(dialog).toBeHidden();
  await openMenu(page); await page.getByRole('menuitem', { name: '退出登录' }).click();
  await dialog.getByRole('button', { name: '放弃修改' }).click();
  await expect(page).toHaveURL(/\/login\?/); await expect(page.getByRole('heading', { name: '账号密码登录' })).toBeVisible(); expect(writes).toBe(2);
  expect(await page.evaluate(() => sessionStorage.getItem('ec:article-editor:recovery:v1'))).toBeNull();
});

test('编辑页后台复核过期清理输入并进入登录', async ({ page, context }) => {
  const session = await setup(context); await page.goto('/articles/new');
  await page.getByRole('textbox', { name: '文章标题' }).fill('复核前的标题');
  session.authenticated = false;
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(page).toHaveURL(/\/login\?/); await expect(page.getByRole('heading', { name: '账号密码登录' })).toBeVisible();
  expect(await page.evaluate(() => sessionStorage.getItem('ec:article-editor:recovery:v1'))).toBeNull();
});

test('管理页读取或删除收到401均进入显式登录', async ({ page, context }) => {
  await setup(context); let expiredRead = true;
  await context.route('**/api/users/me/articles?**', (route) => route.fulfill(expiredRead
    ? { status: 401, json: { code: 'AUTHENTICATION_REQUIRED' } }
    : { json: { items: [article('1')], nextCursor: null } }));
  await page.goto('/manage/articles'); await expect(page.getByRole('heading', { name: '账号密码登录' })).toBeVisible();
  await expect(page).toHaveURL(/\/login\?/);
  expiredRead = false;
  await context.route('**/api/articles/1', (route) => route.fulfill({ status: 401, json: { code: 'AUTHENTICATION_REQUIRED' } }));
  await page.goto('/manage/articles'); await expect(page.locator('.manage-row')).toHaveCount(1);
  await page.locator('.manage-row').getByRole('button', { name: '删除' }).click();
  await page.getByRole('dialog').getByRole('button', { name: '删除', exact: true }).click();
  await expect(page).toHaveURL(/\/login(?:\?.*)?$/); await expect(page.getByRole('heading', { name: '账号密码登录' })).toBeVisible();
});

// 资料按页面读取；这里只迁移接口mock，交互场景由人工验收。
test.beforeEach(async ({ context }) => { await mockPublicProfiles(context); });
