import type { Editor, JSONContent } from '@tiptap/react';
import { NodeSelection, TextSelection } from '@tiptap/pm/state';
import { closeHistory } from '@tiptap/pm/history';

/** 只统计文本码点，空格和标点计数，图片及各种换行不计；不增加保存限制。 */
export function countBodyCharacters(node: JSONContent): number {
  const own = node.type === 'text' ? Array.from((node.text ?? '').replace(/[\r\n\u2028\u2029]/g, '')).length : 0;
  return own + (node.content ?? []).reduce((total, child) => total + countBodyCharacters(child), 0);
}

/** 清除格式是独立的一步历史；空选区扩展到当前文本块，图片节点不会被当作文字清理。 */
export function clearEditorFormatting(editor: Editor) {
  const selection = editor.state.selection;
  if (selection instanceof NodeSelection && !selection.node.isTextblock) return false;
  const original = selection.getBookmark();
  const { $from } = selection;
  const chain = editor.chain().command(({ tr }) => {
    closeHistory(tr);
    if (selection.empty && $from.parent.isTextblock) tr.setSelection(TextSelection.create(tr.doc, $from.start(), $from.end()));
    return true;
  });
  return chain.unsetAllMarks().clearNodes().command(({ tr }) => {
    tr.setSelection(original.map(tr.mapping).resolve(tr.doc)); tr.setStoredMarks(null); return true;
  }).run();
}
