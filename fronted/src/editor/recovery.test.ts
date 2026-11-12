import { beforeEach, expect, it, vi } from 'vitest';
import { clearRecovery, recoveryKey, saveRecovery, takeRecovery } from './recovery';
import { emptyForm } from './model';

beforeEach(() => { sessionStorage.clear(); vi.restoreAllMocks(); });
it('同账号同文章在两小时内恢复一次，并保存原基线', () => {
  const original = emptyForm(), form = { ...original, title: '未提交标题' };
  saveRecovery('7', '12', form, original);
  expect(takeRecovery('7', '99')).toBeNull();
  expect(takeRecovery('7', '12')).toMatchObject({ form, baseline: original });
  expect(sessionStorage.getItem(recoveryKey)).toBeNull(); expect(takeRecovery('7', '12')).toBeNull();
});
it('异账号、过期和损坏快照不恢复且清理', () => {
  saveRecovery('7', undefined, emptyForm(), emptyForm()); expect(takeRecovery('8', undefined)).toBeNull();
  saveRecovery('7', undefined, emptyForm(), emptyForm());
  const data = JSON.parse(sessionStorage.getItem(recoveryKey)!); data.savedAt -= 2 * 60 * 60 * 1000 + 1;
  sessionStorage.setItem(recoveryKey, JSON.stringify(data)); expect(takeRecovery('7', undefined)).toBeNull();
  sessionStorage.setItem(recoveryKey, '{bad'); expect(takeRecovery('7', undefined)).toBeNull(); expect(sessionStorage.getItem(recoveryKey)).toBeNull();
});
it('保存失败向调用者抛出，清理失败不会阻止编辑', () => {
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('quota', 'QuotaExceededError'); });
  expect(() => saveRecovery('7', undefined, emptyForm(), emptyForm())).toThrow();
  vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => { throw new Error('disabled'); }); expect(clearRecovery).not.toThrow();
});
