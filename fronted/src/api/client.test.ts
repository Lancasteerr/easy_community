import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, getFeed, getMyArticles, postLogin, request } from './client';

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe('同源请求边界', () => {
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

  it('公开信息流仅在 401 后以游客身份重读一次', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(new Response('{}', { status: 401 }))
      .mockResolvedValueOnce(new Response('{"items":[],"nextCursor":null}'));
    vi.stubGlobal('fetch', fetcher);
    await expect(getFeed(null)).resolves.toEqual({ items: [], nextCursor: null });
    expect(fetcher).toHaveBeenCalledTimes(2);
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
