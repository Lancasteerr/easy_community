import { describe, expect, it } from 'vitest';
import { contentError, emptyForm, formKey, savePayload, serializeContent, stripTitle, validateForm, validLink } from './model';

const imageId = 'aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa';
describe('文章协议适配', () => {
  it('按 Unicode 码点校验，Java strip 语义不误删 NBSP', () => {
    const form = emptyForm();
    form.title = `\u3000${'😀'.repeat(100)}\n`;
    expect(validateForm(form).title).toBeUndefined();
    form.title += '字';
    expect(validateForm(form).title).toBe('标题不能超过 100 字');
    expect(stripTitle('\u00a0')).toBe('\u00a0');
  });
  it('空段落、不可见空白不算有效正文，只有文件图片允许发布', () => {
    expect(contentError(emptyForm().content)).toBe('请输入文章正文');
    expect(contentError({ schemaVersion: 1, doc: { type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: '\u00a0\n ' }] }] } })).toBe('请输入文章正文');
    expect(contentError({ schemaVersion: 1, doc: { type: 'doc', content: [{ type: 'image', attrs: { fileId: imageId } }] } })).toBe('');
  });
  it('规范化属性并保留标题层级、列表起点、代码语言、图片身份和链接', () => {
    const content = serializeContent({ type: 'doc', content: [
      { type: 'heading', attrs: { level: 6, extra: true }, content: [{ type: 'text', text: '标题', marks: [{ type: 'link', attrs: { href: 'https://example.com', target: '_blank' } }] }] },
      { type: 'orderedList', attrs: { start: 7, type: null }, content: [{ type: 'listItem', content: [{ type: 'paragraph' }] }] },
      { type: 'codeBlock', attrs: { language: 'c++' }, content: [{ type: 'text', text: 'int n;' }] },
      { type: 'image', attrs: { fileId: imageId, src: 'https://untrusted.example/x', alt: '说明', title: '图片', width: 50 } },
    ] });
    expect(contentError(content)).toBe('');
    expect(content.doc.content![0].attrs).toEqual({ level: 6 });
    expect(content.doc.content![0].content![0].marks![0].attrs).toEqual({ href: 'https://example.com' });
    expect(content.doc.content![3].attrs).toEqual({ fileId: imageId, alt: '说明', title: '图片' });
    expect(serializeContent(content.doc)).toEqual(content);
  });
  it('拒绝嵌套超限、图片超限和代码样式叠加', () => {
    const images = Array.from({ length: 51 }, () => ({ type: 'image', attrs: { fileId: imageId } }));
    expect(contentError({ schemaVersion: 1, doc: { type: 'doc', content: images } })).toMatch(/50/);
    let doc = { type: 'paragraph' } as import('@tiptap/react').JSONContent;
    for (let index = 0; index < 32; index++) doc = { type: 'blockquote', content: [doc] };
    expect(contentError({ schemaVersion: 1, doc: { type: 'doc', content: [doc] } })).toMatch(/复杂/);
    expect(contentError({ schemaVersion: 1, doc: { type: 'doc', content: [{ type: 'paragraph', content: [
      { type: 'text', text: 'x', marks: [{ type: 'code' }, { type: 'bold' }] },
    ] }] } })).toMatch(/不能叠加/);
  });
  it('清封面使用 null，标签展示顺序和地址不影响修改判断', () => {
    const form = { ...emptyForm(), tags: [{ tagId: '2', name: 'B' }, { tagId: '1', name: 'A' }] };
    expect(savePayload(form)).toMatchObject({ coverMediaId: null, tagIds: ['2', '1'] });
    expect(savePayload(form)).not.toHaveProperty('articleId');
    expect(formKey(form)).toBe(formKey({ ...form, coverUrl: '/new-url', tags: [...form.tags].reverse() }));
    expect(savePayload(form, '9007199254740993').articleId).toBe('9007199254740993');
  });
  it('拒绝危险链接和超过 1MiB 的实际请求', () => {
    for (const href of ['javascript:alert(1)', 'mailto:a@b.c', 'https://user:pass@example.com', 'https://example.com/a b']) expect(validLink(href)).toBe(false);
    expect(validLink('https://example.com/path?q=x#anchor')).toBe(true);
    const form = { ...emptyForm(), title: '标题' };
    form.content.doc.content = [{ type: 'paragraph', content: [{ type: 'text', text: '中'.repeat(360000) }] }];
    expect(validateForm(form).content).toMatch(/1MiB/);
  });
});
