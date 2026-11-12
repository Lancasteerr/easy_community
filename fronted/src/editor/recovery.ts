import { mediaIdPattern, type ArticleForm } from './model';

export const recoveryKey = 'ec:article-editor:recovery:v1';
export interface EditorRecovery { version: 1; ownerId: string; articleId: string | null; form: ArticleForm; baseline: ArticleForm; savedAt: number }

/** 只为登录跳转保存一次快照，不承担自动保存或草稿箱职责。 */
export function saveRecovery(ownerId: string, articleId: string | undefined, form: ArticleForm, baseline: ArticleForm) {
  const value: EditorRecovery = { version: 1, ownerId, articleId: articleId ?? null, form, baseline, savedAt: Date.now() };
  sessionStorage.setItem(recoveryKey, JSON.stringify(value));
}
function isForm(value: unknown): value is ArticleForm {
  if (!value || typeof value !== 'object') return false;
  const form = value as ArticleForm;
  return typeof form.title === 'string' && form.content?.schemaVersion === 1 && form.content.doc?.type === 'doc'
    && (form.coverMediaId === null || typeof form.coverMediaId === 'string' && mediaIdPattern.test(form.coverMediaId))
    && (form.coverUrl === null || typeof form.coverUrl === 'string') && Array.isArray(form.tags) && form.tags.length <= 5
    && form.tags.every((tag) => typeof tag?.name === 'string' && typeof tag.tagId === 'string' && /^[1-9]\d*$/.test(tag.tagId));
}
export function clearRecovery() {
  try { sessionStorage.removeItem(recoveryKey); } catch { /* 禁用存储时仍允许正常编辑和离开。 */ }
}
/** 在身份确认且编辑权限读取完成后消费；异账号、过期或损坏的数据直接清除。 */
export function takeRecovery(ownerId: string, articleId: string | undefined): EditorRecovery | null {
  try {
    const raw = sessionStorage.getItem(recoveryKey);
    if (!raw) return null;
    const data = JSON.parse(raw) as EditorRecovery;
    if (data.version !== 1 || data.ownerId !== ownerId || !Number.isFinite(data.savedAt) || Date.now() - data.savedAt > 2 * 60 * 60 * 1000
      || data.savedAt > Date.now() || !isForm(data.form) || !isForm(data.baseline)) { clearRecovery(); return null; }
    if (data.articleId !== (articleId ?? null)) return null;
    clearRecovery(); return data;
  } catch { clearRecovery(); return null; }
}
