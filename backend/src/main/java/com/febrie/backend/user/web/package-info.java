/**
 * 接收用户和管理员的 HTTP 请求，将已认证身份转换成操作人后调用用户服务。改密成功后通过认证模块的会话适配器退出当前登录，不直接操作数据库。
 */
package com.febrie.backend.user.web;
