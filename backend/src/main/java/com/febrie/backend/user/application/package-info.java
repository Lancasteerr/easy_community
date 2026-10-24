/**
 * 组织创建用户、修改密码、用户删除和实时资料查询，并为认证提供账户查询。
 * 头像复制在业务事务外准备，当前头像字段与文件引用在同一READ_COMMITTED事务维护。
 * 需要一起成功或失败的数据库修改由这里通过事务处理；不接收 HTTP 请求对象。
 */
package com.febrie.backend.user.application;
