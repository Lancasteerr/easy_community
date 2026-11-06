# easy_community

基于 [easy_blog](https://github.com/Lancasteerr/Easy_blog_full)，适配多用户，多角色改进

本地开发使用 IDEA / Vite 运行应用，MySQL、Redis、MinIO、RabbitMQ 使用独立配置的 Docker 容器，后端通过 `backend/.env.dev` 连接宿主机发布的端口。

功能：

1. 文章crud，tag有限支持（文章元数据），浏览量 点赞量（可复用easy_blog相关逻辑），评论
2. 缓存（复用easy_blog相关逻辑）
3. 权限模型及登录方法改进（已实现）
4. 文件系统改进（已实现）
5. 全文搜索
6. 前端
