## Docker 构建在 Maven 下载依赖时卡住（Windows 国内网络）

旧 Dockerfile 包含 `mvn dependency:go-offline`，首次启动会额外下载大量插件依赖，例如 `javassist`。这种 `WARNING` 本身不代表构建失败。现在镜像构建已改为：

- 去掉单独的 `dependency:go-offline` 步骤，直接执行一次 Maven `package`；
- 默认使用阿里云 Maven 公共镜像，设置在仓库的 `ops/maven-settings.xml`，无需安装宿主机 Maven；
- 启用 BuildKit 的 Maven 本地仓库缓存，保留跨次构建已下载的依赖；
- 固定 Maven Compiler Plugin 版本，消除有效 POM 中的版本缺失警告。

更新到包含这些修改的 `main` 分支后，PowerShell 中执行：

```powershell
git pull
docker compose build --progress=plain app
docker compose up -d
```

首次运行仍然需要下载 Maven/Java 基础镜像和依赖，具体耗时取决于网络；但此后重建会复用缓存。不要为了处理下载慢而执行 `docker compose down -v`，因为它会删除 MySQL 等命名卷。

如果阿里云 Maven 镜像在你当前网络不可用，可以**仅在本次 PowerShell 会话**中切换回 Maven Central，无需修改仓库文件：

```powershell
$env:MAVEN_MIRROR_URL = "https://repo.maven.apache.org/maven2"
docker compose build --progress=plain app
Remove-Item Env:MAVEN_MIRROR_URL
docker compose up -d
```

如果出现 `Could not transfer artifact`、`Connection timed out`、`PKIX path building failed`，属于仓库网络/TLS 问题，检查 Docker Desktop 的代理、DNS、网络连通性。若出现 `BUILD FAILURE`，应查看紧挨着的 `[ERROR]`，不能把依赖警告当作根因。

# ExpensePilot Windows + Docker 本地完整工作区

> 状态：开发分支中的完整集成方案。运行通过与否以 GitHub Actions 和真实机器的测试日志为准，而不是以本文档声称为准。

## 快速启动

1. Windows 10/11 上安装 Docker Desktop，切换为 Linux containers，并确保为 Docker 分配约 8GB 或更多内存。
2. 打开 Windows PowerShell：

```powershell
git clone -b feat/windows-groq-fullstack https://github.com/1564269628/ExpensePilot-Java.git
cd ExpensePilot-Java
docker compose up -d --build
docker compose ps
```

3. 访问 **http://localhost:8080**。服务第一次构建会下载 Java 21、Maven 依赖、MySQL、Redis、RocketMQ 镜像。
4. 如果页面未打开：

```powershell
docker compose logs --tail 200 app
docker compose logs --tail 150 mysql rocketmq-broker
```

5. 退出时：`docker compose down`（保留数据）。清空所有历史数据：`docker compose down -v`（不可恢复）。

所有端口均为内部网络使用，只有 `127.0.0.1:8080` 暴露到 Windows 宿主机。

## 本地版与企业版边界

- **真实 Groq 调用**：Spring AI 使用 OpenAI-compatible `https://api.groq.com/openai/v1` + `qwen/qwen3.8-27b`。Spring 启动时加载器在 `local` profile 解密已提交的密文并注入模型配置；也支持用 `GROQ_API_KEY` 覆盖。
- **真实 Java Graph**：继续使用已有的 Spring AI Alibaba StateGraph、MysqlSaver Checkpoint、PlanValidator、ApprovalService、SideEffectGuard、Transactional Outbox 和 RocketMQ。
- **本地业务数据**：邮箱、网盘、差旅、发票、费用政策、提交、通知这些外部企业系统没有授权，因此本地实现是持久化到 MySQL 的本地工作区，而不是连接到真实 Gmail/Drive/差旅供应商。文档、差旅记录必须由用户录入或加载标记为 DEMO 的测试数据。
- **身份**：本地 profile 自动用 `local-demo-user` 单用户登录（含审批权限），无需外部 JWT IdP；**不可把本地模式部署到公网**。原有生产 profile 仍支持 JWT 和生产 MCP Server。
- **文件**：PDF/JPG/PNG 可上传并持久化到 `uploads` Docker volume，金额/日期/分类是用户确认的元数据。不宣称已完成第三方 OCR/验真。

## 网页操作路径

1. 概览点击「加载明确标注的演示数据」，将插入上海 2026-10-01 ～ 2026-10-03 差旅与两份 DEMO 材料。
2. 点击「创建 Agent 任务」或从侧栏进入 Agent 任务，输入「请处理2026年10月1日至10月3日上海出差的报销」。
3. 后端先真正调用 Groq 生成结构化 Planner 输出，Java PlanValidator 校验 DAG，然后 StateGraph 三路并行查本地工作区数据。
4. 若缺字段，填写城市和日期；若缺材料，先在「费用材料」录入/上传，再勾选材料引用补件；若遇政策冲突，处理 `POLICY_EXCEPTION` 审批。
5. 最终 `SUBMIT_REPORT` 人工确认后，执行幂等本地报销提交。Outbox 将事件投递 RocketMQ，Consumer 在 MySQL 中记录本地通知。可在「报销与通知」查看。
6. 「模型连通检测」按钮独立发起一条真实 Groq API 请求。若 Key 已吊销或供应商不可用会显示具体错误，不会降级为假数据。

## 验证命令

静态和 Java 单元测试（需要本机安装 JDK 21 与 Maven）：

```powershell
mvn -B clean verify
node --check src/main/resources/static/app.js
docker compose config -q
```

真实联调（先启动 Docker，需 Python 3）：

```powershell
python ops/smoke_local.py
```

自动化会检查：MySQL/Redis/应用健康、演示数据、Groq 真实响应、任务创建幂等、Planner、Checkpoint、补件/澄清分支、审批、提交、RocketMQ 通知。测试失败应保留日志并修复，**不能标记为通过**。

## 凭据风险

仓库所有者明确要求在公开配置中同时保存 AES-GCM 密文和密码 `123456`。
**因为两者都公开，任何人都能还原 API Key。此行为等同公开凭据，不属于有效密钥保护。**
建议在真实多人使用前轮换密钥，企业环境中改为容器 Secret/KMS，不要公开转发或启用本地无登录模式。

## 常见问题

- `app` 一直重启：检查 `docker compose logs app`，包括模型权限、数据库初始化、Flyway、Graph Saver 和 MQ 连接。
- 建议至少 8GB Docker RAM；RocketMQ Broker 和 Maven 构建在内存过低时容易 OOM。
- 老版本 Docker 数据库卷可能使用了不同密码，迁移旧数据库或显式清空历史卷再首次启动。
- 若 Groq 401/403，请先检查 Key 是否已撤销；若 429，请等待限流恢复。
