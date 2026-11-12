import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { Editor } from '@tiptap/react';
import type { SelectionBookmark, Transaction } from '@tiptap/pm/state';
import { NodeSelection } from '@tiptap/pm/state';
import { codePoints, validLink } from './model';

export interface AttributeTarget {
  kind: 'link' | 'image'; anchor: HTMLButtonElement; bookmark: SelectionBookmark; fileId?: string;
}

/** 使用浏览器顶层浮层避开吸顶工具栏的裁切；编辑期间将原选区随文档事务映射。 */
export function EditorAttributePopover({ editor, target, disabled, onClose }: {
  editor: Editor; target: AttributeTarget; disabled: boolean; onClose: () => void;
}) {
  const popup = useRef<HTMLDivElement>(null);
  const bookmark = useRef(target.bookmark);
  const callbacks = useRef({ onClose }); callbacks.current = { onClose };
  const [href, setHref] = useState<string>(editor.getAttributes('link').href ?? '');
  const [alt, setAlt] = useState<string>(editor.getAttributes('image').alt ?? '');
  const [title, setTitle] = useState<string>(editor.getAttributes('image').title ?? '');
  const [message, setMessage] = useState('');
  useEffect(() => {
    const map = ({ transaction }: { transaction: Transaction }) => { bookmark.current = bookmark.current.map(transaction.mapping); };
    editor.on('transaction', map);
    return () => { editor.off('transaction', map); };
  }, [editor]);
  useLayoutEffect(() => {
    const element = popup.current!;
    let mounted = true;
    const position = () => {
      const anchor = target.anchor.getBoundingClientRect();
      const bounds = element.getBoundingClientRect();
      const left = Math.max(12, Math.min(anchor.left, window.innerWidth - bounds.width - 12));
      const below = anchor.bottom + 8;
      const top = below + bounds.height <= window.innerHeight - 12 ? below : Math.max(12, anchor.top - bounds.height - 8);
      element.style.left = `${left}px`; element.style.top = `${top}px`;
    };
    const toggle = (event: Event) => { if (mounted && (event as ToggleEvent).newState === 'closed') callbacks.current.onClose(); };
    element.addEventListener('toggle', toggle);
    element.showPopover(); position();
    element.querySelector('input')?.focus({ preventScroll: true });
    const observer = new ResizeObserver(position); observer.observe(element);
    window.addEventListener('scroll', position, true); window.addEventListener('resize', position);
    return () => {
      mounted = false; element.removeEventListener('toggle', toggle); observer.disconnect();
      window.removeEventListener('scroll', position, true); window.removeEventListener('resize', position);
      element.hidePopover(); if (target.anchor.isConnected) target.anchor.focus({ preventScroll: true });
    };
  }, [target]);
  useEffect(() => { if (disabled) callbacks.current.onClose(); }, [disabled]);

  function apply(remove = false) {
    if (disabled || editor.isDestroyed) return;
    if (!remove && target.kind === 'link' && !validLink(href)) { setMessage('请输入有效的 HTTP(S) 链接'); return; }
    if (!remove && target.kind === 'image' && (codePoints(alt) > 500 || codePoints(title) > 500)) { setMessage('图片说明不能超过 500 字'); return; }
    const selection = bookmark.current.resolve(editor.state.doc);
    if (target.kind === 'image' && (!(selection instanceof NodeSelection) || selection.node.type.name !== 'image' || selection.node.attrs.fileId !== target.fileId)) {
      setMessage('所选图片已移除，请重新选择图片'); return;
    }
    // 先恢复被输入框暂存的选区，后续命令和属性更新共用同一事务与撤销步骤。
    const chain = editor.chain().command(({ tr }) => { tr.setSelection(selection); return true; });
    if (target.kind === 'image') {
      chain.setNodeSelection(selection.from);
      if (remove) chain.deleteSelection().run(); else chain.updateAttributes('image', { alt, title }).run();
    } else {
      chain.extendMarkRange('link');
      if (remove) chain.unsetLink().run();
      else if (selection.empty && !selection.$from.marks().some((mark) => mark.type.name === 'link')
        && !editor.state.doc.rangeHasMark(selection.from, Math.min(selection.from + 1, editor.state.doc.content.size), editor.schema.marks.link)) chain.insertContent({ type: 'text', text: href, marks: [{ type: 'link', attrs: { href } }] }).run();
      else chain.setLink({ href }).run();
    }
    callbacks.current.onClose();
  }
  return <div ref={popup} className="editor-popover" popover="auto" role="dialog" aria-label={target.kind === 'link' ? '编辑链接' : '编辑图片'}
    onKeyDown={(event) => {
      if (event.key === 'Enter' && !event.nativeEvent.isComposing && event.target instanceof HTMLInputElement) { event.preventDefault(); apply(); }
      if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); callbacks.current.onClose(); }
    }}>
    <h2>{target.kind === 'link' ? '编辑链接' : '图片属性'}</h2>
    {target.kind === 'link' ? <label>链接地址<input value={href} disabled={disabled} onChange={(event) => setHref(event.target.value)} placeholder="https://" /></label>
      : <><label>替代文字<input value={alt} disabled={disabled} onChange={(event) => setAlt(event.target.value)} /></label>
        <label>图片标题<input value={title} disabled={disabled} onChange={(event) => setTitle(event.target.value)} /></label></>}
    <p className="editor-error" role="status">{message}</p>
    <div className="editor-popover-actions">
      <button type="button" disabled={disabled} onClick={() => apply(true)}>{target.kind === 'link' ? '移除链接' : '移除图片'}</button>
      <button type="button" onClick={onClose}>取消</button>
      <button type="button" className="editor-primary" disabled={disabled} onClick={() => apply()}>应用</button>
    </div>
  </div>;
}
