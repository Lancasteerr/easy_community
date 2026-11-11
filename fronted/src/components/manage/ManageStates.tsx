import type { ReactNode } from 'react';
import { useUnavailable } from '../Toast';
import stateDocument from '../../assets/manage-state-document.svg';

/** 五行骨架对应设计交付，首次读取与已有内容的刷新采用不同反馈。 */
export function ManageSkeleton() {
  return <div className="manage-skeleton" role="status" aria-label="正在加载已发布文章">{Array.from({ length: 5 }, (_, index) =>
    <div className="manage-skeleton-row" key={index}><i /><div><b /><span /><em /></div><small /></div>,
  )}</div>;
}

export function ManageEmpty() {
  const unavailable = useUnavailable();
  return <div className="manage-centered-state"><div className="manage-state-content">
    <img src={stateDocument} alt="" aria-hidden="true" width={64} height={64} /><h2>暂无已发布内容</h2>
    <p>把值得分享的内容，发布到社区吧</p>
    <button className="manage-primary" onClick={unavailable}>去发布</button>
  </div></div>;
}

/** 身份读取与文章读取共享设计稿失败态，不把内部错误分类转成额外提示条。 */
export function ManageError({ onRetry, seconds = 0 }: { onRetry: () => void; seconds?: number }) {
  return <div className="manage-centered-state" role="status"><div className="manage-state-content">
    <img src={stateDocument} alt="" aria-hidden="true" width={64} height={64} />
    <h2>内容加载失败</h2><p>请检查网络连接后重试</p><button className="manage-primary" disabled={seconds > 0} onClick={onRetry}>
      {seconds > 0 ? `${seconds}秒后重试` : '重新加载'}</button></div></div>;
}

/** 失败时只展示原稿状态，保留不可交互的列表节点，使重试后仍能恢复原位置。 */
export function ManageLoadBoundary({ failed, loading = false, onRetry, seconds, children }: {
  failed: boolean; loading?: boolean; onRetry: () => void; seconds: number; children: ReactNode;
}) {
  const covered = failed || loading;
  return <div className="manage-load-boundary">
    <div className={`manage-load-content${covered ? ' is-concealed' : ''}`} inert={covered} aria-hidden={covered || undefined}>{children}</div>
    {covered && <div className="manage-load-overlay">{loading ? <ManageSkeleton /> : <ManageError onRetry={onRetry} seconds={seconds} />}</div>}
  </div>;
}
