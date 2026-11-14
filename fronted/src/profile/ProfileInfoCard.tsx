import type { PublicUserProfile } from '../api/types';
import { profileSamples } from './ProfileHeader';

/** 加入日期只用于展示，使用浏览器当地时区，不参与查询或身份判断。 */
function joinedDate(value: string) {
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return '—';
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

export function ProfileInfoCard({ profile }: { profile: PublicUserProfile }) {
  return <aside className="profile-info glass-panel" aria-label="个人信息">
    <h2 className="panel-heading">个人信息<span>INFO</span></h2>
    <p>{profile.bio || '—'}</p>
    <dl><div><dt>用户 ID</dt><dd>{profile.userId}</dd></div>
      <div><dt>加入时间</dt><dd>{joinedDate(profile.createdAt)}</dd></div>
      <div><dt>发布文章</dt><dd>{profileSamples.articles}</dd></div></dl>
  </aside>;
}
