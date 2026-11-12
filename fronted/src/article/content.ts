export interface TocEntry { id: string; text: string; level: number }
export interface PreparedContent { html: string; entries: TocEntry[] }

/** 输入仅限后端白名单渲染的 HTML；此处只附加阅读语义，不接受用户粘贴内容。 */
export function prepareContent(html: string): PreparedContent {
  const document = new DOMParser().parseFromString(html, 'text/html');
  const entries: TocEntry[] = [];
  document.querySelectorAll<HTMLElement>('h1,h2,h3,h4,h5,h6').forEach((heading, index) => {
    const level = Number(heading.tagName.slice(1));
    const text = heading.textContent?.replace(/\s+/g, ' ').trim();
    heading.id = `article-section-${index + 1}`;
    if (text && level <= 3) entries.push({ id: heading.id, text, level });
  });
  Array.from(document.body.children).forEach((block, index) => block.setAttribute('data-read-block', String(index)));
  document.querySelectorAll('a').forEach((link) => {
    link.target = '_blank'; link.rel = 'noopener noreferrer nofollow';
  });
  return { html: document.body.innerHTML, entries };
}
