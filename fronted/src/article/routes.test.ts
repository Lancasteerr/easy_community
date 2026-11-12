import { expect, it } from 'vitest';
import { loginDestination } from './routes';
import { readingReturnState } from '../navigation/readingReturn';

it('登录白名单接受合法详情和原有入口，拒绝外链、越界及夹带路径', () => {
  for (const target of ['/articles/9007199254740995', '/articles/9223372036854775807/edit', '/articles/new', '/manage/articles']) {
    expect(loginDestination(target)).toBe(target);
  }
  for (const target of ['https://example.com', '//example.com', '/articles/9223372036854775808', '/articles/0', '/articles/1?redirect=//example.com', '/articles/1/../../login']) {
    expect(loginDestination(target)).toBe('/');
  }
});

it('仅向详情传回有限阅读位置，不向编辑页传递或接受非数值位置', () => {
  expect(readingReturnState('/articles/1', { top: 200, anchor: '3', offset: -20 })).toEqual({ readingPosition: { top: 200, anchor: '3', offset: -20 } });
  expect(readingReturnState('/articles/1', { top: Infinity })).toBeUndefined();
  expect(readingReturnState('/articles/1', { top: -1 })).toBeUndefined();
  expect(readingReturnState('/articles/1/edit', { top: 200 })).toBeUndefined();
});
