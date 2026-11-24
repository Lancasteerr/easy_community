import { useEffect, useRef } from 'react';
import { Avatar } from '../../components/Avatar';
import { validCommentText, type CommentDraft, type ReplyTarget } from './useCommentDrafts';

interface ComposerProps {
  draft: CommentDraft;
  target?: ReplyTarget;
  enabled: boolean;
  onChange: (text: string) => void;
  onPublish: (mode: 'submit' | 'check') => void;
  onCancel?: () => void;
}

/** 主评论和行内回复共用同一套禁用、发布与确认提示。 */
export function CommentComposer({ draft, target, enabled, onChange, onPublish, onCancel }: ComposerProps) {
  const input = useRef<HTMLTextAreaElement>(null);
  useEffect(() => {
    if (target) input.current?.focus({ preventScroll: true });
  }, [target]);
  const publishing = draft.state === 'publishing';
  const uncertain = draft.state === 'uncertain';
  const label = target ? `回复 @${target.username}` : '写下你的想法，参与讨论';
  return <form className="comment-composer" onSubmit={(event) => { event.preventDefault(); onPublish('submit'); }}>
    <Avatar size={36} />
    <div className="comment-editor">
      {target && <p className="comment-editor-target">{label}</p>}
      <textarea ref={input} value={draft.text} aria-label={label} placeholder={target ? '写下你的回复' : label}
        rows={3} readOnly={publishing || uncertain} onChange={(event) => onChange(event.target.value)} />
      <div className="comment-editor-actions">
        {onCancel && <button type="button" className="comment-action" onClick={onCancel}>取消</button>}
        {uncertain && <button type="button" className="comment-action" disabled={!enabled} onClick={() => onPublish('check')}>查询结果</button>}
        <button type="submit" className="comment-primary" disabled={!enabled || publishing || !validCommentText(draft.text)}>
          {publishing ? '发布中…' : uncertain ? '重试提交' : '发布'}
        </button>
      </div>
      {draft.state === 'failed' && <p className="comment-feedback" role="status">发布失败，请重试。已保留你输入的内容。</p>}
      {uncertain && <p className="comment-feedback" role="status">暂未确认发布结果，已保留你输入的内容。</p>}
      {Array.from(draft.text.trim()).length > 300 && <p className="comment-feedback">评论最多 300 个字符。</p>}
    </div>
  </form>;
}
