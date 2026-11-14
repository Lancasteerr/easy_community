import { expect, it, vi } from 'vitest';
import { clearRecovery, recoveryKey } from './recovery';

it('只清除本应用旧编辑暂存，不影响其他存储', () => {
  sessionStorage.setItem(recoveryKey, '旧账号内容');
  sessionStorage.setItem('other-app', '保留');
  clearRecovery();
  expect(sessionStorage.getItem(recoveryKey)).toBeNull();
  expect(sessionStorage.getItem('other-app')).toBe('保留');
  sessionStorage.removeItem('other-app');
});
it('存储不可用也不阻止身份重置', () => {
  const remove = vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => { throw new Error('disabled'); });
  expect(clearRecovery).not.toThrow();
  remove.mockRestore();
});
