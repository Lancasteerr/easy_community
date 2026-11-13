import { act, renderHook } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { useArticleToc } from './useArticleToc';

afterEach(() => vi.unstubAllGlobals());

it('章节定位只读取正文，跳转扣除遮挡高度且不改变横向位置', () => {
  vi.stubGlobal('matchMedia', () => ({ matches: false }));
  const root = document.createElement('div');
  root.innerHTML = '<h2 id="outside">正文外标题</h2><article><h2 id="first">一</h2><h2 id="second">二</h2></article>';
  const content = root.querySelector('article')!;
  root.getBoundingClientRect = () => ({ top: 0 }) as DOMRect;
  root.scrollTop = 100;
  Object.defineProperties(root, { clientHeight: { value: 600 }, scrollHeight: { value: 2000 } });
  content.children[0].getBoundingClientRect = () => ({ top: 100 }) as DOMRect;
  content.children[1].getBoundingClientRect = () => ({ top: 400 }) as DOMRect;
  root.scrollTo = vi.fn();
  const entries = [{ id: 'first', text: '一', level: 2 }, { id: 'second', text: '二', level: 2 }];
  const { result, unmount } = renderHook(() => useArticleToc({ current: root }, { current: content }, entries, 167));
  expect(result.current.active).toBe('first');
  act(() => result.current.jump('second'));
  expect(root.scrollTo).toHaveBeenCalledWith({ top: 309, behavior: 'smooth' });
  act(() => result.current.jump('outside'));
  expect(root.scrollTo).toHaveBeenCalledTimes(1);
  unmount();
});

it('到底时高亮最后章节，无标题时不生成当前项', () => {
  const root = document.createElement('div');
  root.scrollTop = 1000;
  Object.defineProperties(root, { clientHeight: { value: 600 }, scrollHeight: { value: 1600 } });
  const entries = [{ id: 'first', text: '一', level: 2 }, { id: 'last', text: '末', level: 2 }];
  const ref = { current: root };
  const { result, unmount } = renderHook(() => useArticleToc(ref, ref, entries, 167));
  expect(result.current.active).toBe('last');
  unmount();
  const empty = renderHook(() => useArticleToc(ref, ref, [], 167));
  expect(empty.result.current.active).toBeUndefined();
  empty.unmount();
});
