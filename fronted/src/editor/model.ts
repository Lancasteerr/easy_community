import type { JSONContent } from '@tiptap/react';

/** 编辑表单只持有业务字段；上传任务、凭证和弹窗状态不进入正文协议。 */
export interface Tag { tagId: string; name: string }
export interface ArticleContent { schemaVersion: 1; doc: JSONContent }
export interface ArticleForm {
  title: string;
  content: ArticleContent;
  coverMediaId: string | null;
  coverUrl: string | null;
  tags: Tag[];
}
export interface ArticleEditData extends ArticleForm {
  articleId: string;
  createdAt: string;
  updatedAt: string;
  publishedAt: string;
}
export function emptyForm(): ArticleForm {
  return { title: '', content: { schemaVersion: 1, doc: { type: 'doc', content: [{ type: 'paragraph' }] } },
    coverMediaId: null, coverUrl: null, tags: [] };
}

/** 与 Java strip 保持一致：NBSP 等不被 Java 视为空白的字符不在这里裁掉。 */
export function stripTitle(value: string) {
  return value.replace(/^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+|[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+$/g, '');
}
export const codePoints = (value: string) => Array.from(value).length;
export const mediaIdPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
export function validLink(href: string) {
  try {
    const url = new URL(href);
    const authority = href.match(/^https?:\/\/([^/?#]+)/i)?.[1];
    return !!authority && /^[\x21-\x7e]+$/.test(authority) && !/%(?![a-f0-9]{2})/i.test(href)
      && href.length <= 2048 && !/[\s<>"{}|\\^`]/.test(href) && /^https?:$/i.test(url.protocol)
      && !!url.hostname && !url.username && !url.password;
  } catch { return false; }
}
export function imageCount(doc: JSONContent): number {
  return (doc.type === 'image' ? 1 : 0) + (doc.content ?? []).reduce((count, node) => count + imageCount(node), 0);
}

/** 只输出后端白名单属性，避免 Tiptap 默认属性或 NodeView 临时状态泄漏到接口。 */
export function serializeContent(doc: JSONContent): ArticleContent {
  const visit = (node: JSONContent): JSONContent => {
    const result: JSONContent = { type: node.type };
    if (node.type === 'text') {
      result.text = node.text;
      if (node.marks?.length) result.marks = node.marks.map((mark) => mark.type === 'link'
        ? { type: 'link', attrs: { href: mark.attrs?.href } } : { type: mark.type }).sort((a, b) => a.type.localeCompare(b.type));
    } else if (node.type === 'image') {
      result.attrs = { fileId: node.attrs?.fileId, alt: node.attrs?.alt ?? '', title: node.attrs?.title ?? '' };
    } else {
      if (node.type === 'heading') result.attrs = { level: node.attrs?.level ?? 1 };
      if (node.type === 'orderedList') result.attrs = { start: node.attrs?.start ?? 1 };
      if (node.type === 'codeBlock' && node.attrs?.language) result.attrs = { language: node.attrs.language };
      if (!['hardBreak', 'horizontalRule'].includes(node.type ?? '')) result.content = (node.content ?? []).map(visit);
    }
    return result;
  };
  return { schemaVersion: 1, doc: visit(doc) };
}

/** 校验协议结构和后端已有资源上限；不为正文额外引入产品字数限制。 */
export function contentError(content: ArticleContent) {
  let nodes = 0, images = 0, plain = '';
  const blocks = ['paragraph', 'heading', 'blockquote', 'bulletList', 'orderedList', 'codeBlock', 'image', 'horizontalRule'];
  const visit = (node: JSONContent, parent: string | undefined, depth: number): void => {
    if (++nodes > 10_000 || depth > 32) throw new Error('正文结构过于复杂，请减少层级或内容块');
    const type = node.type ?? '';
    const allowed = parent === undefined ? ['doc'] : ['doc', 'blockquote', 'listItem'].includes(parent) ? blocks
      : ['paragraph', 'heading'].includes(parent) ? ['text', 'hardBreak']
      : ['bulletList', 'orderedList'].includes(parent) ? ['listItem'] : parent === 'codeBlock' ? ['text'] : [];
    if (!allowed.includes(type)) throw new Error('正文包含不支持的格式');
    if (type === 'image') {
      if (++images > 50) throw new Error('正文最多插入 50 张图片');
      if (!mediaIdPattern.test(node.attrs?.fileId ?? '')) throw new Error('请重新上传无效的正文图片');
      if (codePoints(node.attrs?.alt ?? '') > 500 || codePoints(node.attrs?.title ?? '') > 500) throw new Error('图片说明不能超过 500 字');
    }
    if (type === 'heading' && !(Number.isInteger(node.attrs?.level) && node.attrs!.level >= 1 && node.attrs!.level <= 6)) throw new Error('标题层级无效');
    if (type === 'orderedList' && !(Number.isInteger(node.attrs?.start) && node.attrs!.start >= 1 && node.attrs!.start <= 2147483647)) throw new Error('列表起始序号无效');
    if (type === 'codeBlock' && node.attrs?.language && !/^[A-Za-z0-9_+\-]{1,32}$/.test(node.attrs.language)) throw new Error('代码语言格式无效');
    if (['doc', 'blockquote', 'bulletList', 'orderedList', 'listItem'].includes(type) && !node.content?.length) throw new Error('正文结构不完整');
    if (type === 'listItem' && node.content?.[0]?.type !== 'paragraph') throw new Error('列表项需要以段落开始');
    if (type === 'text') {
      if (!node.text) throw new Error('正文包含空文本节点');
      plain += node.text;
      const marks = node.marks ?? [];
      if (marks.length > 6 || new Set(marks.map((mark) => mark.type)).size !== marks.length
        || marks.some((mark) => !['bold', 'italic', 'strike', 'underline', 'code', 'link'].includes(mark.type))) throw new Error('文字样式无效');
      if ((parent === 'codeBlock' && marks.length) || (marks.some((mark) => mark.type === 'code') && marks.length !== 1)) throw new Error('行内代码不能叠加其他样式');
      if (marks.some((mark) => mark.type === 'link' && !validLink(mark.attrs?.href ?? ''))) throw new Error('链接需为有效的 HTTP(S) 地址');
    }
    (node.content ?? []).forEach((child) => visit(child, type, depth + 1));
  };
  try { visit(content.doc, undefined, 1); }
  catch (error) { return (error as Error).message; }
  return !plain.replace(/[\s\p{Z}]/gu, '') && !images ? '请输入文章正文' : '';
}
export function savePayload(form: ArticleForm, articleId?: string) {
  return { ...(articleId ? { articleId } : {}), title: stripTitle(form.title), content: serializeContent(form.content.doc),
    coverMediaId: form.coverMediaId, tagIds: form.tags.map((tag) => tag.tagId) };
}
export function validateForm(form: ArticleForm, articleId?: string) {
  const errors: { title?: string; content?: string } = {};
  if (!stripTitle(form.title)) errors.title = '请输入文章标题';
  else if (codePoints(stripTitle(form.title)) > 100) errors.title = '标题不能超过 100 字';
  errors.content = contentError(serializeContent(form.content.doc)) || undefined;
  if (!errors.content && new TextEncoder().encode(JSON.stringify(savePayload(form, articleId))).byteLength > 1_048_576) errors.content = '文章请求不能超过 1MiB，请减少内容';
  return errors;
}
/** 展示地址不参与脏数据比较；后端标签按 ID 排序，顺序不表示业务差异。 */
export function formKey(form: ArticleForm) {
  const payload = savePayload(form);
  return JSON.stringify({ ...payload, tagIds: [...payload.tagIds].sort() });
}
