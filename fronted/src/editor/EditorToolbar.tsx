import { useState, type ReactNode } from 'react';
import type { Editor } from '@tiptap/react';
import { EditorAttributePopover, type AttributeTarget } from './EditorAttributePopover';

/** 短符号只负责显示，中文名称和工具提示保持稳定，按钮按编辑用途分组。 */
export function EditorToolbar({ editor, disabled, onImage }: { editor: Editor; disabled: boolean; onImage: () => void }) {
  const [panel, setPanel] = useState<AttributeTarget | null>(null);
  const command = (label: string, glyph: ReactNode, active: boolean | undefined, action: () => void, unavailable = false) => <button type="button"
    disabled={disabled || unavailable} aria-label={label} title={label} aria-pressed={active}
    onMouseDown={(event) => event.preventDefault()} onClick={action}>{glyph}</button>;
  const attributeButton = (kind: 'link' | 'image', label: string, glyph: string) => <button type="button" disabled={disabled}
    aria-label={label} title={label} aria-haspopup="dialog" aria-expanded={panel?.kind === kind}
    onMouseDown={(event) => event.preventDefault()} onClick={(event) => setPanel(panel?.kind === kind ? null : {
      kind, anchor: event.currentTarget, bookmark: editor.state.selection.getBookmark(), fileId: editor.getAttributes('image').fileId,
    })}>{glyph}</button>;
  return <>
    <div className="editor-toolbar" role="toolbar" aria-label="正文格式">
      <div className="editor-toolbar-group" role="group" aria-label="编辑历史">
        {command('撤销', '↶', undefined, () => { editor.chain().focus().undo().run(); }, !editor.can().undo())}
        {command('重做', '↷', undefined, () => { editor.chain().focus().redo().run(); }, !editor.can().redo())}
      </div>
      <div className="editor-toolbar-group" role="group" aria-label="插入内容">
        {command('插入图片', '图片', undefined, onImage)}
        {attributeButton('link', '链接', '链接')}
        {editor.isActive('image') && attributeButton('image', '图片属性', '属性')}
      </div>
      <div className="editor-toolbar-group" role="group" aria-label="文字格式">
        {command('粗体', <b>B</b>, editor.isActive('bold'), () => { editor.chain().focus().toggleBold().run(); })}
        {command('斜体', <i>I</i>, editor.isActive('italic'), () => { editor.chain().focus().toggleItalic().run(); })}
        {command('下划线', <u>U</u>, editor.isActive('underline'), () => { editor.chain().focus().toggleUnderline().run(); })}
        {command('删除线', <s>S</s>, editor.isActive('strike'), () => { editor.chain().focus().toggleStrike().run(); })}
        {command('行内代码', '‹/›', editor.isActive('code'), () => { editor.chain().focus().toggleCode().run(); })}
      </div>
      <div className="editor-toolbar-group" role="group" aria-label="段落格式">
        <select aria-label="段落样式" title="段落样式" disabled={disabled} value={editor.isActive('heading') ? editor.getAttributes('heading').level : 'paragraph'}
          onChange={(event) => event.target.value === 'paragraph' ? editor.chain().focus().setParagraph().run()
            : editor.chain().focus().setHeading({ level: Number(event.target.value) as 1 }).run()}>
          <option value="paragraph">正文</option>{[1, 2, 3, 4, 5, 6].map((level) => <option key={level} value={level}>标题 {level}</option>)}
        </select>
      </div>
      <div className="editor-toolbar-group" role="group" aria-label="列表与引用">
        {command('无序列表', '•', editor.isActive('bulletList'), () => { editor.chain().focus().toggleBulletList().run(); })}
        {command('有序列表', '1.', editor.isActive('orderedList'), () => { editor.chain().focus().toggleOrderedList().run(); })}
        {command('引用', '❝', editor.isActive('blockquote'), () => { editor.chain().focus().toggleBlockquote().run(); })}
        {command('分隔线', '—', undefined, () => { editor.chain().focus().setHorizontalRule().run(); })}
        {editor.isActive('orderedList') && <label className="editor-list-start" title="列表起始序号">起始<input aria-label="列表起始序号" type="number" min={1} max={2147483647} disabled={disabled}
          value={editor.getAttributes('orderedList').start ?? 1} onChange={(event) => {
            const start = Number(event.target.value);
            if (Number.isInteger(start) && start >= 1 && start <= 2147483647) editor.chain().updateAttributes('orderedList', { start }).run();
          }} /></label>}
      </div>
      <div className="editor-toolbar-group" role="group" aria-label="代码格式">
        {command('代码块', 'Code', editor.isActive('codeBlock'), () => { editor.chain().focus().toggleCodeBlock().run(); })}
        {editor.isActive('codeBlock') && <input className="editor-code-language" aria-label="代码语言" disabled={disabled}
          value={editor.getAttributes('codeBlock').language ?? ''} placeholder="例如 java" maxLength={32}
          pattern="[A-Za-z0-9_+\-]*" onChange={(event) => editor.chain().updateAttributes('codeBlock', { language: event.target.value || null }).run()} />}
      </div>
    </div>
    {panel && <EditorAttributePopover key={panel.kind} editor={editor} target={panel} disabled={disabled} onClose={() => setPanel(null)} />}
  </>;
}
