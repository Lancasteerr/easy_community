/**
 * 组织创建用户、修改密码和删除用户的流程，并为认证提供账户查询。
 * 需要一起成功或失败的数据库修改由这里通过事务处理；不接收 HTTP 请求对象。
 */
package com.febrie.backend.user.application;
