import { useEffect, useId, useRef, useState } from 'react';
import { searchTags } from '../api/articles';
import { ApiError } from '../api/client';
import { codePoints, type Tag } from './model';

/** 仅消费关键词搜索，不把空结果误认为完整目录；每个查询拥有独立取消信号。 */
export function TagSelector({ value, disabled, onChange, onUnauthorized }: {
  value: Tag[]; disabled: boolean; onChange: (tags: Tag[]) => void; onUnauthorized: () => void;
}) {
  const [query, setQuery] = useState('');
  const [items, setItems] = useState<Tag[]>([]);
  const [state, setState] = useState<'idle' | 'loading' | 'ready' | 'error'>('idle');
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const [revision, setRevision] = useState(0);
  const input = useRef<HTMLInputElement>(null);
  const id = useId();
  const callbacks = useRef({ onUnauthorized }); callbacks.current = { onUnauthorized };
  const normalized = query.normalize('NFKC').trim().toLowerCase();
  const tooLong = codePoints(query) > 128 || codePoints(normalized) > 32;
  const full = value.length >= 5;
  useEffect(() => {
    setItems([]); setActive(0);
    if (!normalized || tooLong || full || disabled) { setState('idle'); return; }
    const controller = new AbortController();
    setState('loading');
    const timer = setTimeout(() => {
      searchTags(query, controller.signal).then((result) => {
        if (!controller.signal.aborted) { setItems(result); setState('ready'); }
      }).catch((error) => {
        if (controller.signal.aborted) return;
        if (error instanceof ApiError && error.status === 401) callbacks.current.onUnauthorized();
        setState('error');
      });
    }, 300);
    return () => { clearTimeout(timer); controller.abort(); };
  }, [query, revision, full, disabled]);
  const choose = (tag: Tag) => {
    if (disabled || full || value.some((chosen) => chosen.tagId === tag.tagId)) return;
    onChange([...value, tag]); input.current?.focus();
  };
  return <div className="editor-tag-field" onBlur={(event) => { if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false); }}>
    <h3>标签 <small>选填</small></h3>
    <input ref={input} role="combobox" aria-label="搜索已有标签" autoComplete="off" value={query} disabled={disabled || full}
      aria-expanded={open && !!normalized && !full} aria-controls={id} aria-autocomplete="list"
      aria-activedescendant={open && state === 'ready' && items[active] ? `${id}-${active}` : undefined}
      placeholder={full ? '已达标签上限' : '搜索已有标签'} onFocus={() => setOpen(true)}
      onChange={(event) => { setQuery(event.target.value); setOpen(true); }} onKeyDown={(event) => {
        if (event.key === 'Escape') { setOpen(false); event.preventDefault(); return; }
        if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
          event.preventDefault(); setOpen(true); if (items.length) setActive((index) => (index + (event.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length);
        }
        if (event.key === 'Enter') { event.preventDefault(); if (open && state === 'ready' && items[active]) choose(items[active]); }
      }} />
    {tooLong && <p className="editor-error">搜索关键词最多 32 字</p>}
    {open && !!normalized && !tooLong && !full && !disabled && <div className="editor-tag-results" id={id} role="listbox" aria-label="匹配标签">
      {state === 'loading' && <p role="status">正在搜索标签…</p>}
      {state === 'error' && <div role="status">标签搜索失败 <button type="button" onMouseDown={(event) => event.preventDefault()} onClick={() => setRevision((count) => count + 1)}>重新加载</button></div>}
      {state === 'ready' && !items.length && <p>没有匹配的标签，试试其他关键词</p>}
      {items.map((tag, index) => {
        const selected = value.some((chosen) => chosen.tagId === tag.tagId);
        return <button type="button" role="option" id={`${id}-${index}`} key={tag.tagId} aria-selected={selected} disabled={selected}
          className={index === active ? 'is-active' : ''} onMouseDown={(event) => event.preventDefault()} onMouseEnter={() => setActive(index)} onClick={() => choose(tag)}>{tag.name}{selected ? ' · 已选择' : ''}</button>;
      })}
    </div>}
    <div className="editor-selected-tags">{value.map((tag) => <span key={tag.tagId}>{tag.name}<button type="button" disabled={disabled}
      aria-label={`移除标签 ${tag.name}`} onClick={() => onChange(value.filter((chosen) => chosen.tagId !== tag.tagId))}>×</button></span>)}</div>
    <p className="editor-field-hint">已选 {value.length}/5 · 最多选择 5 个标签</p>
  </div>;
}
