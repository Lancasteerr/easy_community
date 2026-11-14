/** 会话仅保存身份；BIGINT标识始终使用字符串。 */
export interface SessionIdentity {
  userId: string;
  role: 'USER' | 'PATROLLER' | 'ADMIN';
}

/** 登录协议保留用户名，但身份状态只提取userId与role。 */
export interface LoginResponse extends SessionIdentity { username: string }

/** 公开资料仅用于页面展示，不包含权限或认证字段。 */
export interface PublicUserProfile {
  userId: string;
  username: string;
  avatarMediaId: string | null;
  avatarUrl: string | null;
  bio: string;
  createdAt: string;
}

/** 兼容现有本人资料写入响应，不将角色写入页面资料状态。 */
export interface ProfileWriteResponse extends PublicUserProfile { role: SessionIdentity['role'] }

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
