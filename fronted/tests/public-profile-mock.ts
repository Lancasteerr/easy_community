import type { Page } from '@playwright/test';
import { profile } from '../src/test/fixtures';

/** 现有浏览器测试分别模拟会话与公开资料，不让资料请求落入真实后端。 */
export async function mockPublicProfiles(page: Page) {
  await page.route(/\/api\/users\/\d+$/, (route) => {
    const userId = new URL(route.request().url()).pathname.split('/').at(-1)!;
    const { role: _role, ...data } = profile;
    return route.fulfill({ json: { ...data, userId, username: userId === '8' ? 'another_user' : profile.username } });
  });
}
