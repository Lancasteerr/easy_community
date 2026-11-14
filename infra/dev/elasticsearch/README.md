# 独立 Elasticsearch 开发服务

固定版本 `9.5.6`，单节点，独立 Docker Compose 项目、网络与数据卷。此处仅部署 ES，后端尚未接入。后续选择 Java 客户端时需确认与服务端版本兼容。

- 地址：`http://127.0.0.1:9200`
- 用户名：`elastic`
- 密码：本目录 `.env` 文件中的 `ELASTIC_PASSWORD`（已由 Git 忽略）
- 容器名：`easy-community-es`
- 内存上限：2 GiB，JVM 堆：1 GiB
- 数据卷：`easy-community-es_es-data`

在本目录执行：

```powershell
# 首次安装时复制示例并填写密码；已有 .env 时无需再复制。
Copy-Item .env.example .env
# 拉取固定版本并启动，等待健康检查通过。
docker compose up -d --wait --wait-timeout 180
# 查看运行状态或日志。
docker compose ps
docker compose logs --tail 100 elasticsearch
# 停止或重新启动，保留数据。
docker compose stop
docker compose start
```

Windows Docker Desktop 的 Linux 内核需满足 `vm.max_map_count >= 1048576`；本次安装前已确认满足要求。容器随 Docker 启动自动恢复（手动停止后除外）。

默认仅通过本机回环地址开放 HTTP，并启用密码认证，适用于本地开发。远程部署应另行配置 TLS、网络访问控制与应用专用账号。不要执行 `docker compose down -v`，该命令会删除索引数据卷。

单节点业务索引如配置副本，集群可能显示 `yellow`；本地索引可按需要设置 `number_of_replicas: 0`。本配置不创建业务索引，不安装额外分词插件。

官方参考：[Docker 单节点部署](https://www.elastic.co/docs/deploy-manage/deploy/self-managed/install-elasticsearch-docker-basic)。
