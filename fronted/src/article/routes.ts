/** 与后端正 BIGINT 范围一致，全程使用字符串，避免精度损失。 */
export function validArticleId(value: string | undefined): value is string {
  return !!value && /^[1-9]\d{0,18}$/.test(value)
    && (value.length < 19 || value <= '9223372036854775807');
}

/** 登录回跳只开放已知站内入口，拒绝外链、查询串和越界文章标识。 */
export function loginDestination(value: unknown) {
  if (value === '/manage/articles' || value === '/articles/new') return value;
  if (typeof value !== 'string') return '/';
  const match = /^\/articles\/([^/]+)(\/edit)?$/.exec(value);
  return match && validArticleId(match[1]) ? value : '/';
}
