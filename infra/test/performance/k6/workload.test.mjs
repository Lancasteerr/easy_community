import assert from 'node:assert/strict';
import test from 'node:test';
import { distributedArticle, randomFor, searchPath, sessionFor, utf8Bytes, validDetail, validLike, validSearch } from './workload.mjs';

test('固定种子可重放，并分散到各点赞分区', () => {
  const partitions = [4, 1, 2, 3].map(first => ({ first, step: 4, count: 2 }));
  const run = () => Array.from({ length: 20 }, (_, i) => distributedArticle(partitions, i, randomFor(3, i, 1)));
  assert.deepEqual(run(), run());
  assert.deepEqual(run().slice(0, 4).map(id => id % 4), [0, 1, 2, 3]);
});

test('搜索检查游标重复、固定查询与标签', () => {
  const item = { articleId: '7', tags: [{ tagId: '2' }] };
  const page = { items: [item], nextCursor: 'next+' };
  assert.equal(validSearch(page, { tagId: 2 }), true);
  assert.equal(validSearch(page, { tagId: 3 }), false);
  assert.equal(validSearch(page, {}, ['7']), false);
  assert.equal(validSearch(page, { articleId: 8 }), false);
  assert.equal(validSearch({ items: [], nextCursor: null }, { empty: true }), true);
  assert.equal(searchPath({ q: '编程', tagId: 2 }, 'next+'), '/api/articles/search?size=5&q=%E7%BC%96%E7%A8%8B&tagId=2&cursor=next%2B');
});

test('详情按业务字段判断，点赞版本始终按字符串传递', () => {
  const detail = { articleId: '1', contentHtml: '<p>正文</p>', likedByMe: false, likeCount: 2, viewCount: 0 };
  assert.equal(validDetail(detail, 1), true);
  assert.equal(validDetail(detail, 2), false);
  const response = { accepted: true, operationId: 'op', liked: true, version: '9007199254740993' };
  assert.equal(validLike(response, 'op', true), true);
  assert.equal(validLike({ ...response, version: Number(response.version) }, 'op', true), false);
  assert.equal(validLike(response, 'other', true), false);
  assert.equal(utf8Bytes('一🐱x'), 8);
});

test('每个 VU 只读自己的会话', () => {
  const sessions = [{ user: 1 }, { user: 2 }];
  assert.notEqual(sessionFor(sessions, 1), sessionFor(sessions, 2));
  assert.equal(sessionFor(sessions, 3), undefined);
});
