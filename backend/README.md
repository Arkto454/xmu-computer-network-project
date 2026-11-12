# 校园互助跑腿后端 第一阶段

本轮实现可启动的 Spring Boot 工程、管理员账号初始化、管理员登录和当前管理员信息查询。学生登录与任务功能后续逐步实现。

## 1 当前实现

| 能力 | 说明 |
| --- | --- |
| 健康检查 | 查询数据库后返回应用与数据库状态 |
| 管理员初始化 | 显式开启时创建账号，数据库保存 BCrypt 哈希；已有账号不覆盖密码 |
| 登录 | 检查账号、密码与启用状态，签发随机 Bearer 凭据 |
| 身份查询 | 校验凭据及当前账号状态，返回管理员编号、账号与名称 |
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
  config/WebConfig.java        管理接口拦截与密码编码器
  common/                      统一响应、异常反馈与健康检查
src/main/resources/
  application.yml              公共配置，数据库参数可由环境变量覆盖
  schema.sql                   幂等创建管理员表，不删除已有表或数据
src/test/                      隔离 H2 测试与凭据过期测试
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

在 PowerShell 中执行：

```powershell
Set-Location 'D:\计网项目和实验\backend'
.\mvnw.cmd clean package
java -jar .\target\campus-errand-backend-0.1.0-SNAPSHOT.jar
```

首次使用 Wrapper 可能下载 Maven 和依赖。也可以在 IDEA 中将 pom.xml 作为 Maven 工程打开，运行 CampusErrandApplication；运行配置的工作目录设为 backend，以便读取本机配置文件。

默认地址为 http://localhost:8080。若端口被占用：

```powershell
java -jar .\target\campus-errand-backend-0.1.0-SNAPSHOT.jar --server.port=8081
```

测试命令：

```powershell
.\mvnw.cmd test
```

自动化测试使用隔离的 H2 内存库，以 MySQL 模式执行当前 schema 和真实 MyBatis 操作，不连接本机 MySQL。H2 结果与真实 MySQL 联调结果分别记录，不等同于未来并发抢单实验。

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

下一小步建议先由使用者在 Apifox 跑通登录和资料查询，再继续学生身份与任务发布。
