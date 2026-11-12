import { Extension } from '@tiptap/react';
import { Plugin, PluginKey } from '@tiptap/pm/state';
import { Decoration, DecorationSet } from '@tiptap/pm/view';

/** 上传锚点随编辑事务映射，不将临时节点写进正文，也不因用户继续输入而插错位置。 */
export const uploadAnchorKey = new PluginKey<DecorationSet>('article-upload-anchors');
export const UploadAnchors = Extension.create({
  name: 'articleUploadAnchors',
  addProseMirrorPlugins() {
    return [new Plugin<DecorationSet>({ key: uploadAnchorKey,
      state: {
        init: () => DecorationSet.empty,
        apply(transaction, previous) {
          let next = previous.map(transaction.mapping, transaction.doc);
          const action = transaction.getMeta(uploadAnchorKey) as { add?: { id: string; position: number }; remove?: string } | undefined;
          if (action?.add) {
            const marker = document.createElement('span'); marker.className = 'editor-upload-anchor';
            next = next.add(transaction.doc, [Decoration.widget(action.add.position, marker, { id: action.add.id, side: -1 })]);
          }
          if (action?.remove) next = next.remove(next.find(undefined, undefined, (spec) => spec.id === action.remove));
          return next;
        },
      }, props: { decorations: (state) => uploadAnchorKey.getState(state) },
    })];
  },
});
