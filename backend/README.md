# 校园互助跑腿后端

当前版本 0.9.0-SNAPSHOT，已实现身份、任务主流程、交付结算、管理员任务管理和 WebSocket 在线提醒。接口与联调说明见[管理员任务管理](../后端设计计划.md#20-第六部分-管理员任务管理)和[在线提醒](../后端设计计划.md#21-第六部分-在线状态提醒与断线恢复)，真实小程序联调等待 AppID、AppSecret 和有效临时 code。

## 1 当前实现

| 能力 | 说明 |
| --- | --- |
| 健康检查 | 查询数据库后返回应用与数据库状态 |
| 管理员初始化 | 显式开启时创建账号，数据库保存 BCrypt 哈希；已有账号不覆盖密码 |
| 登录 | 检查账号、密码与启用状态，签发随机 Bearer 凭据 |
| 身份查询 | 校验凭据及当前账号状态，返回管理员编号、账号与名称 |
| 学生微信登录后端 | 接收 code，由微信身份查找或创建学生并签发独立凭据；真实微信联调待配置 |
| 当前学生查询 | 学生凭据校验和公开资料查询；隔离测试已验证 |
| 任务基础接口 | 学生发布、分页大厅、详情与私密交接信息隔离；H2和独立MySQL测试通过 |
| 任务管理 | 本人发布列表、状态筛选、取消及重复取消反馈；到期待接任务后台分批持久化 |
| 抢单与重试 | 确认窗口、发布者取消优先、唯一接单归属、原编号重试、本人接单列表与双方详情权限 |
| 取件与取消 | 接单者确认取件，双方协商取消，取件后由管理员审核并保留处理记录 |
| 交付与模拟结算 | 交付码签发、刷新与停用；发布者扫码核验后确认；结算明细与收益汇总 |
| 管理员任务管理 | 任务编号与状态查询、详情时间线、直接关闭任务并结束待处理取消申请 |
| 在线提醒 | 原生 WebSocket、学生身份认证、多连接、心跳和事务提交后异步发送 |
| 失败反馈 | 区分请求格式错误、登录失败、未认证与服务异常，不返回密码哈希 |

技术版本：Spring Boot 3.5.16、MyBatis Starter 3.0.5、MySQL Connector/J、Spring Security Crypto。源码以 Java 17 为编译目标，本机使用 Java 25.0.3 验证。

版本组合参考 [Spring Boot 系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)与 [MyBatis Starter 兼容表](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/)。

## 2 先理解文件与调用关系

```text
src/main/java/cn/campus/errand/
  CampusErrandApplication.java  启动入口
  admin/
    AdminController.java       接收登录和身份查询请求
    AuthContracts.java         请求与响应对象，不包含密码哈希响应
    AdminAuthService.java      登录检查、身份识别
    AdminMapper.java           MyBatis 参数化数据库操作
    AdminAccount.java          后端内部的账号数据
    AdminTokenStore.java       单体内存登录凭据与过期判断
    AdminBootstrap.java        首次账号初始化
  student/                     微信身份交换、学生资料与独立学生凭据
  task/
    TaskController.java        发布、大厅、详情、本人列表、取消与接单
    TaskService.java           校验、任务保存、取消事务与私密字段过滤
    TaskMapper.java            任务数据库操作
    TaskContracts.java         公开摘要、受控详情与发布表单
    TaskExpirationScheduler.java  启动补偿与定时分批过期
    TaskClaimService.java      登记申请、确认接单、保存与重放结果
    TaskClaimScheduler.java    结清待确认申请与重启补偿
  config/WebConfig.java        管理员/学生接口身份拦截与密码编码器
  common/                      统一响应、异常反馈与健康检查
src/main/resources/
  application.yml              公共配置，数据库参数可由环境变量覆盖
  schema.sql                   幂等创建管理员表，不删除已有表或数据
  schema-mysql.sql             幂等创建学生、任务、接单申请与归属表
src/test/                      身份、任务和微信适配的隔离测试
application-local.properties   本机私有配置，已被 .gitignore 排除
```

登录请求：Controller → Service → Mapper 查询账号 → BCrypt 校验 → TokenStore 签发凭据 → 返回 LoginResult。

身份查询：WebConfig 拦截 → Service 校验凭据并查询账号状态 → 将管理员身份放入请求属性 → Controller 返回公开资料。

## 3 本机已经完成的准备

- 2026-11-12 新建 MySQL 数据库 campus_errand，采用 utf8mb4。
- 按本轮约定使用 root 连接，连接密码保存在本机 application-local.properties。
- 已创建管理员，账号为 admin。随机生成的初始密码保存在该文件的 app.admin.bootstrap.password 中，在本机查看即可。
- 初始化完成后已将 app.admin.bootstrap.enabled 关闭。再次启动不会重置账号密码。
- 联调服务已停止，需按下一节启动。

上述私有配置只属于当前机器，不应作为共享配置提交。更换机器时需自行创建数据库并提供连接参数；仓库不包含可通用的默认管理员密码。

## 4 启动与测试

编译和测试可先使用 IDEA：打开 Maven 工具窗口，确认该项目的 Maven Runner 使用 JDK25，执行 Lifecycle → verify。也可直接运行 CampusErrandApplication，运行配置的工作目录设为 backend，以读取本机配置。

当前可执行 JAR 为 0.9.0-SNAPSHOT，在 PowerShell 中可直接启动。

```powershell
Set-Location 'D:\计网项目和实验\backend'
& 'C:\Program Files\Java\jdk-25.0.3\bin\java.exe' -jar .\target\campus-errand-backend-0.9.0-SNAPSHOT.jar
```

上述启动命令明确指定本机 Java 路径，无须修改 Windows 或 IDEA 终端的 Path、JAVA_HOME。队友需替换为自己机器上的 JDK 路径。

默认地址为 http://localhost:8080。若端口被占用：

```powershell
& 'C:\Program Files\Java\jdk-25.0.3\bin\java.exe' -jar .\target\campus-errand-backend-0.9.0-SNAPSHOT.jar --server.port=8081
```

在 IDEA 中可运行单个测试类，或通过 Maven verify 运行全量测试。若要使用终端 Maven，先执行 .\mvnw.cmd -version，确认显示 Java 17 或更高版本，再运行：

```powershell
.\mvnw.cmd test
```

自动化测试使用隔离的 H2 内存库，以 MySQL 模式执行当前 schema 和真实 MyBatis 操作，不连接本机 MySQL。H2 结果与真实 MySQL 联调结果分别记录，不等同于未来并发抢单实验。

需要重新生成 JAR 时同样可在 IDEA 执行 Maven package；终端 Java 版本满足要求时也可执行 .\mvnw.cmd package。首次使用 Wrapper 可能下载 Maven 和依赖。

需要环境变量覆盖连接时，可设置 DB_URL、DB_USERNAME、DB_PASSWORD。若本机配置文件同时设置相同 Spring 配置项，应同步修改或移除该项，避免混用两套配置。

首次在新环境初始化账号：在本机配置 app.admin.bootstrap.enabled=true，提供 username、password、display-name；密码要求 8—64 字符且 UTF-8 不超过 72 字节。初始化完成后关闭开关。该机制只创建不存在的账号，不提供密码重置。

## 5 三个接口

### 5.1 健康检查

GET /api/health，无需登录。数据库可查询时返回：

```json
{"code":"OK","message":"成功","data":{"application":"UP","database":"UP"}}
```

### 5.2 管理员登录

POST /api/admin/auth/login，Content-Type: application/json。

```json
{"username":"admin","password":"填写本机初始管理员密码"}
```

HTTP 200 返回 data.accessToken、data.tokenType、data.expiresAt 和 data.admin。admin 仅包含 id、username、displayName。登录时不接受客户端指定管理员编号或角色。

### 5.3 查询当前管理员

GET /api/admin/me，携带请求头：

```text
Authorization: Bearer <登录返回的 accessToken>
```

HTTP 200 返回当前管理员公开资料。

| HTTP 状态 | code | 含义 |
| --- | --- | --- |
| 400 | INVALID_REQUEST | JSON、必填项或输入长度不符合要求 |
| 401 | LOGIN_FAILED | 登录账号或密码错误，或账号不可用 |
| 401 | UNAUTHORIZED | 无有效凭据、已过期，或对应账号当前不可用 |
| 500 | INTERNAL_ERROR | 服务异常，详细错误保留在服务端日志 |

## 6 本轮设计取舍

- 管理员独立保存在 admin_account，唯一账号；当前字段为 id、username、password_hash、display_name、enabled、created_at、updated_at。
- 暂用随机不透明凭据，客户端按 Bearer 方式携带，不使用客户端传入的用户编号作为身份依据。
- 凭据保存在单个后端进程内，默认 1 小时到期；后端重启后需重新登录。
- 同一管理员再次登录替换旧凭据；不同管理员互不影响。
- 每次受保护请求都检查账号当前状态，账号被禁用时已有凭据不能用于访问。
- 本轮拦截范围为 /api/admin/**，仅登录入口公开；未来增加管理接口会复用此入口保护。

## 7 验证记录

验证日期：2026-11-12。

- Maven clean package 成功，10 个测试通过：8 个接口与数据库集成测试，2 个凭据存储测试。
- 覆盖成功登录和资料查询、错误密码、未知账号、SQL 注入式输入、禁用账号、无效凭据、异常请求、重新登录替换、过期边界及数据库健康查询。
- 真实 MySQL 8.0.46 与可执行 JAR 完成 5 项 HTTP 验证：健康、正确登录、带凭据查询、不带凭据拒绝、错误密码拒绝。
- 当前测试报告位于 target/surefire-reports，MySQL 联调结果位于 target/mysql-smoke-results.txt；target 可由 clean 清除。

管理员接口已由使用者通过 PowerShell 验收，第三部分 A 的 48 项任务测试也已由用户复跑通过。当前第三部分 B 完成后全量 112 项测试通过；A/B 任务合计 83 项另在独立临时 MySQL 库通过，真实定时扫描及重启补偿也已用新 JAR 验证。B 的用户验收仍待进行，说明见 TASK_MANAGEMENT.md。学生真实微信与小程序端到端联调仍待完成。终端环境保持用户原状，运行时明确指定 JDK。

2026-11-19 抢单后端完成，全量 123 项通过，独立 MySQL 中任务相关 94 项通过。0.5 JAR 已验证重启后可结清待确认申请。第四部分功能及复跑方法见 [接单接口](./TASK_CLAIM.md)。

0.6 新增确认取件、双方取消和客服审核。全量 131 项通过，独立 MySQL 任务相关 102 项通过。可以在 IDEA 运行 TaskCancellationIntegrationTest 复跑新增的 8 项测试。

0.7 新增交付与模拟结算。全量 143 项通过，独立 MySQL 任务相关 114 项通过；另验证了从 0.6 的数据库结构升级时保留已有任务。可以在 IDEA 运行 TaskDeliveryIntegrationTest 复跑新增的 12 项测试，真实小程序扫码待联调。

0.8 新增管理员任务管理和交付确认窗口。全量 156 项、独立 MySQL 任务用例 127 项通过，旧结构升级与可执行 JAR 的重启补偿验证通过。新增 13 项可在 AdminTaskIntegrationTest 复跑。

确认接口可能返回 HTTP 202 和 `outcome=PENDING`。客户端需通过任务详情或 `GET /api/student/tasks/{id}/delivery/confirmation` 查询最终结果。窗口可通过 `app.task.delivery.window-ms` 配置，默认 1000 毫秒。前端联调时须处理确认中、完成、关闭、审核暂停和交付码过期的结果。

0.9 新增在线提醒。全量 167 项、独立 MySQL 任务用例 138 项通过，其中 TaskSocketIntegrationTest 包含 11 项真实 WebSocket 场景。

连接地址为 `ws://localhost:8080/ws/student/tasks`，部署 HTTPS 时使用对应的 `wss://` 地址。握手使用学生 Authorization 请求头，建立成功返回 READY，支持 PING/PONG 心跳。消息包含 eventId、eventType、taskId、occurredAt，收到后查询 HTTP 接口。关闭码 4401 表示需要重新登录。具体事件和前端恢复规则见[后端设计第 21 节](../后端设计计划.md#21-第六部分-在线状态提醒与断线恢复)。
