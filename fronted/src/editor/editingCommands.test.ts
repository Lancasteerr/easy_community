import { afterEach, describe, expect, it } from 'vitest';
import { Editor, type JSONContent } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import { ArticleImage } from './extensions';
import { ArticleCodeBlock } from './codeHighlight';
import { clearEditorFormatting, countBodyCharacters } from './editingCommands';
import { serializeContent } from './model';

const instances: Editor[] = [];
afterEach(() => instances.splice(0).forEach((editor) => { const element = editor.view.dom.parentElement; editor.destroy(); element?.remove(); }));
function create(doc: JSONContent) {
  const element = document.createElement('div'); document.body.append(element);
  const editor = new Editor({ element, extensions: [StarterKit.configure({ codeBlock: false, trailingNode: false }), ArticleCodeBlock, ArticleImage], content: doc });
  instances.push(editor); return editor;
}
const paragraph = (text: string): JSONContent => ({ type: 'paragraph', content: [{ type: 'text', text }] });

describe('正文计数与清除格式', () => {
  it('码点计数保留空格和标点，不计换行、块边界与图片', () => {
    expect(countBodyCharacters({ type: 'doc', content: [paragraph('中😀 A，\nB\r\n'), { type: 'hardBreak' }, { type: 'image' },
      { type: 'codeBlock', content: [{ type: 'text', text: 'x\ny' }] }] })).toBe(8);
    expect(countBodyCharacters({ type: 'doc', content: [{ type: 'image' }] })).toBe(0);
  });
  it('无选区只清当前文本块，一次撤销完整恢复原样式', () => {
    const editor = create({ type: 'doc', content: [
      { type: 'heading', attrs: { level: 2 }, content: [{ type: 'text', text: '标题', marks: [{ type: 'bold' }, { type: 'link', attrs: { href: 'https://example.com' } }] }] },
      { type: 'paragraph', content: [{ type: 'text', text: '其他段落', marks: [{ type: 'italic' }] }] },
    ] });
    const original = editor.getJSON(); editor.commands.setTextSelection(2);
    expect(clearEditorFormatting(editor)).toBe(true);
    expect(editor.getJSON().content?.[0]).toEqual(paragraph('标题'));
    expect(editor.getJSON().content?.[1]).toEqual(original.content?.[1]);
    editor.commands.undo(); expect(editor.getJSON()).toEqual(original);
  });
  it('跨块清理保留图片和文字，图片单选时不执行清理', () => {
    const editor = create({ type: 'doc', content: [
      { type: 'blockquote', content: [{ type: 'heading', attrs: { level: 3 }, content: [{ type: 'text', text: '引用标题', marks: [{ type: 'underline' }] }] }] },
      { type: 'image', attrs: { fileId: 'aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa', src: '/image.png', alt: '保留图片' } },
      { type: 'bulletList', content: [{ type: 'listItem', content: [paragraph('列表文字')] }] },
    ] });
    const original = editor.getJSON(); editor.commands.selectAll(); clearEditorFormatting(editor);
    expect(editor.state.doc.textContent).toBe('引用标题列表文字');
    expect(editor.getJSON().content?.filter((node) => node.type === 'image')).toEqual(original.content?.filter((node) => node.type === 'image'));
    expect(editor.getJSON().content?.some((node) => ['heading', 'blockquote', 'bulletList'].includes(node.type!))).toBe(false);
    editor.commands.undo(); expect(editor.getJSON()).toEqual(original);
    let imagePosition = 0; editor.state.doc.descendants((node, position) => { if (node.type.name === 'image') imagePosition = position; });
    editor.commands.setNodeSelection(imagePosition); expect(clearEditorFormatting(editor)).toBe(false); expect(editor.getJSON()).toEqual(original);
  });
});
describe('代码高亮与现有 JSON 协议', () => {
  it.each(['java', 'html', null])('语言 %s 的显示装饰不进入保存内容或改写语言', (language) => {
    const editor = create({ type: 'doc', content: [{ type: 'codeBlock', attrs: { language }, content: [{ type: 'text', text: language === 'html' ? '<div>hello</div>' : 'public class Demo { int value = 123; }' }] }, paragraph('末尾')] });
    const initial = serializeContent(editor.getJSON());
    expect(editor.view.dom.querySelectorAll('pre span[class*="hljs-"]').length).toBeGreaterThan(0);
    editor.commands.setTextSelection(3); expect(serializeContent(editor.getJSON())).toEqual(initial);
    expect(editor.getJSON().content?.[0].attrs?.language).toBe(language);
    expect(initial.doc.content?.[0].content?.[0].marks).toBeUndefined();
    expect(JSON.stringify(initial)).not.toMatch(/hljs-|className/);
  });
  it('旧未知语言保留原值且不做自动高亮', () => {
    const editor = create({ type: 'doc', content: [{ type: 'codeBlock', attrs: { language: 'future_lang' }, content: [{ type: 'text', text: 'public class Demo { int value = 123; }' }] }] });
    expect(editor.view.dom.querySelectorAll('pre span[class*="hljs-"]').length).toBe(0);
    expect(serializeContent(editor.getJSON()).doc.content?.[0].attrs?.language).toBe('future_lang');
  });
});
