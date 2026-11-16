# 上传协议与 COS 验证

所有命令从仓库根目录执行。`support/upload.mjs` 是协议测试和 COS 验证共用的辅助函数，正式前端使用 `fronted/src/api/media.ts`，不依赖本目录。

```powershell
# 离线测试：只验证动态授权字段、multipart 和统一响应，不连接外部存储。
node --test infra/test/media/support/upload.test.mjs
```

`verify-cos.mjs` 仅在显式配置真实 COS 环境时运行，会登录社区并上传测试文件，不是普通离线回归的一部分。需要 HTTPS、专用账号、可达回调、已开通 Callback/ckey 和真实审核策略：

- `IT_COS_API_URL`、`IT_COS_USERNAME`、`IT_COS_PASSWORD`。
- `IT_COS_SMALL_IMAGE`：不超过 2 MiB 的 PNG。
- `IT_COS_LARGE_IMAGE`：恰好 10 MiB 的 JPEG。

```powershell
# 仅主动进行真实云端验收时执行；凭证通过环境变量提供。
node infra/test/media/verify-cos.mjs
```

脚本不绑定头像，无引用文件交由正常 GC 回收。离线测试、MinIO 联调与真实 COS 验证分别记录结果，不相互替代。后端真实媒体集成测试的独立库、桶和 Webhook 配置见[文件服务指南](../../../docs/public/guides/media/zh-CN/media-service.md#验证与切换)。
