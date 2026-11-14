import { useRef, useState, type FormEvent } from 'react';
import { Icon } from '../components/Icon';
import { useToast } from '../components/Toast';
import { searchKeywordError } from './routes';

/** 草稿由表单独立持有；页面在历史项变化时重建表单，同步已提交的关键词。 */
export function SearchForm({ keyword = '', variant = 'page', onSearch }: {
  keyword?: string; variant?: 'header' | 'page'; onSearch: (q: string) => void;
}) {
  const [draft, setDraft] = useState(keyword);
  const input = useRef<HTMLInputElement>(null);
  const composing = useRef(false);
  const notify = useToast();
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (composing.current) return;
    const error = searchKeywordError(draft);
    if (error) { notify(error); input.current?.focus(); return; }
    const q = draft.trim();
    setDraft(q); onSearch(q);
  };
  return <form className={variant === 'page' ? 'search-page-form' : 'search-form'} role="search" onSubmit={submit}>
    {variant === 'page' && <Icon name="search" />}
    <input ref={input} type="search" aria-label="搜索内容" placeholder={variant === 'page' ? '搜索感兴趣的文章' : '搜索你感兴趣的内容'}
      value={draft} onChange={(event) => setDraft(event.target.value)}
      onCompositionStart={() => { composing.current = true; }} onCompositionEnd={() => { composing.current = false; }}
      onKeyDown={(event) => {
        // 部分输入法在确认候选时先发 compositionend，229 仍表示本次按键属于组词。
        if (event.key === 'Enter' && (composing.current || event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229)) event.preventDefault();
      }} />
    {variant === 'page' && draft && <button type="button" className="search-clear" aria-label="清空关键词" onClick={() => {
      setDraft(''); input.current?.focus();
    }}><Icon name="searchCancel" size={24} /></button>}
    <button type="submit" className={variant === 'page' ? 'search-submit' : undefined} aria-label="搜索">
      {variant === 'page' ? '搜索' : <Icon name="search" size={16} />}
    </button>
  </form>;
}
