import type { CSSProperties } from 'react';
import search from '../assets/search.svg';
import edit from '../assets/edit.svg';
import like from '../assets/like.svg';
import comment from '../assets/comment.svg';
import terminal from '../assets/terminal.svg';
import trending from '../assets/trending.svg';
import compass from '../assets/compass.svg';
import publish from '../assets/publish.svg';
import inbox from '../assets/inbox.svg';
import eye from '../assets/eye.svg';
import eyeOff from '../assets/eye-off.svg';

const sources = { search, edit, like, comment, terminal, trending, compass, publish, inbox, eye, eyeOff };

/** 用原稿 SVG 轮廓作遮罩，使悬停、点赞和聚焦颜色随组件状态继承。 */
export function Icon({ name, size = 20 }: { name: keyof typeof sources; size?: number }) {
  return <span aria-hidden="true" className="icon" style={{
    '--icon-source': `url("${sources[name]}")`, width: size, height: size,
  } as CSSProperties} />;
}
