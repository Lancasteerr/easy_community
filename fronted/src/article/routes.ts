import { validUserId } from '../profile/routes';
import { readSearchQuery, searchKeywordError, searchPath } from '../search/routes';

/** 与后端正 BIGINT 范围一致，全程使用字符串，避免精度损失。 */
export function validArticleId(value: string | undefined): value is string {
  return !!value && /^[1-9]\d{0,18}$/.test(value)
    && (value.length < 19 || value <= '9223372036854775807');
}

/** 搜索仅接收关键词与排序；其他登录回跳仍拒绝查询串、外链和越界标识。 */
export function loginDestination(value: unknown) {
  if (value === '/manage/articles' || value === '/articles/new' || value === '/settings/profile') return value;
  if (typeof value !== 'string') return '/';
  if (value === '/search') return value;
  if (value.startsWith('/search?') && !value.includes('#')) {
    const search = value.slice('/search'.length);
    const params = new URLSearchParams(search);
    // 单独开放搜索参数，不能借此放行任意页面的查询串或额外跳转条件。
    if (Array.from(params.keys()).some((key) => key !== 'q' && key !== 'sort')
      || params.getAll('q').length > 1 || params.getAll('sort').length > 1) return '/';
    const { q, sort } = readSearchQuery(search);
    return !q || !searchKeywordError(q) ? searchPath(q, sort) : '/';
  }
  const user = /^\/users\/([^/]+)$/.exec(value);
  if (user && validUserId(user[1])) return value;
  const match = /^\/articles\/([^/]+)(\/edit)?$/.exec(value);
  return match && validArticleId(match[1]) ? value : '/';
}
