/** 整页导航集中于此，身份边界与资料保存复用；测试替换此边界即可。 */
export const documentNavigation = {
  reload: () => window.location.reload(),
  replace: (url: string) => window.location.replace(url),
};
