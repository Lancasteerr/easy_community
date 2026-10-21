/**
 * 在 Redis 集合中登记、查询和移除有效会话，并分批清理过期成员。
 * 只有登录可以登记成员，普通请求不能让旧会话恢复有效。
 */
package com.febrie.backend.auth.infrastructure.session;
