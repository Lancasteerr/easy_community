/**
 * 读写用户及本地密码身份表。
 * 登录使用一次 JOIN 取得完整快照，改密、删号和角色修改在同一事务内递增安全版本。
 */
package com.febrie.backend.user.infrastructure.persistence;
