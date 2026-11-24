import { useEffect, useRef, useState } from 'react';
import { ApiError, getCsrf } from '../../api/client';
import { getCommentSubmission, submitComment, type CommentSubmission } from '../../api/comments';

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

async function confirm(articleId: string, id: string, signal: AbortSignal): Promise<CommentSubmission | undefined> {
  const checking = AbortSignal.any([signal, AbortSignal.timeout(10_000)]);
  while (!checking.aborted) {
    await new Promise((resolve) => setTimeout(resolve, 650));
    if (checking.aborted) break;
    const result = await getCommentSubmission(articleId, id, checking);
    if (result.state !== 'PUBLISHING') return result;
  }
}

/** 按被回复的评论保存草稿，只有成功的对应提交才能清空它。 */
export function useCommentDrafts(articleId: string, enabled: boolean, userId: string | undefined,
  onPublished: (target: ReplyTarget | null, submissionId: string, count?: number) => void) {
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

  async function publish(reply: ReplyTarget | null, mode: 'submit' | 'check') {
    const key = reply?.id ?? ROOT_DRAFT;
    const draft = getDraft(reply);
    if (!enabled || pending.current.has(key) || !validCommentText(draft.text) || (mode === 'check' && !draft.submissionId)) return;
    const id = draft.submissionId ?? crypto.randomUUID();
    const controller = new AbortController();
    const { signal } = controller;
    pending.current.set(key, controller);
    update(key, { ...draft, submissionId: id, state: 'publishing' });
    let sent = false;
    let checking = mode === 'check';
    try {
      let result: CommentSubmission | undefined;
      if (mode === 'check') {
        result = await getCommentSubmission(articleId, id, signal);
      } else {
        const csrf = await getCsrf(signal);
        if (signal.aborted) return;
        sent = true;
        result = await submitComment(articleId, id, draft.text, reply?.id ?? null, csrf, signal);
        if (result.state === 'PUBLISHING') {
          checking = true;
          result = await confirm(articleId, id, signal);
        }
      }
      if (signal.aborted) return;
      if (result?.state === 'PUBLISHED') {
        update(key, emptyDraft);
        if (reply) setTarget((active) => active?.id === reply.id ? null : active);
        onPublished(reply, id, result.commentCount);
      } else if (result?.state === 'FAILED') {
        update(key, { text: draft.text, state: 'failed' });
      } else {
        update(key, { ...draft, submissionId: id, state: 'uncertain' });
      }
    } catch (error) {
      if (signal.aborted) return;
      // 只有提交前的错误或明确的首次拒绝才能释放编号，未知结果继续沿用原编号。
      const rejected = error instanceof ApiError && error.status >= 400 && error.status < 500;
      if (!checking && !draft.submissionId && (!sent || rejected)) {
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
