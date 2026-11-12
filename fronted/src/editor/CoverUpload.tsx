import { useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/client';
import { imageFileError, uploadArticleImage, uploadError, type UploadedMedia } from '../api/media';

/** 更换成功前不覆盖原封面；失败项显式移除后才允许以无封面提交。 */
export function CoverUpload({ value, disabled, onChange, onBlocked, onUnauthorized }: {
  value: UploadedMedia | null; disabled: boolean; onChange: (value: UploadedMedia | null) => void;
  onBlocked: (blocked: boolean) => void; onUnauthorized: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [percent, setPercent] = useState(0);
  const [message, setMessage] = useState('');
  const [brokenUrl, setBrokenUrl] = useState('');
  const [previewRevision, setPreviewRevision] = useState(0);
  const pending = useRef<AbortController | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const previousFile = useRef<File | null>(null);
  const options = useRef({ onBlocked, onUnauthorized }); options.current = { onBlocked, onUnauthorized };
  useEffect(() => () => { pending.current?.abort(); }, []);
  async function start(file: File) {
    if (pending.current || disabled) return;
    previousFile.current = file;
    const error = imageFileError(file, 5 * 1024 * 1024);
    if (error) { setMessage(error); options.current.onBlocked(!value); return; }
    const controller = new AbortController(); pending.current = controller;
    setBusy(true); setPercent(0); setMessage(''); options.current.onBlocked(true);
    try {
      const uploaded = await uploadArticleImage(file, 5 * 1024 * 1024, controller.signal, setPercent);
      if (!controller.signal.aborted) { onChange(uploaded); options.current.onBlocked(false); previousFile.current = null; }
    } catch (failure) {
      if (controller.signal.aborted) return;
      if (failure instanceof ApiError && failure.status === 401) options.current.onUnauthorized();
      setMessage(`${value ? '更换失败，保留原封面。' : ''}${uploadError(failure)}`);
      options.current.onBlocked(!value);
    } finally { if (!controller.signal.aborted) { pending.current = null; setBusy(false); } }
  }
  return <div className="editor-cover-field">
    <h3>封面 <small>选填</small></h3>
    <div className="editor-cover-row">
      <button type="button" className="editor-cover-slot" aria-label={value ? '更换封面' : '上传封面'} disabled={disabled || busy} onClick={() => fileInput.current?.click()}>
        {value ? <><img style={{ display: brokenUrl === value.publicUrl ? 'none' : undefined }}
          src={previewRevision ? `${value.publicUrl}${value.publicUrl.includes('?') ? '&' : '?'}preview=${previewRevision}` : value.publicUrl}
          alt="文章封面" onError={() => setBrokenUrl(value.publicUrl)} />{brokenUrl === value.publicUrl && <span>图片暂时无法显示</span>}</> : <span>＋ 上传封面</span>}
        {busy && <span className="editor-cover-progress" role="status">{percent < 100 ? `上传中 ${percent}%` : '正在处理上传响应…'}</span>}
      </button>
      <div className="editor-cover-description"><p>支持 JPG、PNG、WebP，最大 5 MiB；建议使用 16:9 图片</p>
        {(value || message) && <div className="editor-cover-actions">
          <button type="button" disabled={disabled || busy} onClick={() => fileInput.current?.click()}>{value ? '更换' : '重新选择'}</button>
          {value && brokenUrl === value.publicUrl && <button type="button" disabled={disabled || busy} onClick={() => {
            // 只在用户主动操作时刷新预览；不轮询，不等待发布，不影响已选择的媒体身份。
            setBrokenUrl(''); setPreviewRevision(Date.now());
          }}>重新加载预览</button>}
          {message && previousFile.current && <button type="button" disabled={disabled || busy} onClick={() => { void start(previousFile.current!); }}>重新上传</button>}
          <button type="button" disabled={disabled || busy} onClick={() => { onChange(null); setMessage(''); previousFile.current = null; options.current.onBlocked(false); }}>移除</button>
        </div>}
        {message && <p className="editor-error" role="alert">{message}</p>}
      </div>
    </div>
    <input hidden type="file" accept="image/jpeg,image/png,image/webp" ref={fileInput} aria-label="选择封面图片" disabled={disabled || busy}
      onChange={(event) => { const file = event.target.files?.[0]; if (file) void start(file); event.target.value = ''; }} />
  </div>;
}
