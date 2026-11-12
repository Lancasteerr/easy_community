import { useLayoutEffect, useRef } from 'react';
import type { PreparedContent } from './content';

/** React 只管理正文根节点，锚点及代码着色在隔离的正文子树中生效。 */
export function ArticleBody({ content }: { content: PreparedContent }) {
  const root = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    const element = root.current;
    if (!element) return;
    let disposed = false;
    // 后端没有图片尺寸；加载失败替换为有文字的块，避免破图破坏阅读。
    const replaceFailedImage = (image: HTMLImageElement) => {
      const placeholder = document.createElement('span');
      placeholder.className = 'detail-image-failed';
      placeholder.setAttribute('role', 'img');
      placeholder.setAttribute('aria-label', image.alt || '正文图片加载失败');
      placeholder.textContent = image.alt ? `图片无法显示：${image.alt}` : '正文图片暂时无法显示';
      if (image.dataset.readBlock) placeholder.dataset.readBlock = image.dataset.readBlock;
      image.replaceWith(placeholder);
    };
    const fail = (event: Event) => {
      if (event.target instanceof HTMLImageElement) replaceFailedImage(event.target);
    };
    element.addEventListener('error', fail, true);
    // 捕捉在监听器建立前已经失败的缓存图片。
    element.querySelectorAll('img').forEach((image) => {
      if (image.complete && image.naturalWidth === 0) replaceFailedImage(image);
    });
    if (element.querySelector('pre code')) void import('./highlight').then((module) => {
      if (!disposed) module.highlightCode(element);
    }).catch(() => { /* 高亮资源失败仍保留完整纯文本正文。 */ });
    return () => { disposed = true; element.removeEventListener('error', fail, true); };
  }, [content]);
  return <div ref={root} className="detail-prose" dangerouslySetInnerHTML={{ __html: content.html }} />;
}
