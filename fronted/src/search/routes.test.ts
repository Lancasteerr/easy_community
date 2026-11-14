import { afterEach, expect, it, vi } from 'vitest';
import { loginDestination } from '../article/routes';
import { searchArticles } from './api';
import { readSearchQuery, searchKeywordError, searchPath } from './routes';

afterEach(() => vi.unstubAllGlobals());

it('关键词先去空白，长度与后端一样按码点计算', () => {
  expect(searchKeywordError(' \n ')).toBe('请输入搜索关键词');
  expect(searchKeywordError(` ${'😀'.repeat(100)} `)).toBeNull();
  expect(searchKeywordError('😀'.repeat(101))).toBe('搜索关键词不能超过100个字符');
  expect(searchPath('  社区 & C++  ')).toBe('/search?q=%E7%A4%BE%E5%8C%BA+%26+C%2B%2B&sort=relevance');
  expect(searchPath(' ')).toBe('/search');
});

it('地址解析保留有效排序，缺省或未知排序回到相关度', () => {
  expect(readSearchQuery('')).toEqual({ q: '', sort: 'relevance' });
  expect(readSearchQuery('?q=+社区+&sort=latest')).toEqual({ q: '社区', sort: 'latest' });
  expect(readSearchQuery('?q=社区&sort=recommended')).toEqual({ q: '社区', sort: 'relevance' });
});

it('登录仅为搜索开放规范化参数，不能放行额外跳转条件和其他页面查询串', () => {
  expect(loginDestination('/search')).toBe('/search');
  expect(loginDestination('/search?q=+社区+&sort=latest')).toBe(searchPath('社区', 'latest'));
  expect(loginDestination('/search?q=社区&sort=unknown')).toBe(searchPath('社区'));
  for (const path of ['/search?q=a&redirect=//example.com', '/search?q=a&q=b', '/search?q=a#x',
    `/search?q=${'a'.repeat(101)}`, '//example.com/search?q=a', '/articles/1?q=a', '/search/other?q=a']) {
    expect(loginDestination(path)).toBe('/');
  }
});

it('搜索请求固定页大小，原样编码关键词、排序和不透明游标', async () => {
  const fetcher = vi.fn().mockResolvedValue(new Response('{"items":[],"nextCursor":null}'));
  vi.stubGlobal('fetch', fetcher);
  await searchArticles('社区 & C++', 'latest', 'opaque+/=');
  expect(fetcher).toHaveBeenCalledWith(
    '/api/articles/search?q=%E7%A4%BE%E5%8C%BA+%26+C%2B%2B&sort=latest&size=20&cursor=opaque%2B%2F%3D',
    expect.objectContaining({ credentials: 'same-origin', cache: 'no-store' }));
});
