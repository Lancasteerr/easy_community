import type { PublicUserProfile } from '../api/types';
import { Avatar } from '../components/Avatar';
import { useUnavailable } from '../components/Toast';

/** 样例统计集中管理，与真实列表长度和关注按钮完全独立。 */
export const profileSamples = { articles: '12', following: '19', followers: '1.2k' } as const;

export function ProfileStats() {
  return <dl className="profile-stats" aria-label="个人统计">
    <div><dd>{profileSamples.articles}</dd><dt>文章</dt></div>
    <div><dd>{profileSamples.following}</dd><dt>关注</dt></div>
    <div><dd>{profileSamples.followers}</dd><dt>粉丝</dt></div>
  </dl>;
}

/** 完整与简要卡片共用操作，身份未确认时不误显示访客关注或本人编辑入口。 */
function ProfileAction({ self, known, blocked }: { self: boolean; known: boolean; blocked: boolean }) {
  const unavailable = useUnavailable();
  if (!known) return null;
  if (!self) return <button className="profile-action profile-follow" onClick={unavailable}>+ 关注</button>;
  return blocked ? <button className="profile-action" disabled>编辑资料</button>
    : <a className="profile-action" href="/settings/profile" target="_blank" rel="noopener noreferrer">编辑资料</a>;
}

/** 简要卡片保留同一份资料，隐藏态由外层 hidden 管理，避免重复焦点。 */
export function ProfileHeader({ profile, self, known, blocked, compact = false }: {
  profile: PublicUserProfile; self: boolean; known: boolean; blocked: boolean; compact?: boolean;
}) {
  return <div className={`profile-header${compact ? ' profile-header-compact' : ''}`}>
    {!compact && <div className="profile-cover" role="img" aria-label="个人主页封面" />}
    <div className="profile-identity-row">
      <Avatar src={profile.avatarUrl} size={compact ? 48 : 96} />
      <div className="profile-identity">
        {compact ? <p className="profile-username" title={profile.username}>{profile.username}</p>
          : <h1 className="profile-username" title={profile.username}>{profile.username}</h1>}
        {!compact && <p className="profile-bio">{profile.bio || '—'}</p>}
        <ProfileStats />
      </div>
      <ProfileAction self={self} known={known} blocked={blocked} />
    </div>
  </div>;
}
