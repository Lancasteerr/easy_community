import { expect, test } from '@playwright/test';
import { article, profile } from '../src/test/fixtures';

test('主页点赞使用受理接口，防止重复写入并支持独立取消', async ({ page }) => {
  await page.route('**/api/users/me', (route) => route.fulfill({ json: profile }));
  await page.route('**/api/auth/csrf', (route) => route.fulfill({ json: { token: 'masked', headerName: 'X-CSRF-TOKEN' } }));
  await page.route('**/api/articles/feed?**', (route) => route.fulfill({ json: { items: [article('1'), article('2')], nextCursor: null } }));
  const commands: { id: string; method: string; operation: string }[] = [];
  let release!: () => void;
  const pending = new Promise<void>((resolve) => { release = resolve; });
  await page.route('**/api/articles/*/like', async (route) => {
    const request = route.request();
    const operation = request.headers()['x-operation-id'];
    expect(request.headers()['x-csrf-token']).toBe('masked');
    expect(operation).toMatch(/^[0-9a-f-]{36}$/);
    commands.push({ id: new URL(request.url()).pathname.split('/')[3], method: request.method(), operation });
    if (commands.length === 1) await pending;
    return route.fulfill({ status: 202, json: { accepted: true, operationId: operation, liked: request.method() === 'PUT', version: '1' } });
  });
  await page.goto('/');
  const first = page.getByRole('article', { name: '文章 1' });
  const second = page.getByRole('article', { name: '文章 2' });
  const like = first.getByRole('button', { name: '点赞 328' });
  await like.click();
  await expect(like).toBeDisabled();
  await expect(like).toHaveAttribute('aria-pressed', 'false');
  await expect(like).toHaveText('328');
  // 原生禁用按钮不会产生第二次写入，另一张卡片仍可正常受理。
  await like.dispatchEvent('click');
  await second.getByRole('button', { name: '点赞 328' }).click();
  await expect(second.getByRole('button', { name: '取消点赞 329' })).toHaveClass(/is-liked/);
  expect(commands).toHaveLength(2);
  release();
  const cancel = first.getByRole('button', { name: '取消点赞 329' });
  await expect(cancel).toBeEnabled();
  await cancel.click();
  await expect(first.getByRole('button', { name: '点赞 328' })).toHaveAttribute('aria-pressed', 'false');
  expect(commands.map(({ id, method }) => ({ id, method }))).toEqual([
    { id: '1', method: 'PUT' }, { id: '2', method: 'PUT' }, { id: '1', method: 'DELETE' },
  ]);
  expect(new Set(commands.map((command) => command.operation)).size).toBe(3);
});

test('游客点赞登录后恢复深层卡片、纵横位置和续页游标，不补发点赞', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 844 });
  let signedIn = false;
  let likes = 0;
  const cursors: (string | null)[] = [];
  await page.route('**/api/users/me', (route) => route.fulfill(signedIn ? { json: profile }
    : { status: 401, json: { code: 'AUTHENTICATION_REQUIRED' } }));
  await page.route('**/api/auth/csrf', (route) => route.fulfill({ json: { token: 'masked', headerName: 'X-CSRF-TOKEN' } }));
  await page.route('**/api/auth/login', (route) => { signedIn = true; return route.fulfill({ json: profile }); });
  await page.route('**/feed-cover.png', (route) => route.fulfill({ path: 'src/assets/home-banner.png', contentType: 'image/png' }));
  await page.route('**/api/articles/feed?**', (route) => {
    const cursor = new URL(route.request().url()).searchParams.get('cursor');
    cursors.push(cursor);
    const start = cursor === null ? 1 : cursor === 'second-page' ? 4 : 7;
    return route.fulfill({ json: { items: Array.from({ length: 3 }, (_, index) => ({ ...article(String(start + index)), coverUrl: '/feed-cover.png' })),
      nextCursor: start === 1 ? 'second-page' : start === 4 ? 'third-page' : null } });
  });
  await page.route('**/api/articles/*/like', (route) => {
    likes++;
    return route.fulfill({ status: 202, json: { accepted: true, operationId: route.request().headers()['x-operation-id'], liked: true, version: '1' } });
  });
  await page.goto('/');
  await expect(page.locator('.article-card')).toHaveCount(3);
  await page.evaluate(() => document.fonts.ready);
  const scroller = page.getByRole('region', { name: '首页滚动区域' });
  await scroller.evaluate((element) => {
    element.dispatchEvent(new Event('wheel'));
    element.scrollTop = element.scrollHeight;
  });
  await expect(page.locator('.article-card')).toHaveCount(6);
  const card = page.getByRole('article', { name: '文章 4' });
  const like = card.getByRole('button', { name: '点赞 328' });
  await expect(like).toBeEnabled();
  // 把目标按钮放在视口中央，以真实鼠标点击，避免自动滚动改变需要验证的位置。
  const targetTop = (await like.boundingBox())!.y;
  await scroller.evaluate((element, top) => element.scrollTo({ top: element.scrollTop + top - 500, left: 100, behavior: 'instant' }), targetTop);
  const position = await scroller.evaluate((element) => ({ top: element.scrollTop, left: element.scrollLeft }));
  const cardTop = (await card.boundingBox())!.y;
  const bounds = (await like.boundingBox())!;
  await page.mouse.click(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
  await expect(page).toHaveURL(/\/login$/);
  expect(likes).toBe(0);
  await page.getByLabel('账号', { exact: true }).fill('Alice_1');
  await page.getByLabel('密码', { exact: true }).fill('secret123');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page).toHaveURL(/:5175\/$/);
  await expect(page.locator('.article-card')).toHaveCount(6);
  await expect.poll(() => scroller.evaluate((element) => ({ top: element.scrollTop, left: element.scrollLeft }))).toEqual(position);
  expect((await card.boundingBox())!.y).toBeCloseTo(cardTop, 1);
  expect(cursors).toEqual([null, 'second-page']);
  expect(likes).toBe(0);
  await like.click();
  await expect(card.getByRole('button', { name: '取消点赞 329' })).toBeEnabled();
  expect(likes).toBe(1);
  // 滚到列表尾部验证原游标续页；直接点击会与自动加载争抢已被移除的按钮。
  await scroller.evaluate((element) => element.scrollTo({ top: element.scrollHeight, behavior: 'instant' }));
  await expect(page.locator('.article-card')).toHaveCount(9);
  expect(cursors).toEqual([null, 'second-page', 'third-page']);
});
