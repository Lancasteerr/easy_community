import { Icon } from '../Icon';
import { useUnavailable } from '../Toast';

/** 五行骨架对应设计交付，首次读取与已有内容的刷新采用不同反馈。 */
export function ManageSkeleton() {
  return <div className="manage-skeleton" role="status" aria-label="正在加载已发布文章">{Array.from({ length: 5 }, (_, index) =>
    <div className="manage-skeleton-row" key={index}><i /><div><b /><span /><em /></div><small /></div>,
  )}</div>;
}

export function ManageEmpty({ onRefresh, disabled = false }: { onRefresh: () => void; disabled?: boolean }) {
  const unavailable = useUnavailable();
  return <div className="manage-centered-state">
    <Icon name="document" size={64} /><h2>当前暂无可显示的已发布内容</h2>
    <p>把值得分享的内容，发布到社区吧</p>
    <div className="manage-state-actions"><button className="manage-primary" onClick={unavailable}>去发布</button>
      <button className="manage-text-action" disabled={disabled} onClick={onRefresh}>刷新列表</button></div>
  </div>;
}

export function ManageError({ message, onRetry, seconds = 0 }: { message: string; onRetry: () => void; seconds?: number }) {
  return <div className="manage-centered-state" role="status"><Icon name="document" size={64} />
    <h2>内容加载失败</h2><p>{message}</p><button className="manage-primary" disabled={seconds > 0} onClick={onRetry}>
      {seconds > 0 ? `${seconds}秒后重试` : '重新加载'}</button></div>;
}
