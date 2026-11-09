# easy_community

基于 [easy_blog](https://github.com/Lancasteerr/Easy_blog_full)，适配多用户，多角色改进

本地开发使用 IDEA / Vite 运行应用，MySQL、Redis、MinIO、RabbitMQ 使用独立配置的 Docker 容器。填写 `backend/.env.dev` 后复制为 `backend/.env`，后端根据其中的 `SPRING_PROFILES_ACTIVE=dev` 加载开发配置并连接宿主机发布的端口；生产改用已填写的 `.env.prod` 模板。

文档入口：[文档中心](docs/README.md) · [公开文档](docs/public/README.md)。

前端启动、页面范围与联调记录见 [前端说明](fronted/README.md)。

功能：

1. 文章crud，tag有限支持（文章元数据），浏览量 点赞量（可复用easy_blog相关逻辑），评论
2. 缓存（复用easy_blog相关逻辑）
3. 权限模型及登录方法改进（已实现）
4. 文件系统改进（已实现）
5. 全文搜索
6. 前端
