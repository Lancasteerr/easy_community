import { useId, useLayoutEffect, useRef, useState, type ReactNode } from 'react';

const expandLabel = '…展开';
const graphemes = typeof Intl.Segmenter === 'function'
  ? new Intl.Segmenter('zh-CN', { granularity: 'grapheme' })
  : null;

/** 正文超限时，用同一个行盒测量预览文字和展开按钮。 */
function measurePreview(paragraph: HTMLParagraphElement, text: HTMLSpanElement, button: HTMLButtonElement,
  content: string, maxLines: number): string | null {
  const maxHeight = Number.parseFloat(getComputedStyle(paragraph).lineHeight) * maxLines + 0.5;
  text.textContent = content;
  try {
    if (paragraph.getBoundingClientRect().height <= maxHeight) return null;

    // 使用字素边界截取，避免把连写的表情或组合字符拆开。
    const offsets = graphemes
      ? Array.from(graphemes.segment(content), ({ index }) => index)
      : Array.from(content.matchAll(/./gsu), ({ index }) => index);
    button.hidden = false;
    let preview = '';
    let start = 0;
    let end = offsets.length - 1;
    while (start <= end) {
      const middle = Math.floor((start + end) / 2);
      const candidate = content.slice(0, offsets[middle]).replace(/[ \t]+$/, '');
      text.textContent = candidate;
      if (paragraph.getBoundingClientRect().height <= maxHeight) {
        preview = candidate;
        start = middle + 1;
      } else {
        end = middle - 1;
      }
    }
    return preview;
  } finally {
    // 测量结束后清空临时文字，避免在页面上保留重复正文。
    text.textContent = '';
    button.hidden = true;
  }
}

/** 一级评论和二级回复共用折叠布局，各自保存展开状态。 */
export function CommentContent({ content, maxLines, prefix }: { content: string; maxLines: number; prefix?: ReactNode }) {
  const [preview, setPreview] = useState<string | null>(null);
  const [expanded, setExpanded] = useState(false);
  const contentId = useId();
  const measureParagraph = useRef<HTMLParagraphElement>(null);
  const measureText = useRef<HTMLSpanElement>(null);
  const measureButton = useRef<HTMLButtonElement>(null);

  useLayoutEffect(() => {
    const paragraph = measureParagraph.current;
    const text = measureText.current;
    const button = measureButton.current;
    if (!paragraph || !text || !button) return;

    let width = 0;
    const measure = () => {
      width = paragraph.getBoundingClientRect().width;
      if (width > 0) setPreview(measurePreview(paragraph, text, button, content, maxLines));
    };
    measure();
    // 测量区域与正文等宽，宽度变化时更新断行，字体加载后也重新测量。
    const observer = typeof ResizeObserver === 'undefined' ? undefined : new ResizeObserver(([entry]) => {
      if (entry.contentRect.width !== width) measure();
    });
    observer?.observe(paragraph);
    document.fonts?.addEventListener('loadingdone', measure);
    return () => {
      observer?.disconnect();
      document.fonts?.removeEventListener('loadingdone', measure);
    };
  }, [content, maxLines, prefix]);

  return <div className="comment-content-wrap">
    <p className="comment-content">
      <span id={contentId}>{prefix}{expanded ? content : preview ?? content}</span>
      {preview !== null && <button type="button" className="comment-action comment-content-toggle"
        aria-controls={contentId} aria-expanded={expanded} onClick={() => setExpanded((previous) => !previous)}>
        {expanded ? '收起' : expandLabel}
      </button>}
    </p>
    <div className="comment-content-measure" aria-hidden="true" inert>
      <p className="comment-content" ref={measureParagraph}>
        <span>{prefix}<span ref={measureText} /></span>
        <button type="button" className="comment-action comment-content-toggle" ref={measureButton} hidden>{expandLabel}</button>
      </p>
    </div>
  </div>;
}
