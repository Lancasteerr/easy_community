import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import type { Editor } from '@tiptap/react';
import { useAuth } from '../auth/AuthContext';
import { ArticleBodyEditor } from './ArticleBodyEditor';
import { CoverUpload } from './CoverUpload';
import { TagSelector } from './TagSelector';
import { ImageUploadTasks } from './ImageUploadTasks';
import { SaveArticleDialog } from './SaveArticleDialog';
import { useBodyImages } from './useBodyImages';
import { useArticleDocument } from './useArticleDocument';
import { useArticleSave } from './useArticleSave';
import { codePoints, validateForm } from './model';
import { EditorReadState } from './EditorReadState';

/** 表单、上传与保存的编排入口，文档协议和请求生命周期由专用模块负责。 */
export function ArticleEditorSession({ routeId }: { routeId?: string }) {
  const auth = useAuth();
  const navigate = useNavigate();
  const document = useArticleDocument(routeId, auth.expireSession);
  const { form, setForm } = document;
  const save = useArticleSave((id, submitted) => {
    document.saved(id, submitted);
    if (!document.articleId) navigate(`/articles/${id}/edit`, { replace: true });
  }, auth.expireSession);
  const editor = useRef<Editor | null>(null);
  const [coverBlocked, setCoverBlocked] = useState(false);
  const disabled = auth.refreshing || !!auth.error || save.busy;
  const uploads = useBodyImages(editor, disabled, auth.expireSession);
  const blocked = disabled || coverBlocked || uploads.blocked;
  const title = useRef<HTMLTextAreaElement>(null);
  useLayoutEffect(() => {
    if (title.current) { title.current.style.height = 'auto'; title.current.style.height = `${Math.max(36, title.current.scrollHeight)}px`; }
  }, [form.title, document.state]);
  useEffect(() => {
    if (save.errors.title) title.current?.focus();
    else if (save.errors.content) { editor.current?.commands.focus(); editor.current?.view.dom.scrollIntoView({ block: 'center' }); }
  }, [save.errors]);
  if (document.state !== 'ready') return <EditorReadState state={document.state} onRetry={document.retry} />;
  return <div className="article-editor-inner">
    <div className="article-editor-heading"><h1>{document.articleId ? '编辑文章' : '发布文章'}</h1><Link to="/">返回首页</Link></div>
    <form noValidate onSubmit={(event) => { event.preventDefault(); void save.submit(form, document.articleId, blocked); }}>
      <div className={`article-title${save.errors.title ? ' has-error' : ''}`}><textarea ref={title} aria-label="文章标题" value={form.title} rows={1} placeholder="请输入文章标题"
        disabled={disabled} aria-invalid={!!save.errors.title} aria-describedby="article-title-error"
        onBlur={() => { if (save.errors.title) save.setErrors({ ...save.errors, title: validateForm(form).title }); }}
        onChange={(event) => { const next = { ...form, title: event.target.value }; setForm(next); if (save.errors.title) save.setErrors({ ...save.errors, title: validateForm(next).title }); }} />
        <span>{codePoints(form.title)}/100</span></div>
      {save.errors.title && <p id="article-title-error" className="editor-error" role="alert">{save.errors.title}</p>}
      <ArticleBodyEditor key={document.bodyKey} initialDoc={form.content.doc} disabled={disabled} error={save.errors.content} onFiles={uploads.add}
        onReady={(instance) => { editor.current = instance; }} onChange={(doc) => {
          setForm((current) => ({ ...current, content: { schemaVersion: 1, doc } }));
          if (save.errors.content) save.setErrors({ ...save.errors, content: validateForm({ ...form, content: { schemaVersion: 1, doc } }).content });
        }} />
      {save.errors.content && <p className="editor-error" role="alert">{save.errors.content}</p>}
      <ImageUploadTasks uploads={uploads} disabled={disabled} />
      <section className="article-settings" aria-label="发布设置"><h2>发布设置</h2>
        <CoverUpload value={form.coverMediaId ? { mediaId: form.coverMediaId, publicUrl: form.coverUrl! } : null}
          disabled={disabled} onBlocked={setCoverBlocked} onUnauthorized={auth.expireSession}
          onChange={(value) => setForm((current) => ({ ...current, coverMediaId: value?.mediaId ?? null, coverUrl: value?.publicUrl ?? null }))} />
        <TagSelector value={form.tags} disabled={disabled} onUnauthorized={auth.expireSession} onChange={(tags) => setForm((current) => ({ ...current, tags }))} />
      </section>
      {!!auth.error && <p className="editor-error" role="status">登录状态暂时无法确认 <button type="button" onClick={() => { void auth.refresh(); }}>重新检查</button></p>}
      <div className="article-submit-row"><button className="editor-primary" disabled={blocked} type="submit">{document.articleId ? '保存修改' : '发布文章'}</button></div>
    </form>
    <SaveArticleDialog state={save.dialog} blocked={blocked} onClose={save.close} onRetry={() => { void save.submit(form, document.articleId, blocked); }} />
  </div>;
}
