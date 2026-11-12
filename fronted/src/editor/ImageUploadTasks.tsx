import type { useBodyImages } from './useBodyImages';

/** 临时上传反馈放在正文容器外，提交时不会把占位发送给后端。 */
export function ImageUploadTasks({ uploads, disabled }: { uploads: ReturnType<typeof useBodyImages>; disabled: boolean }) {
  return <div className="editor-upload-tasks" aria-live="polite">
    {uploads.message && <p className="editor-error">{uploads.message}</p>}
    {uploads.tasks.map((task) => <div key={task.id} className="editor-upload-task"><span>{task.file.name}</span>
      <span>{task.status === 'queued' ? '等待上传' : task.status === 'failed' ? task.error : task.percent < 100 ? `上传中 ${task.percent}%` : '正在处理上传响应…'}</span>
      {task.status === 'failed' && <button type="button" disabled={disabled} onClick={() => uploads.retry(task.id)}>重新上传</button>}
      <button type="button" disabled={disabled} onClick={() => uploads.remove(task.id)}>移除上传项</button>
    </div>)}
  </div>;
}
