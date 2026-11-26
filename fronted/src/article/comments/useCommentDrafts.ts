import { useEffect, useRef, useState } from 'react';
import { ApiError, getCsrf } from '../../api/client';
import { submitComment, type CommentSubmission } from '../../api/comments';

export interface ReplyTarget {
  id: string;
  rootId: string;
  username: string;
}

export interface CommentDraft {
  text: string;
  state: 'editing' | 'publishing' | 'uncertain' | 'failed';
  submissionId?: string;
}

const ROOT_DRAFT = 'root';
const emptyDraft: CommentDraft = { text: '', state: 'editing' };
export const MAX_COMMENT_LENGTH = 300;

/** 计数器与发布校验共用口径，去掉首尾空白后按 Unicode 码点计数。 */
export function commentLength(text: string) {
  return Array.from(text.trim()).length;
}

export function validCommentText(text: string) {
  const length = commentLength(text);
  return length > 0 && length <= MAX_COMMENT_LENGTH;
}

/** 按被回复的评论保存草稿，只有成功的对应提交才能清空它。 */
export function useCommentDrafts(articleId: string, enabled: boolean, userId: string | undefined,
  onPublished: (result: CommentSubmission) => void) {
  const [drafts, setDrafts] = useState<Record<string, CommentDraft>>({});
  const [target, setTarget] = useState<ReplyTarget | null>(null);
  const pending = useRef(new Map<string, AbortController>());
  const getDraft = (reply: ReplyTarget | null) => drafts[reply?.id ?? ROOT_DRAFT] ?? emptyDraft;
  const update = (key: string, value: CommentDraft) => setDrafts((current) => ({ ...current, [key]: value }));

  useEffect(() => {
    setDrafts({});
    setTarget(null);
    const requests = pending.current;
    return () => {
      requests.forEach((controller) => controller.abort());
      requests.clear();
    };
  }, [articleId, userId]);

  function edit(reply: ReplyTarget | null, text: string) {
    const current = getDraft(reply);
    if (current.submissionId) return;
    update(reply?.id ?? ROOT_DRAFT, { text, state: 'editing' });
  }

  async function publish(reply: ReplyTarget | null) {
    const key = reply?.id ?? ROOT_DRAFT;
    const draft = getDraft(reply);
    if (!enabled || pending.current.has(key) || !validCommentText(draft.text)) return;
    const id = draft.submissionId ?? crypto.randomUUID();
    const controller = new AbortController();
    const { signal } = controller;
    pending.current.set(key, controller);
    update(key, { ...draft, submissionId: id, state: 'publishing' });
    let sent = false;
    try {
      const csrf = await getCsrf(signal);
      if (signal.aborted) return;
      sent = true;
      // 重试沿用上次提交编号、原正文和回复目标，由同一个 PUT 确认落库结果。
      const result = await submitComment(articleId, id, draft.text, reply?.id ?? null, csrf, signal);
      if (signal.aborted) return;
      if (result.state === 'PUBLISHED') {
        update(key, emptyDraft);
        if (reply) setTarget((active) => active?.id === reply.id ? null : active);
        onPublished(result);
      } else {
        update(key, { text: draft.text, state: 'failed' });
      }
    } catch (error) {
      if (signal.aborted) return;
      const rejected = error instanceof ApiError && error.status >= 400 && error.status < 500;
      const conflict = error instanceof ApiError && error.code === 'COMMENT_OPERATION_CONFLICT';
      // 明确拒绝可以重新编辑，曾经发出且结果未知的请求继续沿用原编号。
      if (conflict || (!draft.submissionId && (!sent || rejected))) {
        update(key, { text: draft.text, state: 'failed' });
      } else {
        update(key, { ...draft, submissionId: id, state: 'uncertain' });
      }
    } finally {
      if (pending.current.get(key) === controller) pending.current.delete(key);
    }
  }

  return { target, setTarget, getDraft, edit, publish };
}
