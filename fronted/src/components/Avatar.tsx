import { useState, type ReactNode } from 'react';
import defaultAvatar from '../assets/default-avatar.jpg';

/** 失败记录绑定图片地址，新用户或新头像不继承旧图片的失败状态。 */
export function Avatar({ src, size = 36, fallback }: { src?: string | null; size?: number; fallback?: ReactNode }) {
  const [failedSource, setFailedSource] = useState<string>();
  const source = src && src !== failedSource ? src : null;
  // 评论编辑器可用账号首字符占位，其余头像继续使用系统图片。
  if (!source && fallback !== undefined) return fallback;
  const image = source ?? defaultAvatar;
  return <img className="avatar" src={image} alt="" width={size} height={size}
    onError={() => setFailedSource(image)} />;
}
