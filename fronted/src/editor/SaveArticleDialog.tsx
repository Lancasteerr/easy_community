import { useCooldown } from '../hooks/useCooldown';
import { EditorDialog } from './EditorDialog';
import type { SaveState } from './useArticleSave';

/** 成功留在当前编辑会话；失败仅提供人工重试和继续编辑。 */
export function SaveArticleDialog({ state, onClose, onRetry, blocked }: { state: SaveState | null; onClose: () => void; onRetry: () => void; blocked: boolean }) {
  const seconds = useCooldown(state?.retryAt ?? 0);
  if (!state) return null;
  const operation = state.editing ? '保存' : '发布';
  const title = `${operation}${state.status === 'pending' ? '中…' : state.status === 'success' ? '成功' : '失败'}`;
  return <EditorDialog title={title} busy={state.status === 'pending'} onClose={onClose}>
    <p role="status">{state.message}</p>
    <div className="editor-dialog-actions">
      {state.status !== 'pending' && <button type="button" data-autofocus onClick={onClose}>继续编辑</button>}
      {state.status === 'failure' && <button type="button" className="editor-primary" disabled={blocked || seconds > 0} onClick={onRetry}>{seconds > 0 ? `${seconds}秒后重试` : `重新${operation}`}</button>}
    </div>
  </EditorDialog>;
}
