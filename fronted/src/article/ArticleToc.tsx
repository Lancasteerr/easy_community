import { useLayoutEffect, useRef, useState } from 'react';
import type { TocEntry } from './content';

/** 目录列表独立滚动，切换当前项时仅调整目录自身的位置。 */
export function ArticleToc({ entries, active, onJump }: { entries: TocEntry[]; active?: string; onJump: (id: string) => void }) {
  const [expanded, setExpanded] = useState(true);
  const list = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    const root = list.current;
    const selected = root?.querySelector<HTMLElement>('[aria-current="location"]');
    if (!root || !selected) return;
    const bounds = root.getBoundingClientRect();
    const item = selected.getBoundingClientRect();
    if (item.top < bounds.top) root.scrollTop += item.top - bounds.top;
    else if (item.bottom > bounds.bottom) root.scrollTop += item.bottom - bounds.bottom;
  }, [active, expanded]);
  if (!entries.length) return null;
  const minLevel = Math.min(...entries.map((entry) => entry.level));
  return <nav className="detail-toc" aria-label="文章目录">
    <div className="detail-toc-heading"><h2>目录</h2><button aria-expanded={expanded} aria-controls="article-toc-list"
      onClick={() => setExpanded(!expanded)}>{expanded ? '收起' : '展开'}<span aria-hidden="true">{expanded ? '⌃' : '⌄'}</span></button></div>
    {expanded && <div id="article-toc-list" className="detail-toc-list" ref={list}>
      {entries.map((entry) => <button key={entry.id} title={entry.text} aria-current={entry.id === active ? 'location' : undefined}
        style={{ paddingLeft: 12 + (entry.level - minLevel) * 12 }} onClick={() => onJump(entry.id)}><span>{entry.text}</span></button>)}
    </div>}
  </nav>;
}
