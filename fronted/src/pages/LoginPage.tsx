import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { ApiError, errorMessage, getCsrf, postLogin } from '../api/client';
import type { CsrfToken } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { Icon } from '../components/Icon';
import { useToast } from '../components/Toast';
import { useCooldown } from '../hooks/useCooldown';

/** 只做登录所必需的前端校验，凭证规范与认证结果由后端决定。 */
export function LoginPage() {
  const auth = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  // 回跳仅接受本轮受保护路由，外部地址或任意路由状态均回到首页。
  const target: unknown = location.state?.returnTo;
  const destination = typeof target === 'string' && (target === '/manage/articles' || target === '/articles/new'
    || /^\/articles\/[1-9]\d{0,18}\/edit$/.test(target)) ? target : '/';
  const notify = useToast();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [visible, setVisible] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [invalid, setInvalid] = useState<'username' | 'password' | null>(null);
  const [retryAt, setRetryAt] = useState(0);
  const seconds = useCooldown(retryAt);
  const usernameInput = useRef<HTMLInputElement>(null);
  const passwordInput = useRef<HTMLInputElement>(null);
  const controller = useRef<AbortController | null>(null);
  const submitting = useRef(false);
  const csrf = useRef<CsrfToken | null>(null);

  useEffect(() => {
    document.title = '登录 · Easy Community';
    window.scrollTo({ top: 0, left: 0, behavior: 'instant' });
    return () => { controller.current?.abort(); };
  }, []);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting.current || retryAt > Date.now()) return;
    const account = username.trim();
    if (!account || !password) {
      const field = !account ? 'username' : 'password';
      setInvalid(field);
      setMessage(!account ? '请输入账号' : '请输入密码');
      (!account ? usernameInput : passwordInput).current?.focus();
      return;
    }
    submitting.current = true;
    setBusy(true); setInvalid(null); setMessage('');
    const operation = new AbortController();
    controller.current = operation;
    const signal = operation.signal;
    try {
      csrf.current ??= await getCsrf(signal);
      const identity = await postLogin(account, password, csrf.current, signal);
      // POST 已成功，后续读取失败只能影响资料更新，不能重发密码或报告认证失败。
      const results = await Promise.allSettled([
        getCsrf(signal).then((token) => { csrf.current = token; }),
        auth.refresh(identity),
      ]);
      if (signal.aborted) return;
      if (results.some((result) => result.status === 'rejected') || (results[1].status === 'fulfilled' && results[1].value?.error)) {
        notify('登录成功，部分资料暂时无法刷新');
      }
      navigate(destination, { replace: true });
    } catch (error) {
      if (signal.aborted) return;
      if (error instanceof ApiError && error.status === 403) {
        csrf.current = null;
        try {
          csrf.current = await getCsrf(signal);
          if (!signal.aborted) setMessage('登录凭据已刷新，请再次点击登录');
        } catch (refreshError) {
          if (!signal.aborted) {
            setMessage(errorMessage(refreshError));
            if (refreshError instanceof ApiError) setRetryAt(refreshError.retryAt);
          }
        }
      } else if (error instanceof ApiError && error.code === 'INVALID_CREDENTIALS') {
        setMessage('账号或密码错误，请重试');
      } else {
        setMessage(error instanceof ApiError && error.code === 'INVALID_USERNAME'
          ? '账号须为4至32位英文字母、数字或下划线，区分大小写' : errorMessage(error));
        if (error instanceof ApiError) setRetryAt(error.retryAt);
      }
    } finally {
      if (!signal.aborted) { submitting.current = false; setBusy(false); }
    }
  }

  // 登录过程先更新全局身份，等待后续读取完成后再统一导航。
  if (auth.status === 'authenticated' && !busy) return <Navigate to={destination} replace />;
  const editField = () => { setInvalid(null); setMessage(''); };
  return <main className="login-page">
    <div className="login-decoration" aria-hidden="true" />
    <section className="login-panel" aria-labelledby="login-heading">
      <div className="login-brand"><Link to="/">Easy Community</Link></div>
      <div className="login-body">
        <h1 id="login-heading">账号密码登录</h1>
        <form className="login-form" onSubmit={(event) => void submit(event)} noValidate aria-busy={busy}>
          <div className="login-fields">
            <div className="login-field">
              <label htmlFor="username">账号</label>
              <div className={`login-input${invalid === 'username' ? ' input-invalid' : ''}`}>
                <input id="username" name="username" ref={usernameInput} autoComplete="username" maxLength={128} required
                  value={username} placeholder="请输入账号" disabled={busy} aria-invalid={invalid === 'username'} aria-describedby="login-feedback"
                  onChange={(event) => { setUsername(event.target.value); editField(); }} />
              </div>
            </div>
            <div className="login-field">
              <label htmlFor="password">密码</label>
              <div className={`login-input${invalid === 'password' ? ' input-invalid' : ''}`}>
                <input id="password" name="password" ref={passwordInput} type={visible ? 'text' : 'password'} autoComplete="current-password"
                  maxLength={256} required value={password} placeholder="请输入密码" disabled={busy}
                  aria-invalid={invalid === 'password'} aria-describedby="login-feedback"
                  onChange={(event) => { setPassword(event.target.value); editField(); }} />
                <button className="password-toggle" type="button" disabled={busy} aria-label={visible ? '隐藏密码' : '显示密码'}
                  aria-pressed={visible} onClick={() => setVisible(!visible)}><Icon name={visible ? 'eyeOff' : 'eye'} /></button>
              </div>
            </div>
          </div>
          <div id="login-feedback" className="login-feedback" role="status" aria-live="polite">{message}</div>
          <button type="submit" className="login-submit" disabled={busy || seconds > 0}>
            {busy ? <><i className="spinner" aria-hidden="true" />登录中…</> : seconds ? `${seconds}秒后重试` : '登录'}
          </button>
        </form>
      </div>
      <footer className="login-footer"><Link to="/">返回首页</Link><p>© Easy Community</p></footer>
    </section>
  </main>;
}
