import { useState } from 'react';
import type { Editor } from '@tiptap/react';
import { validLink, codePoints } from './model';

/** 工具栏按钮保留当前选区；属性面板使用显式确认，避免每次输入修改正文。 */
export function EditorToolbar({ editor, disabled, onImage }: { editor: Editor; disabled: boolean; onImage: () => void }) {
  const [panel, setPanel] = useState<'link' | 'image' | null>(null);
  const [href, setHref] = useState('');
  const [alt, setAlt] = useState('');
  const [title, setTitle] = useState('');
  const [message, setMessage] = useState('');
  const command = (label: string, active: boolean, action: () => void, unavailable = false) => <button type="button" key={label}
    disabled={disabled || unavailable} aria-label={label} title={label} aria-pressed={active}
    onMouseDown={(event) => event.preventDefault()} onClick={action}>{label}</button>;
  return <>
    <div className="editor-toolbar" role="toolbar" aria-label="正文格式">
      <select aria-label="段落样式" disabled={disabled} value={editor.isActive('heading') ? editor.getAttributes('heading').level : 'paragraph'}
        onChange={(event) => event.target.value === 'paragraph' ? editor.chain().focus().setParagraph().run()
          : editor.chain().focus().setHeading({ level: Number(event.target.value) as 1 }).run()}>
        <option value="paragraph">正文</option>{[1, 2, 3, 4, 5, 6].map((level) => <option key={level} value={level}>标题 {level}</option>)}
      </select>
      {command('粗体', editor.isActive('bold'), () => { editor.chain().focus().toggleBold().run(); })}
      {command('斜体', editor.isActive('italic'), () => { editor.chain().focus().toggleItalic().run(); })}
      {command('删除线', editor.isActive('strike'), () => { editor.chain().focus().toggleStrike().run(); })}
      {command('下划线', editor.isActive('underline'), () => { editor.chain().focus().toggleUnderline().run(); })}
      {command('行内代码', editor.isActive('code'), () => { editor.chain().focus().toggleCode().run(); })}
      {command('引用', editor.isActive('blockquote'), () => { editor.chain().focus().toggleBlockquote().run(); })}
      {command('无序列表', editor.isActive('bulletList'), () => { editor.chain().focus().toggleBulletList().run(); })}
      {command('有序列表', editor.isActive('orderedList'), () => { editor.chain().focus().toggleOrderedList().run(); })}
      {command('代码块', editor.isActive('codeBlock'), () => { editor.chain().focus().toggleCodeBlock().run(); })}
      {command('分隔线', false, () => { editor.chain().focus().setHorizontalRule().run(); })}
      {command('链接', editor.isActive('link'), () => { setHref(editor.getAttributes('link').href ?? ''); setMessage(''); setPanel('link'); })}
      {command('插入图片', false, onImage)}
      {editor.isActive('image') && command('图片属性', false, () => {
        const attrs = editor.getAttributes('image'); setAlt(attrs.alt ?? ''); setTitle(attrs.title ?? ''); setMessage(''); setPanel('image');
      })}
      {command('撤销', false, () => { editor.chain().focus().undo().run(); }, !editor.can().undo())}
      {command('重做', false, () => { editor.chain().focus().redo().run(); }, !editor.can().redo())}
    </div>
    {editor.isActive('codeBlock') && <label className="editor-inline-field">代码语言<input aria-label="代码语言" disabled={disabled}
      value={editor.getAttributes('codeBlock').language ?? ''} placeholder="例如 java" maxLength={32}
      pattern="[A-Za-z0-9_+\-]*" onChange={(event) => editor.chain().updateAttributes('codeBlock', { language: event.target.value || null }).run()} /></label>}
    {editor.isActive('orderedList') && <label className="editor-inline-field">起始序号<input aria-label="列表起始序号" type="number" min={1} max={2147483647} disabled={disabled}
      value={editor.getAttributes('orderedList').start ?? 1} onChange={(event) => {
        const start = Number(event.target.value); if (Number.isInteger(start) && start >= 1 && start <= 2147483647) editor.chain().updateAttributes('orderedList', { start }).run();
      }} /></label>}
    {panel && <div className="editor-attributes" role="group" aria-label={panel === 'link' ? '编辑链接' : '编辑图片'}>
      {panel === 'link' ? <label>链接地址<input autoFocus value={href} disabled={disabled} onChange={(event) => setHref(event.target.value)} placeholder="https://" /></label>
        : <><label>替代文字<input autoFocus value={alt} disabled={disabled} onChange={(event) => setAlt(event.target.value)} /></label>
          <label>图片标题<input value={title} disabled={disabled} onChange={(event) => setTitle(event.target.value)} /></label></>}
      <span className="editor-error" role="status">{message}</span>
      <button type="button" disabled={disabled} onClick={() => {
        if (panel === 'link') {
          if (!validLink(href)) { setMessage('请输入有效的 HTTP(S) 链接'); return; }
          const chain = editor.chain().focus().extendMarkRange('link');
          if (editor.state.selection.empty && !editor.isActive('link')) chain.insertContent({ type: 'text', text: href, marks: [{ type: 'link', attrs: { href } }] }).run();
          else chain.setLink({ href }).run();
        } else {
          if (codePoints(alt) > 500 || codePoints(title) > 500) { setMessage('图片说明不能超过 500 字'); return; }
          editor.chain().focus().updateAttributes('image', { alt, title }).run();
        }
        setPanel(null);
      }}>应用</button>
      <button type="button" disabled={disabled} onClick={() => {
        if (panel === 'link') editor.chain().focus().extendMarkRange('link').unsetLink().run();
        else editor.chain().focus().deleteSelection().run();
        setPanel(null);
      }}>{panel === 'link' ? '移除链接' : '移除图片'}</button>
      <button type="button" onClick={() => { setPanel(null); editor.commands.focus(); }}>取消</button>
    </div>}
  </>;
}
