import { act, renderHook } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { useScrollMemory } from './useScrollMemory';

afterEach(() => vi.unstubAllGlobals());

it('内容增长时按锚点校正位置，显式目录跳转后停止自动校正', () => {
  const callbacks: (() => void)[] = [];
  vi.stubGlobal('ResizeObserver', class {
    constructor(callback: () => void) { callbacks.push(callback); }
    observe() {}
    disconnect() {}
  });
  const root = document.createElement('section');
  root.innerHTML = '<article><p data-read-block="0">正文</p></article>';
  let blockTop = 200;
  root.getBoundingClientRect = () => ({ top: 0 }) as DOMRect;
  root.querySelector('p')!.getBoundingClientRect = () => ({ top: blockTop - root.scrollTop, bottom: blockTop - root.scrollTop + 100 }) as DOMRect;
  const ref = { current: root };
  const initial = { top: 200, anchor: '0', offset: 0 };
  const { result } = renderHook(() => useScrollMemory(ref, 'scroll-anchor-test', true, initial));
  expect(root.scrollTop).toBe(200);
  blockTop = 400;
  act(() => callbacks[0]());
  expect(root.scrollTop).toBe(400);
  act(() => result.current());
  root.scrollTop = 600;
  blockTop = 700;
  act(() => callbacks[0]());
  expect(root.scrollTop).toBe(600);
});
