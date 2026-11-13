import { act, renderHook } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { capturePosition, useScrollMemory } from './useScrollMemory';

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
  const initial = { top: 200, left: 80, anchor: '0', offset: 0 };
  const { result } = renderHook(() => useScrollMemory(ref, 'scroll-anchor-test', true, initial));
  expect(root.scrollTop).toBe(200);
  expect(root.scrollLeft).toBe(80);
  blockTop = 400;
  act(() => callbacks[0]());
  expect(root.scrollTop).toBe(400);
  act(() => result.current());
  root.scrollTop = 600;
  root.scrollLeft = 120;
  blockTop = 700;
  act(() => callbacks[0]());
  expect(root.scrollTop).toBe(600);
  expect(root.scrollLeft).toBe(120);
});

it('只采集正文可见块，恢复时扣除顶部遮挡并保留横向位置', () => {
  const root = document.createElement('div');
  root.innerHTML = '<p data-read-block="0">正文外同名块</p><article><p data-read-block="0">已遮挡</p><p data-read-block="1">可见正文</p></article>';
  const content = root.querySelector('article')!;
  root.scrollTop = 500;
  root.scrollLeft = 90;
  root.getBoundingClientRect = () => ({ top: 0 }) as DOMRect;
  content.children[0].getBoundingClientRect = () => ({ top: 100, bottom: 150 }) as DOMRect;
  content.children[1].getBoundingClientRect = () => ({ top: 650 - root.scrollTop, bottom: 750 - root.scrollTop }) as DOMRect;
  const geometry = { contentRef: { current: content }, topInset: 167 };
  const position = capturePosition(root, geometry);
  expect(position).toEqual({ top: 500, left: 90, anchor: '1', offset: -17 });
  root.scrollTop = 0;
  root.scrollLeft = 0;
  const { unmount } = renderHook(() => useScrollMemory({ current: root }, 'visible-content', true, position, undefined, 0, geometry));
  expect(root.scrollTop).toBe(500);
  expect(root.scrollLeft).toBe(90);
  unmount();
});

it('找不到锚点时回退数值位置，键盘与滚轮操作均终止布局校正', () => {
  const callbacks: (() => void)[] = [];
  vi.stubGlobal('ResizeObserver', class {
    constructor(callback: () => void) { callbacks.push(callback); }
    observe() {}
    disconnect() {}
  });
  for (const event of [new Event('wheel'), new KeyboardEvent('keydown', { key: 'PageDown' })]) {
    const root = document.createElement('div');
    const { unmount } = renderHook(() => useScrollMemory({ current: root }, `missing-${event.type}`, true,
      { top: 300, anchor: 'missing', offset: 0 }));
    expect(root.scrollTop).toBe(300);
    root.dispatchEvent(event);
    root.scrollTop = 600;
    act(() => callbacks.at(-1)!());
    expect(root.scrollTop).toBe(600);
    unmount();
  }
});
