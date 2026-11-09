import type { ArticleCardData, UserProfile } from '../api/types';

/** 测试卡片刻意使用超出 JS 安全整数范围的字符串 ID。 */
export const article = (id = '9007199254740993'): ArticleCardData => ({
  articleId: id, title: `文章 ${id}`, authorId: '9007199254740995', authorName: 'Flame',
  summary: '一个好用的社区，需要建立发布、浏览与反馈的完整闭环。', coverUrl: null, tags: [],
  publishedAt: '2026-11-09T00:00:00Z', updatedAt: '2026-11-09T00:00:00Z', viewCount: 1280, likeCount: 328, likedByMe: false,
});

export const profile: UserProfile = { userId: '9007199254740995', username: 'community_user', role: 'USER', avatarMediaId: null, avatarUrl: null };

export function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((success, failure) => { resolve = success; reject = failure; });
  return { promise, resolve, reject };
}
