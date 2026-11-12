import hljs from 'highlight.js/lib/core';
import java from 'highlight.js/lib/languages/java';
import javascript from 'highlight.js/lib/languages/javascript';
import typescript from 'highlight.js/lib/languages/typescript';
import xml from 'highlight.js/lib/languages/xml';
import css from 'highlight.js/lib/languages/css';
import json from 'highlight.js/lib/languages/json';
import sql from 'highlight.js/lib/languages/sql';
import bash from 'highlight.js/lib/languages/bash';
import python from 'highlight.js/lib/languages/python';
import markdown from 'highlight.js/lib/languages/markdown';
import yaml from 'highlight.js/lib/languages/yaml';
import plaintext from 'highlight.js/lib/languages/plaintext';

// 阅读高亮独立于 Tiptap，只有正文含代码块时才下载本模块。
for (const [name, language] of Object.entries({ java, javascript, typescript, xml, css, json, sql, bash, python, markdown, yaml, plaintext })) {
  hljs.registerLanguage(name, language);
}

/** 未标注及未知语言保持纯文本；只对已注册语言执行显式高亮。 */
export function highlightCode(root: HTMLElement) {
  root.querySelectorAll<HTMLElement>('pre code').forEach((code) => {
    const language = /(?:^|\s)language-([\w+-]+)/.exec(code.className)?.[1];
    if (language && hljs.getLanguage(language)) {
      code.innerHTML = hljs.highlight(code.textContent ?? '', { language, ignoreIllegals: true }).value;
      code.classList.add('hljs');
    }
  });
}
