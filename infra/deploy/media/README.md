# COS 部署配置模板

- `cos-public-policy.json`：填写实际桶和对象前缀，仅允许匿名读取 public/，temp/ 保持私有。
- `cos-cors.xml`：填写实际前端来源，允许约定的 POST 上传；不要直接沿用示例域名。

这些文件原位于 backend/examples/media，仅作为配置模板，不自动应用到真实云资源。Callback、ckey、真实审核策略及应用身份权限必须单独配置，完整契约见[文件服务指南](../../../docs/public/guides/media/zh-CN/media-service.md)。真实验证入口见[媒体验证说明](../../test/media/README.md)。
