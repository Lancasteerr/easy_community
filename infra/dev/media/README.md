# MinIO 配置与历史上传联调环境

日常开发使用独立配置的 MinIO，以及 [Vite 上传代理](../../../fronted/dev/mediaUploadProxy.ts)。代理与后端配置步骤见[前端说明](../../../fronted/README.md#开发上传与真实联调)；本目录不会自动创建桶、应用策略或启动服务。

- `minio-public-policy.json`：填写桶名和前缀，仅允许匿名读取 public/。
- `minio-notification.json`：填写临时前缀，精确订阅 POST 创建事件，配合后端 Webhook。
- 临时对象一天过期的生命周期需单独配置；不公开 temp/。

## 历史 Caddy 独立联调

`compose.dev.yaml` 与 `Caddyfile.dev` 保留为独立协议联调方案，原位于 backend/examples/media。固定 MinIO 与 Caddy 版本、独立项目和数据卷保持不变；它不是当前日常开发启动步骤。

从仓库根目录显式执行：

```powershell
# 环境文件按 Compose 语法读取；先确认与 Java Properties 特殊字符的差异。
docker compose --env-file backend/.env.dev -f infra/dev/media/compose.dev.yaml up -d
```

先确认 19000/19001/19002 端口可用，并将此联调后端的上传入口单独设为 `http://127.0.0.1:19002`。MinIO 在 19000，控制台在 19001；通知地址默认指向宿主机 8080，后端端口变化时同步调整。Compose 仍相对挂载同目录的 `Caddyfile.dev`，不复用或清理已有开发存储。完整约束见[文件服务指南](../../../docs/public/guides/media/zh-CN/media-service.md)。
