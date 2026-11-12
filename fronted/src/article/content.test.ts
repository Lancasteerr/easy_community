import { expect, it } from 'vitest';
import { prepareContent } from './content';

it('仅收录有效的前三层标题，重复文本拥有稳定且不同的锚点', () => {
  const html = '<h2> 同名 </h2><p>正文</p><h4>深层</h4><h2>同名</h2><h3> </h3><h1>概览</h1>';
  const content = prepareContent(html);
  expect(content.entries).toEqual([
    { id: 'article-section-1', text: '同名', level: 2 },
    { id: 'article-section-3', text: '同名', level: 2 },
    { id: 'article-section-5', text: '概览', level: 1 },
  ]);
  expect(prepareContent(html)).toEqual(content);
  expect(content.html).toContain('data-read-block="1"');
});

it('没有有效标题时目录为空，外链附加新标签与隔离关系', () => {
  const content = prepareContent('<p><a href="https://example.com">链接</a></p><h4>细节</h4>');
  expect(content.entries).toEqual([]);
  expect(content.html).toContain('target="_blank"');
  expect(content.html).toContain('rel="noopener noreferrer nofollow"');
});
