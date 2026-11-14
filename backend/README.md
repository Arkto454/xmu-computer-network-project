# 校园互助跑腿后端

已实现管理员登录、学生微信登录后端，以及任务发布、分页列表、详情。真实小程序联调等待 AppID、AppSecret 和有效临时 code。说明见 [学生登录](./STUDENT_LOGIN.md)与[任务基础接口](./TASK_API.md)。

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
    TaskController.java        发布、大厅与详情接口
    TaskService.java           校验、任务保存与私密字段过滤
    TaskMapper.java            任务数据库操作
    TaskContracts.java         公开摘要、受控详情与发布表单
  config/WebConfig.java        管理员/学生接口身份拦截与密码编码器
  common/                      统一响应、异常反馈与健康检查
src/main/resources/
  application.yml              公共配置，数据库参数可由环境变量覆盖
  schema.sql                   幂等创建管理员表，不删除已有表或数据
  schema-mysql.sql             幂等创建学生表和任务表
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

本轮已生成 0.3.0-SNAPSHOT 的可执行 JAR，在 PowerShell 中可直接启动：

```powershell
Set-Location 'D:\计网项目和实验\backend'
& 'C:\Program Files\Java\jdk-25.0.3\bin\java.exe' -jar .\target\campus-errand-backend-0.3.0-SNAPSHOT.jar
```

上述启动命令明确指定本机 Java 路径，无须修改 Windows 或 IDEA 终端的 Path、JAVA_HOME。队友需替换为自己机器上的 JDK 路径。

默认地址为 http://localhost:8080。若端口被占用：

```powershell
& 'C:\Program Files\Java\jdk-25.0.3\bin\java.exe' -jar .\target\campus-errand-backend-0.3.0-SNAPSHOT.jar --server.port=8081
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

管理员接口已由使用者通过 PowerShell 验收。学生模块的真实微信联调边界见 STUDENT_LOGIN.md，任务模块的接口与当前验证记录见 TASK_API.md。第三部分 A 完成后共 77 个测试通过，任务模块 48 项另在独立临时 MySQL 库通过。终端环境保持用户原状，运行时明确指定 JDK。
