/** 端点沿用本项目主稿；先移走封面，再在资料区内完成头像及文字的连续收拢。 */
export const profileLayout = { expandedHeight: 376, collapsedHeight: 88, coverHeight: 211, lowerGap: 17 } as const;

const clamp = (value: number) => Math.min(1, Math.max(0, value));
// 原稿定位与收拢目标对应profile.css，集中列出锚点，避免位移量失去设计含义。
const expanded = { avatarTop: 163, nameLeft: 152, nameTop: 222, statsTop: 314, actionTop: 237 };
const collapsed = { avatarTop: 20, nameLeft: 96, nameTop: 18, statsTop: 44, actionHeight: 40 };

/** 调用前提：scrollTop来自当前页面滚动口；负值只表示浏览器的回弹。 */
export function profileCollapseAt(scrollTop: number) {
  const maxDistance = profileLayout.expandedHeight - profileLayout.collapsedHeight;
  const distance = Math.min(Math.max(0, scrollTop), maxDistance);
  const height = profileLayout.expandedHeight - distance;
  // 资料区剩余176px时开始渐缩，在88px端点收为48px头像，不按时间播放动画。
  const morphStart = 200;
  const progress = clamp((distance - morphStart) / (maxDistance - morphStart));
  const textTravel = Math.min(distance, morphStart);
  return {
    height,
    coverY: -Math.min(distance, profileLayout.coverHeight),
    avatarY: -Math.min(distance, expanded.avatarTop - collapsed.avatarTop),
    avatarScale: 1 - progress * .5,
    nameX: progress * (collapsed.nameLeft - expanded.nameLeft),
    nameY: -textTravel + progress * (collapsed.nameTop - expanded.nameTop + textTravel),
    nameScale: 1 - progress * .25,
    bioY: -textTravel,
    bioOpacity: 1 - clamp((distance - 112) / 64),
    statsY: -textTravel + progress * (collapsed.statsTop - expanded.statsTop + textTravel),
    statsScale: 1 - progress * .125,
    actionY: Math.max((height - collapsed.actionHeight) / 2, expanded.actionTop - distance) - expanded.actionTop,
    collapsed: height === profileLayout.collapsedHeight,
  };
}
