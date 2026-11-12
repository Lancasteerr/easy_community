import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { clearRecovery, saveRecovery, takeRecovery } from './recovery';
import type { ArticleForm } from './model';

/** 外壳保留原作者会话；401 后先保存输入，成功后才允许卸载并前往登录。 */
export function useEditorRecovery(options: {
  ownerId: string; articleId?: string; ready: boolean; form: ArticleForm; baseline: ArticleForm;
  allowNavigation: () => void; restore: (form: ArticleForm, baseline: ArticleForm) => void;
}) {
  const auth = useAuth();
  const navigate = useNavigate();
  const current = useRef(options); current.current = options;
  const redirecting = useRef(false);
  const restored = useRef(false);
  const [error, setError] = useState('');
  const accountMatches = auth.status === 'authenticated' && auth.user?.userId === options.ownerId;
  const returnTo = options.articleId ? `/articles/${options.articleId}/edit` : '/articles/new';
  function goLogin() {
    if (redirecting.current) return;
    const value = current.current;
    try {
      if (value.ready) saveRecovery(value.ownerId, value.articleId, value.form, value.baseline);
    } catch { setError('暂时无法保存编辑内容，内容仍保留在当前页。请允许浏览器会话存储后重试。'); return; }
    redirecting.current = true; value.allowNavigation();
    navigate('/login', { replace: true, state: { returnTo } });
  }
  useEffect(() => {
    if (auth.status === 'guest') goLogin();
    else if (auth.status === 'authenticated' && !accountMatches) {
      clearRecovery(); setError('账号已切换，当前内容属于原账号。请重新登录原账号后继续。');
    }
  }, [auth.status, auth.user?.userId]);
  useEffect(() => {
    if (!accountMatches || !options.ready || restored.current) return;
    restored.current = true;
    const snapshot = takeRecovery(options.ownerId, options.articleId);
    if (snapshot) options.restore(snapshot.form, snapshot.baseline);
  }, [accountMatches, options.ready]);
  return { error, accountMatches, goLogin };
}
