import { useEffect, useId, useLayoutEffect, useRef } from 'react';
import type { PublicUserProfile } from '../../api/types';
import { Avatar } from '../../components/Avatar';
import { commentLength, MAX_COMMENT_LENGTH, type CommentDraft, type ReplyTarget } from './useCommentDrafts';

interface ComposerProps {
  draft: CommentDraft;
  target?: ReplyTarget;
  viewerProfile: Pick<PublicUserProfile, 'username' | 'avatarUrl'> | null;
  enabled: boolean;
  onChange: (text: string) => void;
  onPublish: () => void;
  onCancel?: () => void;
}

/** 先恢复行数决定的初始高度，再测量正文，删减内容时也能缩回。 */
function fitContent(input: HTMLTextAreaElement) {
  input.style.height = 'auto';
  input.style.height = `${input.scrollHeight}px`;
}

/** 主评论和行内回复共用同一套禁用、发布与确认提示。 */
export function CommentComposer({ draft, target, viewerProfile, enabled, onChange, onPublish, onCancel }: ComposerProps) {
  const input = useRef<HTMLTextAreaElement>(null);
  const countId = useId();
  useLayoutEffect(() => {
    if (input.current) fitContent(input.current);
  }, [draft.text]);
  useLayoutEffect(() => {
    const element = input.current;
    if (!element || typeof ResizeObserver === 'undefined') return;
    let lastWidth: number | undefined;
    const observer = new ResizeObserver(([entry]) => {
      const { width } = entry.contentRect;
      // 只有宽度变化会影响折行，忽略调整高度引起的回调。
      if (width === lastWidth) return;
      lastWidth = width;
      if (width > 0) fitContent(element);
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  useEffect(() => {
    if (target) input.current?.focus({ preventScroll: true });
  }, [target]);
  const publishing = draft.state === 'publishing';
  const uncertain = draft.state === 'uncertain';
  const count = commentLength(draft.text);
  const overLimit = count > MAX_COMMENT_LENGTH;
  const label = target ? `回复 @${target.username}` : '写下你的想法，参与讨论';
  const [initial] = Array.from(viewerProfile?.username.trim() ?? '');
  return <form className="comment-composer" onSubmit={(event) => { event.preventDefault(); onPublish(); }}>
    <Avatar src={viewerProfile?.avatarUrl} size={36}
      fallback={<span className="comment-avatar" aria-hidden="true">{initial}</span>} />
    <div className="comment-editor">
      {target && <p className="comment-editor-target">{label}</p>}
      <textarea ref={input} value={draft.text} aria-label={label} aria-describedby={countId} aria-invalid={overLimit || undefined}
        placeholder={target ? '写下你的回复' : label} rows={3} readOnly={publishing || uncertain}
        onChange={(event) => onChange(event.target.value)} />
      {draft.state === 'failed' && <p className="comment-feedback" role="status">发布失败，请重试。已保留你输入的内容。</p>}
      {uncertain && <p className="comment-feedback" role="status">暂未确认发布，请重试。</p>}
      {overLimit && <p className="comment-feedback">评论最多 {MAX_COMMENT_LENGTH} 个字符。</p>}
      <div className="comment-editor-footer">
        <span id={countId} className="comment-count" data-over-limit={overLimit}>{count}/{MAX_COMMENT_LENGTH}</span>
        <div className="comment-editor-actions">
          {onCancel && <button type="button" className="comment-action" onClick={onCancel}>取消</button>}
          <button type="submit" className="comment-primary" disabled={!enabled || publishing || count === 0 || overLimit}>
            {publishing ? '发布中…' : uncertain ? '重试提交' : '发布'}
          </button>
        </div>
      </div>
    </div>
  </form>;
}
