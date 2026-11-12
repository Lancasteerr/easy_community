import { Link } from 'react-router-dom';
import type { ReadState } from './useArticleDocument';

/** 作者读取结果直接映射设计中的统一页面状态，不依赖公开列表判断权限。 */
export function EditorReadState({ state, onRetry }: { state: Exclude<ReadState, 'ready'>; onRetry: () => void }) {
  const labels = { loading: ['正在加载文章', '正在读取文章内容，请稍候。'], error: ['文章加载失败', '暂时无法读取文章，请重试。'],
    missing: ['文章不存在', '该文章不存在，无法进入编辑。'], forbidden: ['没有编辑权限', '你没有编辑这篇文章的权限。'], hidden: ['文章已删除', '该文章已删除，无法继续编辑。'] };
  return <div className="editor-read-state" role="status"><h1>{labels[state][0]}</h1><p>{labels[state][1]}</p>
    {state === 'error' ? <button className="editor-primary" onClick={onRetry}>重新加载</button> : state !== 'loading' && <Link to="/">返回首页</Link>}
  </div>;
}
