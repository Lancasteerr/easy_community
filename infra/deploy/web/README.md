# Web 部署模板

拓扑为浏览器 HTTPS → Caddy → Nginx → 后端。只公开 Caddy，Nginx 和后端位于专用容器网络。

- `Caddyfile`：设置实际 `PUBLIC_HOST`；向 Nginx 重建可信代理信息。
- `nginx.conf.template`：放入官方 Nginx 容器的 `/etc/nginx/templates/default.conf.template`，设置真实 `CADDY_TRUSTED_CIDR`，由容器 envsubst 替换。
- 将 `fronted/dist/` 部署到 Nginx `/usr/share/nginx/html`；SPA 路由回退到 index.html，`/api/` 保留路径前缀。

模板中的 nginx、backend 服务名必须与实际部署一致。此目录不提供完整生产 Compose，也不自动填写可信网段或启动服务。转发头、安全边界和部署前提见[后端说明](../../../backend/README.md#生产代理与容器配置)。
