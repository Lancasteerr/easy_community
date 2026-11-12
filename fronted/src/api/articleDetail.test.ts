import { afterEach, expect, it, vi } from 'vitest';
import { getArticleDetail, setArticleLike } from './articles';

afterEach(() => vi.unstubAllGlobals());

it('公开详情401后仅以游客重读一次，持续401不无限重试', async () => {
  const fetch = vi.fn().mockImplementation(async () => new Response(JSON.stringify({ code: 'AUTHENTICATION_REQUIRED' }), { status: 401 }));
  vi.stubGlobal('fetch', fetch);
  await expect(getArticleDetail('9007199254740995')).rejects.toMatchObject({ status: 401 });
  expect(fetch).toHaveBeenCalledTimes(2);
  expect(fetch.mock.calls[0][0]).toBe('/api/articles/9007199254740995');
});

it('点赞和取消均携带操作UUID及CSRF，返回原始字符串版本', async () => {
  const fetch = vi.fn().mockImplementation(async () => new Response(JSON.stringify({ accepted: true, liked: true, operationId: 'operation', version: '9007199254740995' }), { status: 202 }));
  vi.stubGlobal('fetch', fetch);
  const csrf = { token: 'masked', headerName: 'X-CSRF-TOKEN' };
  expect((await setArticleLike('1', true, 'operation', csrf)).version).toBe('9007199254740995');
  await setArticleLike('1', false, 'operation-2', csrf);
  expect(fetch.mock.calls[0][1]).toMatchObject({ method: 'PUT', headers: { 'X-CSRF-TOKEN': 'masked', 'X-Operation-Id': 'operation' } });
  expect(fetch.mock.calls[1][1]).toMatchObject({ method: 'DELETE', headers: { 'X-Operation-Id': 'operation-2' } });
});
