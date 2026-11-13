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

it('向阅读页面传回有限位置，不向编辑页传递或接受非数值位置', () => {
  expect(readingReturnState('/articles/1', { top: 200, anchor: '3', offset: -20 })).toEqual({ readingPosition: { top: 200, anchor: '3', offset: -20 } });
  expect(readingReturnState('/articles/1', { top: Infinity })).toBeUndefined();
  expect(readingReturnState('/articles/1', { top: -1 })).toBeUndefined();
  expect(readingReturnState('/articles/1/edit', { top: 200 })).toBeUndefined();
});

it('首页回跳只接受公开列表快照，详情不携带列表标识', () => {
  const position = { top: 2400, left: 100, anchor: '9007199254740993', offset: -30 };
  expect(readingReturnState('/', position, 'feed:default')).toEqual({ readingPosition: position, feedSnapshotKey: 'feed:default' });
  expect(readingReturnState('/articles/1', position, 'feed:default')).toEqual({ readingPosition: position });
  for (const key of ['manage:default', 'feed-scroll:default', 'feed:', 'feed:../private', 'feed:a?b', 'feed:' + 'x'.repeat(129), 123, {}]) {
    expect(readingReturnState('/', position, key)).toEqual({ readingPosition: position });
  }
  for (const top of [-1, Infinity, NaN, '200']) expect(readingReturnState('/', { top }, 'feed:default')).toBeUndefined();
  expect(readingReturnState('/', { top: 10, left: -1, anchor: 'bad', offset: Infinity }, 'feed:key-1'))
    .toEqual({ readingPosition: { top: 10 }, feedSnapshotKey: 'feed:key-1' });
});

it('登录回跳保留有效横向位置，忽略负值及非有限值', () => {
  expect(readingReturnState('/articles/1', { top: 200, left: 80 })).toEqual({ readingPosition: { top: 200, left: 80 } });
  for (const left of [-1, Infinity, NaN, '80']) {
    expect(readingReturnState('/articles/1', { top: 200, left })).toEqual({ readingPosition: { top: 200 } });
  }
});
