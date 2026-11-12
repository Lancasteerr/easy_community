import CodeBlockLowlight from '@tiptap/extension-code-block-lowlight';
import { Plugin } from '@tiptap/pm/state';
import { DecorationSet } from '@tiptap/pm/view';
import { createLowlight } from 'lowlight';
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

/** 显式注册目录内的语法，避免引入所有语言；此模块仅随编辑路由加载。 */
const lowlight = createLowlight({ java, javascript, typescript, xml, css, json, sql, bash, python, markdown, yaml, plaintext });
export const codeLanguages = [
  { value: '', label: '自动识别' }, { value: 'java', label: 'Java' }, { value: 'javascript', label: 'JavaScript' },
  { value: 'typescript', label: 'TypeScript' }, { value: 'html', label: 'HTML / XML' }, { value: 'css', label: 'CSS' },
  { value: 'json', label: 'JSON' }, { value: 'sql', label: 'SQL' }, { value: 'bash', label: 'Bash' },
  { value: 'python', label: 'Python' }, { value: 'markdown', label: 'Markdown' }, { value: 'yaml', label: 'YAML' },
  { value: 'plaintext', label: '纯文本' },
] as const;
export const knownCodeLanguage = (language: string) => language === 'xml' || codeLanguages.some((item) => item.value === language);

export const ArticleCodeBlock = CodeBlockLowlight.extend({
  addProseMirrorPlugins() {
    return (this.parent?.() ?? []).map((plugin) => {
      const decorations = plugin.props.decorations;
      if (!decorations) return plugin;
      // 上游会对未知语言自动识别；本系统要求保留旧语言标识并显示纯文本，所以仅过滤其显示装饰。
      return new Plugin({ ...plugin.spec, props: { ...plugin.spec.props, decorations(state) {
        const result = decorations.call(plugin, state);
        if (!(result instanceof DecorationSet)) return result;
        const plainRanges: { from: number; to: number }[] = [];
        state.doc.descendants((node, position) => {
          if (node.type.name === 'codeBlock' && node.attrs.language && !knownCodeLanguage(node.attrs.language)) {
            plainRanges.push({ from: position + 1, to: position + node.nodeSize - 1 });
          }
        });
        return plainRanges.length ? result.remove(result.find().filter((item) => plainRanges.some((range) => item.from >= range.from && item.to <= range.to))) : result;
      } } });
    });
  },
}).configure({ lowlight, defaultLanguage: null, enableTabIndentation: true, tabSize: 2 });
