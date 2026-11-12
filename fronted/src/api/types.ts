/** 对齐后端公开 DTO；BIGINT 标识始终保留字符串，避免浏览器精度丢失。 */
export interface UserIdentity {
  userId: string;
  username: string;
  role: 'USER' | 'PATROLLER' | 'ADMIN';
}

export interface UserProfile extends UserIdentity {
  avatarMediaId: string | null;
  avatarUrl: string | null;
}

export interface ArticleCardData {
  articleId: string;
  title: string;
  authorId: string;
  authorName: string;
  /** 信息流直接提供作者头像地址；未设置头像时为空。 */
  authorAvatarUrl: string | null;
  summary: string | null;
  coverUrl: string | null;
  tags: { tagId: string; name: string }[];
  publishedAt: string;
  updatedAt: string;
  viewCount: number;
  likeCount: number;
  likedByMe: boolean;
}

export interface FeedPage {
  items: ArticleCardData[];
  nextCursor: string | null;
}

/** 公开详情直接复用文章展示字段；阅读数由服务端返回，前端不补计本次 PV。 */
export interface ArticleDetailData extends ArticleCardData {
  coverMediaId: string | null;
  contentHtml: string;
}

/** 202 仅表示命令已受理；版本号不能转换为 JavaScript 数值。 */
export interface ArticleLikeAccepted {
  accepted: boolean;
  operationId: string;
  liked: boolean;
  version: string;
}

export interface CsrfToken { token: string; headerName: string }

export interface ProblemDetail {
  status?: number;
  code?: string;
  detail?: string;
}
