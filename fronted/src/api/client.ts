import type { CsrfToken, FeedPage, ProblemDetail, UserIdentity, UserProfile } from './types';

/** 所有错误经此边界转换；界面不直接展示服务端连接或索引诊断信息。 */
export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public retryAt = 0) {
    super(message);
    this.name = 'ApiError';
  }
}

function retryTime(value: string | null, status: number) {
  if (status !== 429) return 0;
  const seconds = value === null || value.trim() === '' ? NaN : Number(value);
  const date = Number.isFinite(seconds) ? Date.now() + Math.max(0, seconds) * 1000 : Date.parse(value ?? '');
  return Number.isFinite(date) ? Math.max(Date.now(), date) : Date.now() + 30_000;
}

export async function request<T>(url: string, options: RequestInit = {}): Promise<T> {
  const timeout = AbortSignal.timeout(10_000);
  const signal = options.signal ? AbortSignal.any([options.signal, timeout]) : timeout;
  try {
    const response = await fetch(url, { ...options, signal, credentials: 'same-origin', cache: 'no-store' });
    if (!response.ok) {
      const problem: ProblemDetail = await response.json().catch(() => ({}));
      throw new ApiError(response.status, problem.code ?? 'HTTP_ERROR', '请求失败', retryTime(response.headers.get('Retry-After'), response.status));
    }
    // DELETE成功不带响应体；不能将204误判成JSON读取失败或写入结果未知。
    if (response.status === 204) return undefined as T;
    return await response.json() as T;
  } catch (error) {
    // 页面主动取消与读取超时语义不同：前者静默清理，后者允许用户重试。
    if (options.signal?.aborted) throw new DOMException('请求已取消', 'AbortError');
    if (error instanceof ApiError) throw error;
    if (timeout.aborted) throw new ApiError(0, 'REQUEST_TIMEOUT', '请求超时，请重试');
    throw new ApiError(0, 'NETWORK_ERROR', '网络连接失败，请检查网络后重试');
  }
}

export function getProfile(signal?: AbortSignal) {
  return request<UserProfile>('/api/users/me', { signal });
}

export async function getFeed(cursor: string | null, signal?: AbortSignal) {
  const query = new URLSearchParams({ size: '20' });
  if (cursor !== null) query.set('cursor', cursor);
  const read = () => request<FeedPage>(`/api/articles/feed?${query}`, { signal });
  try { return await read(); }
  catch (error) {
    // 过期的登录 Cookie 会被后端清除，公开读取允许再以游客身份尝试一次。
    if (error instanceof ApiError && error.status === 401 && !signal?.aborted) return read();
    throw error;
  }
}

export function getCsrf(signal?: AbortSignal) {
  return request<CsrfToken>('/api/auth/csrf', { signal });
}

/** 本人入口不降为游客重读，作者条件只由服务端Session确定。 */
export function getMyArticles(cursor: string | null, signal?: AbortSignal) {
  const query = new URLSearchParams({ size: '20' });
  if (cursor !== null) query.set('cursor', cursor);
  return request<FeedPage>(`/api/users/me/articles?${query}`, { signal });
}

export function deleteArticle(id: string, csrf: CsrfToken, signal?: AbortSignal) {
  return request<void>(`/api/articles/${encodeURIComponent(id)}`, {
    method: 'DELETE', headers: { [csrf.headerName]: csrf.token }, signal,
  });
}

/** 仅用户主动核对时直读作者接口；不保存正文，也不通过公开详情增加PV。 */
export async function checkDeletion(id: string, signal?: AbortSignal): Promise<'visible' | 'hidden' | 'missing'> {
  try {
    await request<unknown>(`/api/articles/${encodeURIComponent(id)}/edit`, { signal });
    return 'visible';
  } catch (error) {
    if (error instanceof ApiError && error.status === 409 && error.code === 'ARTICLE_HIDDEN') return 'hidden';
    if (error instanceof ApiError && error.status === 404 && error.code === 'ARTICLE_NOT_FOUND') return 'missing';
    throw error;
  }
}

export function postLogin(username: string, password: string, csrf: CsrfToken, signal?: AbortSignal) {
  return request<UserIdentity>('/api/auth/login', {
    method: 'POST', signal,
    headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
    body: JSON.stringify({ username, password }),
  });
}

export function errorMessage(error: unknown) {
  if (!(error instanceof ApiError)) return '暂时无法加载，请重试';
  if (error.status === 429) return '请求过于频繁，请稍后重试';
  if (error.status >= 500) return '服务暂时不可用，请稍后重试';
  if (error.code === 'ARTICLE_CURSOR_EXPIRED') return '列表已过期，请重新加载';
  if (error.status === 400) return '请求参数无效，请重新加载';
  return error.status === 0 ? error.message : '暂时无法加载，请重试';
}
