/** 用户标识与后端正 BIGINT 范围一致，禁止通过数值转换丢失精度。 */
export function validUserId(value: string | undefined): value is string {
  return !!value && /^[1-9]\d{0,18}$/.test(value)
    && (value.length < 19 || value <= '9223372036854775807');
}

/** 内部作者和会话提供的标识已经由服务端确认，路由入口仍会独立校验。 */
export const profilePath = (userId: string) => `/users/${encodeURIComponent(userId)}`;
