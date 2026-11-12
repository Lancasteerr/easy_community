import Image from '@tiptap/extension-image';
import { mediaIdPattern } from './model';

/** 只接受本站可绑定的图片身份；任意外链或剪贴板 data URL 不变成文章图片。 */
export const ArticleImage = Image.extend({
  addAttributes() {
    return { ...this.parent?.(), fileId: { default: null, parseHTML: (element) => element.getAttribute('data-file-id'),
      renderHTML: (attributes) => ({ 'data-file-id': attributes.fileId }) } };
  },
  parseHTML() { return [{ tag: 'img[data-file-id]', getAttrs: (element) => mediaIdPattern.test(element.getAttribute('data-file-id') ?? '') ? null : false }]; },
}).configure({ inline: false, allowBase64: false });
