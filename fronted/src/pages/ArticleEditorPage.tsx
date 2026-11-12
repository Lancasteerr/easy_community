import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Link, Navigate, useParams } from 'react-router-dom';
import type { Editor } from '@tiptap/react';
import { useAuth } from '../auth/AuthContext';
import { Header } from '../components/Header';
import { useToast } from '../components/Toast';
import { ApiError } from '../api/client';
import { getArticleEdit } from '../api/articles';
import { ArticleBodyEditor } from '../editor/ArticleBodyEditor';
import { codePoints, emptyForm, type ArticleForm } from '../editor/model';
import './editor.css';

/** 路由身份变化时重新建立编辑会话，新建成功换地址则由会话自身保留内容。 */
export function ArticleEditorPage() {
  const { id } = useParams();
  const auth = useAuth();
  const notify = useToast();
  useEffect(() => {
    document.title = `${id ? '编辑文章' : '发布文章'} · Easy Community`;
    window.scrollTo({ top: 0, behavior: 'instant' });
  }, [id]);
  if (auth.status === 'guest') return <Navigate to="/login" replace state={{ returnTo: id ? `/articles/${id}/edit` : '/articles/new' }} />;
  return <div className="article-editor-page">
    <Header hideSearch avatarUrl={auth.user?.avatarUrl} avatarLabel="个人中心" onAvatarClick={() => notify('个人中心暂未开放')} />
    <main className="article-editor-panel">
      {auth.user ? <ArticleEditorSession key={`${auth.user.userId}:${id ?? 'new'}`} initialId={id} />
        : <EditorReadState state={auth.status === 'error' ? 'error' : 'loading'} onRetry={() => { void auth.refresh(); }} />}
    </main>
  </div>;
}
type ReadState = 'loading' | 'ready' | 'error' | 'missing' | 'forbidden' | 'hidden';

/** 读取错误独立于表单，只有成功读取并确认权限后才挂载正文编辑器。 */
function ArticleEditorSession({ initialId }: { initialId?: string }) {
  const auth = useAuth();
  const [form, setForm] = useState<ArticleForm>(emptyForm);
  const [state, setState] = useState<ReadState>(initialId ? 'loading' : 'ready');
  const [revision, setRevision] = useState(0);
  const editor = useRef<Editor | null>(null);
  const title = useRef<HTMLTextAreaElement>(null);
  useLayoutEffect(() => {
    if (title.current) { title.current.style.height = 'auto'; title.current.style.height = `${Math.max(36, title.current.scrollHeight)}px`; }
  }, [form.title, state]);
  useEffect(() => {
    if (!initialId) return;
    const controller = new AbortController();
    setState('loading');
    getArticleEdit(initialId, controller.signal).then((data) => {
      if (!controller.signal.aborted) { setForm(data); setState('ready'); }
    }).catch((error) => {
      if (controller.signal.aborted) return;
      if (error instanceof ApiError && error.status === 401) { auth.expireSession(); return; }
      setState(error instanceof ApiError && error.code === 'ARTICLE_HIDDEN' ? 'hidden'
        : error instanceof ApiError && error.status === 404 ? 'missing'
        : error instanceof ApiError && error.status === 403 ? 'forbidden' : 'error');
    });
    return () => controller.abort();
  }, [initialId, revision]);
  if (state !== 'ready') return <EditorReadState state={state} onRetry={() => setRevision((value) => value + 1)} />;
  return <div className="article-editor-inner">
    <div className="article-editor-heading"><h1>{initialId ? '编辑文章' : '发布文章'}</h1><Link to="/">返回首页</Link></div>
    <form noValidate onSubmit={(event) => event.preventDefault()}>
      <div className="article-title"><textarea ref={title} aria-label="文章标题" value={form.title} rows={1} placeholder="请输入文章标题"
        onChange={(event) => setForm({ ...form, title: event.target.value })} /><span>{codePoints(form.title)}/100</span></div>
      <ArticleBodyEditor initialDoc={form.content.doc} disabled={auth.refreshing || !!auth.error}
        onReady={(instance) => { editor.current = instance; }} onChange={(doc) => setForm((current) => ({ ...current, content: { schemaVersion: 1, doc } }))} />
      <section className="article-settings" aria-label="发布设置"><h2>发布设置</h2></section>
      <div className="article-submit-row"><button className="editor-primary" disabled type="submit">{initialId ? '保存修改' : '发布文章'}</button></div>
    </form>
  </div>;
}
function EditorReadState({ state, onRetry }: { state: Exclude<ReadState, 'ready'>; onRetry: () => void }) {
  const labels = { loading: ['正在加载文章', '正在读取文章内容，请稍候。'], error: ['文章加载失败', '暂时无法读取文章，请重试。'],
    missing: ['文章不存在', '该文章不存在，无法进入编辑。'], forbidden: ['没有编辑权限', '你没有编辑这篇文章的权限。'], hidden: ['文章已删除', '该文章已删除，无法继续编辑。'] };
  return <div className="editor-read-state" role="status"><h1>{labels[state][0]}</h1><p>{labels[state][1]}</p>
    {state === 'error' ? <button className="editor-primary" onClick={onRetry}>重新加载</button> : state !== 'loading' && <Link to="/">返回首页</Link>}
  </div>;
}
