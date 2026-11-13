# 学生微信登录 后端与隔离测试

实现日期：2026-11-13。当前完成后端代码和隔离测试；尚无真实小程序配置，不能把模拟结果记为真实微信登录成功。

## 1 功能与流程

小程序取得 wx.login() 返回的临时 code → POST 给本后端 → 后端向微信换取身份 → 查找或创建学生 → 签发本系统凭据 → 携带凭据查询当前学生。

两个接口：

| 接口 | 作用 |
| --- | --- |
| POST /api/student/auth/wechat | 接收微信临时 code，返回学生公开资料和登录凭据 |
| GET /api/student/me | 按学生凭据查询当前学生公开资料 |

登录请求：

```json
{"code":"小程序通过wx.login取得的真实临时code"}
```

成功响应的 data 包含 accessToken、tokenType、expiresAt、student；student 仅包含 id、nickname、contact。首次登录默认昵称为微信用户，联系方式为空。昵称不由微信登录自动取得，本轮尚未实现资料编辑。

查询请求携带 Authorization: Bearer <accessToken>。学生凭据默认 1 小时有效；同一学生重登替换旧凭据，后端重启后失效。管理员与学生各使用独立凭据存储，即使内部编号相同，也不能互相访问。

## 2 文件和数据

| 文件或类 | 职责 |
| --- | --- |
| StudentController / StudentContracts | 接口入口与输入输出 |
| StudentAuthService | 登录、首次创建和当前身份查询 |
| WeChatGateway / HttpWeChatGateway | 微信服务调用边界，便于隔离测试 |
| WeChatConfig / WeChatProperties | 服务端配置与连接、读取超时 |
| StudentMapper / StudentAccount | 学生持久化 |
| StudentTokenStore | 学生独立登录状态 |
| common/InMemoryTokenStore | 管理员与学生复用算法，但使用不同实例 |
| schema-mysql.sql | MySQL 学生表及区分大小写的身份唯一约束 |
| src/test/resources/schema-test.sql | H2 对应结构，仅用于隔离测试 |

student_user 字段：id、app_id、wechat_openid、nickname、contact、enabled、created_at、updated_at。app_id 与 wechat_openid 共同唯一，比较区分大小写。同一微信身份并发首次登录通过唯一约束收敛为同一学生。

app_id 和 openid 来自后端配置与微信响应，不信任客户端提交的身份。AppSecret 仅留在服务端配置，微信 session_key 不保存、不作为本系统 Token，也不返回给客户端。

## 3 尚未配置时的行为

未设置 AppID、AppSecret 时，健康检查和管理员功能仍可运行。

对学生登录接口提交非空 code，返回 HTTP 503、code 为 WECHAT_NOT_CONFIGURED。这个结果表示缺少外部配置，不表示程序已经调用微信，更不表示输入的 code 已经验证。

| 情况 | HTTP / code |
| --- | --- |
| code 缺失、空白、格式不符或超长 | 400 / INVALID_REQUEST |
| 未配置微信 | 503 / WECHAT_NOT_CONFIGURED |
| 微信明确报告临时 code 无效或已使用 | 401 / WECHAT_LOGIN_FAILED |
| 微信忙、响应异常或连接失败 | 503 / WECHAT_UNAVAILABLE |
| 学生账号已禁用，尝试登录 | 403 / ACCOUNT_DISABLED |
| 无有效学生凭据，或账号当前不可用 | 401 / UNAUTHORIZED |

本轮只确定禁用学生不能登录和查询受保护接口；已有任务如何处理，仍按需求文档保留待定。

## 4 后续接入真实小程序

拿到配置后，在已被 Git 忽略的 application-local.properties 中填写：

```properties
app.wechat.app-id=填写实际小程序AppID
app.wechat.app-secret=填写实际小程序AppSecret
```

也可以配置 WECHAT_APP_ID、WECHAT_APP_SECRET 环境变量。配置与临时 code 需属于同一小程序，真实登录待取得配置后单独验证。

流程参考入口：[微信登录说明](https://developers.weixin.qq.com/miniprogram/dev/framework/open-ability/login.html)、[code2Session](https://developers.weixin.qq.com/miniprogram/dev/OpenApiDoc/user-login/code2Session.html)。当前工具未能读取官方网页，真实联调时需再次核对平台要求和响应。

## 5 隔离测试

- StudentAuthIntegrationTest：替换微信网关，真实执行 Controller、Service、MyBatis、H2 和凭据校验。
- HttpWeChatGatewayTest：拦截 HTTP 请求并模拟微信响应，检查请求与错误处理，不连接微信。
- 管理员原有 10 个测试保留，用于回归。
- 共 29 个测试通过，其中学生业务 9 个、微信 HTTP 适配 10 个、管理员 10 个。
- 包含同身份四请求并发首次登录、应用及大小写隔离、重复登录、禁用与跨身份凭据拒绝。

可以在 IDEA 中打开测试类，使用项目 JDK25 运行。隔离测试无须 AppID、AppSecret 或真实微信临时 code。

H2 验证不替代真实 MySQL 校验，也不替代小程序到微信服务的端到端测试。当前没有开放任意指定学生身份的开发登录接口，也未向业务数据库插入模拟学生账号。

真实 MySQL 验证已完成：新版本在独立临时端口成功启动，健康接口通过；未配置微信返回 503 / WECHAT_NOT_CONFIGURED；匿名学生查询返回 401。student_user 表已创建，校对规则为 utf8mb4_bin，学生记录为 0。临时验证进程已停止，原有 8080 服务未替换。记录位于 target/student-smoke-results.txt。

## 6 启动

构建后的新 JAR 是 0.2.0-SNAPSHOT。保持终端原有环境，明确使用现有 JDK：

```powershell
Set-Location 'D:\计网项目和实验\backend'
& 'C:\Program Files\Java\jdk-25.0.3\bin\java.exe' -jar '.\target\campus-errand-backend-0.2.0-SNAPSHOT.jar'
```

已运行的旧 JAR 不会自动加载新接口，启动新版本前应处理端口占用。本轮验证使用独立端口，不替换原 8080 服务。
