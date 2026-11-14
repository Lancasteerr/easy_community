/** 搜索页只开放当前已有的两种标题排序，不向界面暴露推荐排序。 */
export type SearchSort = 'relevance' | 'latest';

export function searchKeywordError(value: string): string | null {
  const keyword = value.trim();
  if (!keyword) return '请输入搜索关键词';
  // 后端按 Unicode 码点计数，不能用 UTF-16 长度限制包含表情的关键词。
  return Array.from(keyword).length > 100 ? '搜索关键词不能超过100个字符' : null;
}

/** URL 是已提交条件的来源；输入框草稿不参与分页和历史会话标识。 */
export function readSearchQuery(search: string) {
  const params = new URLSearchParams(search);
  return { q: (params.get('q') ?? '').trim(), sort: params.get('sort') === 'latest' ? 'latest' as const : 'relevance' as const };
}

export function searchPath(q: string, sort: SearchSort = 'relevance') {
  const keyword = q.trim();
  return keyword ? `/search?${new URLSearchParams({ q: keyword, sort })}` : '/search';
}
