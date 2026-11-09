/**
 * 按配置在应用启动时初始化首个管理员，也支持非 Web 命令。
 * 已有管理员时幂等跳过；仅首次创建读取初始凭证，不提供公网初始化接口。
 */
package com.febrie.backend.user.infrastructure.bootstrap;
