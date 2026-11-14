/** 兼容清除旧版本留下的编辑暂存，不再创建或恢复内容。 */
export const recoveryKey = 'ec:article-editor:recovery:v1';
export function clearRecovery() {
  try { sessionStorage.removeItem(recoveryKey); } catch { /* 禁用存储时仍允许清理页面并离开。 */ }
}
