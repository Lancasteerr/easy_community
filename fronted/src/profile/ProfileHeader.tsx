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

/** 资料卡只保留一个操作入口，身份未确认时不误显示访客关注或本人编辑入口。 */
function ProfileAction({ self, known, blocked }: { self: boolean; known: boolean; blocked: boolean }) {
  const unavailable = useUnavailable();
  if (!known) return null;
  if (!self) return <button className="profile-action profile-follow" onClick={unavailable}>+ 关注</button>;
  return blocked ? <button className="profile-action" disabled>编辑资料</button>
    : <a className="profile-action" href="/settings/profile" target="_blank" rel="noopener noreferrer">编辑资料</a>;
}

/** 同一张卡片由CSS变量连续收缩，头像、名字与操作保持原有DOM和焦点。 */
export function ProfileHeader({ profile, self, known, blocked }: {
  profile: PublicUserProfile; self: boolean; known: boolean; blocked: boolean;
}) {
  return <div className="profile-header">
    <div className="profile-cover" role="img" aria-label="个人主页封面" />
    <div className="profile-identity-row">
      <Avatar src={profile.avatarUrl} size={96} />
      <div className="profile-identity">
        <h1 className="profile-username" title={profile.username}>{profile.username}</h1>
        <p className="profile-bio">{profile.bio || '—'}</p>
        <ProfileStats />
      </div>
      <ProfileAction self={self} known={known} blocked={blocked} />
    </div>
  </div>;
}
