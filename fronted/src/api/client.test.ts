import { endPage, pageLifetime, renewPage } from '../auth/pageLifetime';
import { deferred } from '../test/fixtures';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, checkDeletion, deleteArticle, getFeed, getMyArticles, postLogin, postLogout, request } from './client';

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe('同源请求边界', () => {
  it('登出以 POST 携带动态 CSRF 与同源 Cookie，204 不读取 JSON', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetcher);
    await expect(postLogout({ token: 'masked', headerName: 'CUSTOM-CSRF' })).resolves.toBeUndefined();
    expect(fetcher).toHaveBeenCalledWith('/api/auth/logout', expect.objectContaining({ method: 'POST',
      credentials: 'same-origin', cache: 'no-store', headers: { 'CUSTOM-CSRF': 'masked' } }));
  });
  it('删除携带动态CSRF并接受204，核对只调用作者接口且不保留正文', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response('{"contentJson":{"large":"body"}}'))
      .mockResolvedValueOnce(new Response('{"code":"ARTICLE_HIDDEN"}', { status: 409 }))
      .mockResolvedValueOnce(new Response('{"code":"ARTICLE_NOT_FOUND"}', { status: 404 }));
    vi.stubGlobal('fetch', fetcher);
    await expect(deleteArticle('9007199254740993', { token: 'masked', headerName: 'X-CSRF-TOKEN' })).resolves.toBeUndefined();
    expect(fetcher).toHaveBeenLastCalledWith('/api/articles/9007199254740993', expect.objectContaining({ method: 'DELETE', headers: { 'X-CSRF-TOKEN': 'masked' } }));
    await expect(checkDeletion('1')).resolves.toBe('visible');
    await expect(checkDeletion('1')).resolves.toBe('hidden');
    await expect(checkDeletion('1')).resolves.toBe('missing');
    expect(fetcher.mock.calls.slice(1).map((call) => call[0])).toEqual(Array(3).fill('/api/articles/1/edit'));
  });
  it('本人列表固定20条，原样编码游标，401不重复查询', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response('{"items":[],"nextCursor":null}'));
    vi.stubGlobal('fetch', fetcher);
    await getMyArticles('a+/=');
    expect(fetcher).toHaveBeenLastCalledWith('/api/users/me/articles?size=20&cursor=a%2B%2F%3D', expect.objectContaining({ credentials: 'same-origin' }));
    fetcher.mockResolvedValue(new Response('{}', { status: 401 }));
    await expect(getMyArticles(null)).rejects.toMatchObject({ status: 401 });
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
  it('原样编码不透明游标，携带同源 Cookie 且不缓存个性化读取', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ items: [], nextCursor: null })));
    vi.stubGlobal('fetch', fetcher);
    await getFeed('a+/b=');
    expect(fetcher).toHaveBeenCalledWith('/api/articles/feed?size=20&cursor=a%2B%2Fb%3D', expect.objectContaining({ credentials: 'same-origin', cache: 'no-store' }));
  });

  it('公开信息流401直接交给会话重置，不按游客重复请求', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response('{}', { status: 401 }));
    vi.stubGlobal('fetch', fetcher);
    await expect(getFeed(null)).rejects.toMatchObject({ status: 401 });
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('解析 ProblemDetail 及 Retry-After，非 JSON 错误也可显示', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"code":"RATE_LIMITED"}', { status: 429, headers: { 'Retry-After': '30' } })));
    const start = Date.now();
    const error = await request('/api/test').catch((failure: unknown) => failure) as ApiError;
    expect(error.code).toBe('RATE_LIMITED');
    expect(error.retryAt).toBeGreaterThanOrEqual(start + 30_000);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('unavailable', { status: 503 })));
    await expect(request('/api/test')).rejects.toMatchObject({ status: 503, code: 'HTTP_ERROR' });
  });

  it('登录发送指定 CSRF 请求头，密码保持原样且失败不自动重发', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response('{"code":"ACCESS_OR_CSRF_DENIED"}', { status: 403 }));
    vi.stubGlobal('fetch', fetcher);
    await expect(postLogin('Alice_1', '  secret  ', { token: 'masked', headerName: 'X-CSRF-TOKEN' })).rejects.toBeInstanceOf(ApiError);
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher).toHaveBeenCalledWith('/api/auth/login', expect.objectContaining({ method: 'POST', body: '{"username":"Alice_1","password":"  secret  "}', headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': 'masked' } }));
  });

  it('取消请求与网络故障保持不同错误语义', async () => {
    const controller = new AbortController();
    controller.abort();
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('network')));
    await expect(request('/api/test', { signal: controller.signal })).rejects.toMatchObject({ name: 'AbortError' });
    await expect(request('/api/test')).rejects.toMatchObject({ code: 'NETWORK_ERROR' });
  });

  it('10秒读取超时转换为可重试错误，而非主动取消', async () => {
    const timeout = new AbortController();
    timeout.abort();
    const createTimeout = vi.spyOn(AbortSignal, 'timeout').mockReturnValue(timeout.signal);
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new DOMException('timeout', 'TimeoutError')));
    await expect(request('/api/test')).rejects.toMatchObject({ code: 'REQUEST_TIMEOUT' });
    expect(createTimeout).toHaveBeenCalledWith(10_000);
  });
});

/** 用忽略取消的延迟响应验证顺序保护，不依赖真实浏览器或网络。 */
it('登录前旧401不能重置新身份，页面结束后的成功响应同样被丢弃', async () => {
  const old = deferred<Response>();
  const fetcher = vi.fn().mockReturnValueOnce(old.promise);
  vi.stubGlobal('fetch', fetcher);
  const unauthorized = vi.fn(); pageLifetime.onUnauthorized = unauthorized;
  const requestResult = request('/api/articles/feed');
  const oldResult = expect(requestResult).rejects.toMatchObject({ name: 'AbortError' });
  renewPage();
  old.resolve(new Response('{"code":"AUTHENTICATION_REQUIRED"}', { status: 401 }));
  await oldResult;
  expect(unauthorized).not.toHaveBeenCalled();
  const late = deferred<Response>(); fetcher.mockReturnValueOnce(late.promise);
  const lateResult = expect(request('/api/users/7')).rejects.toMatchObject({ name: 'AbortError' });
  endPage(); late.resolve(new Response('{"userId":"7"}'));
  await lateResult;
});

it('并发认证401只重置一次，登录凭证错误和503不触发重置', async () => {
  const fetcher = vi.fn().mockImplementation(async () => new Response('{"code":"AUTHENTICATION_REQUIRED"}', { status: 401 }));
  vi.stubGlobal('fetch', fetcher);
  const unauthorized = vi.fn(() => { endPage(); }); pageLifetime.onUnauthorized = unauthorized;
  await Promise.allSettled([request('/api/articles/feed'), request('/api/users/7')]);
  expect(unauthorized).toHaveBeenCalledTimes(1);
  renewPage(); unauthorized.mockClear();
  fetcher.mockImplementation(async () => new Response('{"code":"INVALID_CREDENTIALS"}', { status: 401 }));
  await expect(postLogin('user', 'pass', { token: 'token', headerName: 'X-CSRF-TOKEN' })).rejects.toMatchObject({ status: 401 });
  fetcher.mockImplementation(async () => new Response('{}', { status: 503 }));
  await expect(request('/api/auth/session')).rejects.toMatchObject({ status: 503 });
  expect(unauthorized).not.toHaveBeenCalled();
});
