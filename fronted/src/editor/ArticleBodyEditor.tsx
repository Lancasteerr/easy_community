import { useEffect, useRef, useState } from 'react';
import { EditorContent, useEditor, type Editor, type JSONContent } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import Placeholder from '@tiptap/extension-placeholder';
import FileHandler from '@tiptap/extension-file-handler';
import { ArticleImage } from './extensions';
import { EditorToolbar } from './EditorToolbar';
import { validLink } from './model';
import { UploadAnchors } from './uploadAnchors';
import { ArticleCodeBlock } from './codeHighlight';
import { countBodyCharacters } from './editingCommands';

/** 编辑器只维护文档；媒体任务由上层持有，重新渲染不会重复上传。 */
export function ArticleBodyEditor({ initialDoc, disabled, error, onChange, onReady, onFiles }: {
  initialDoc: JSONContent; disabled: boolean; error?: string; onChange: (doc: JSONContent) => void;
  onReady: (editor: Editor) => void; onFiles?: (files: File[], position: number) => void;
}) {
  const options = useRef({ onChange, onReady, onFiles });
  options.current = { onChange, onReady, onFiles };
  const input = useRef<HTMLInputElement>(null);
  const [characters, setCharacters] = useState(() => countBodyCharacters(initialDoc));
  const editor = useEditor({
    extensions: [StarterKit.configure({ codeBlock: false, trailingNode: false, link: { openOnClick: false, autolink: false,
      isAllowedUri: (url) => validLink(url) } }), ArticleCodeBlock, ArticleImage, UploadAnchors, Placeholder.configure({ placeholder: '请输入正文…' }),
      FileHandler.configure({ allowedMimeTypes: ['image/jpeg', 'image/png', 'image/webp'], consumePasteEvent: true,
        onDrop: (instance, files, position) => { if (instance.isEditable) options.current.onFiles?.(files, position); },
        onPaste: (instance, files) => { if (instance.isEditable) options.current.onFiles?.(files, instance.state.selection.from); } })],
    content: initialDoc,
    shouldRerenderOnTransaction: true,
    editorProps: { attributes: { role: 'textbox', 'aria-label': '文章正文', 'aria-multiline': 'true', 'aria-invalid': 'false' },
      // 外部 HTML 中的图片需重新上传，不能凭复制来的标识绑定他人的文件。
      transformPastedHTML: (html) => { const parsed = new DOMParser().parseFromString(html, 'text/html'); parsed.querySelectorAll('img').forEach((image) => image.remove()); return parsed.body.innerHTML; } },
    onUpdate: ({ editor: instance }) => {
      const doc = instance.getJSON(); setCharacters(countBodyCharacters(doc)); options.current.onChange(doc);
    },
  });
  useEffect(() => { if (editor) options.current.onReady(editor); }, [editor]);
  useEffect(() => { editor?.setEditable(!disabled); }, [editor, disabled]);
  // 懒加载路由首次提交时 EditorContent 可能尚未建立 view，不能提前读取 DOM。
  useEffect(() => { if (editor?.isInitialized && !editor.isDestroyed) editor.view.dom.setAttribute('aria-invalid', String(!!error)); }, [editor, error]);
  if (!editor) return null;
  return <div className={`article-body${error ? ' has-error' : ''}`}>
    <EditorToolbar editor={editor} disabled={disabled} onImage={() => input.current?.click()} />
    <input hidden type="file" multiple accept="image/jpeg,image/png,image/webp" ref={input} disabled={disabled}
      aria-label="选择正文图片" onChange={(event) => { options.current.onFiles?.(Array.from(event.target.files ?? []), editor.state.selection.from); event.target.value = ''; }} />
    <EditorContent editor={editor} />
    <output className="editor-word-count" aria-label="正文字数" aria-live="polite">{characters} 字</output>
  </div>;
}
