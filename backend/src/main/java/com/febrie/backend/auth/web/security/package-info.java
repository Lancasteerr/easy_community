/**
 * 在请求进入接口前执行限流和登录状态校验。
 * 这里读取容器处理后的客户端 IP，将校验工作交给应用服务，不自行解析代理请求头。
 */
package com.febrie.backend.auth.web.security;
