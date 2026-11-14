/** 每个文档只拥有一个生命周期；身份边界结束后，任何旧回调都不得继续回填。 */
export const pageLifetime = {
  controller: new AbortController(),
  onUnauthorized: null as (() => void) | null,
};

export function isPageActive() { return !pageLifetime.controller.signal.aborted; }

/** 同时校验文档代次与取消状态，测试或重新挂载也不能接纳旧代次响应。 */
export function capturePage() {
  const signal = pageLifetime.controller.signal;
  return () => signal === pageLifetime.controller.signal && !signal.aborted;
}

export function endPage() {
  if (!isPageActive()) return false;
  pageLifetime.controller.abort();
  return true;
}

/** 登录已成功但仍需获取新CSRF时，先隔离登录前的全部请求和回调。 */
export function renewPage() {
  pageLifetime.controller.abort();
  pageLifetime.controller = new AbortController();
}
