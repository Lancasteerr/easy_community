import { useState } from 'react';
import defaultAvatar from '../assets/default-avatar.jpg';

/** 失败记录绑定图片地址，新用户或新头像不继承旧图片的失败状态。 */
export function Avatar({ src, size = 36 }: { src?: string | null; size?: number }) {
  const [failedSource, setFailedSource] = useState<string>();
  const source = src && src !== failedSource ? src : defaultAvatar;
  return <img className="avatar" src={source} alt="" width={size} height={size}
    onError={() => setFailedSource(source)} />;
}
