# 任务管理接口 第三部分 B

设计与实施日期：2026-11-14，B 部分在 0.4.0-SNAPSHOT 实现并验证。范围为我发布的任务、取消、持久化过期处理。两个接口均要求学生 Bearer 凭据，沿用账号启用检查。0.5 版本增加确认窗口，窗口内发布者取消优先，结束后先确认这轮接单再决定能否直接取消。见 [接单接口](./TASK_CLAIM.md)。

## 1 我发布的任务

GET /api/student/tasks/mine?page=1&size=20&status=WAITING

- 当前学生来自认证结果，不能通过 publisherId 参数指定其他学生。
- page 至少 1，size 为 1—100；默认 1/20，status 选填，支持 TaskStatus 中已定义的状态名。
- 按 createdAt DESC、id DESC，返回 data.items、total、page、size；items 复用公开 Summary，不包含任何私密列。
- 有效状态用于筛选和展示：存储 WAITING 但已经到期的记录算 EXPIRED；无需等待扫描。

## 2 取消

POST /api/student/tasks/{id}/cancel，无需请求体。

成功示例：{"code":"OK","message":"成功","data":{"id":1,"status":"CANCELLED"}}。

| HTTP / code | 含义 |
| --- | --- |
| 200 / OK | 本次取消成功，或同一发布者重试已取消任务 |
| 400 / INVALID_REQUEST | 编号、分页或状态格式不合法 |
| 401 / UNAUTHORIZED | 学生身份无效、账号当前不可用，或误用管理员凭据 |
| 404 / TASK_NOT_FOUND | 任务不存在或不属于当前发布者 |
| 409 / TASK_EXPIRED | 本人任务已过期；若刚到期，过期状态先提交保存 |
| 409 / TASK_STATE_CONFLICT | 本人任务处于已接单、已取件、待确认、已完成或已关闭 |

取消采用事务与按本人任务的锁定读取，取得行锁后采样服务端时间，再条件更新。专用过期异常不回滚已确认的过期状态，其他异常回滚。已取消任务重复请求不改变原更新时间，也不因后来经过截止时间变成过期。

## 3 扫描与时间边界

默认应用启动后立即扫描，此后每轮结束间隔 30 秒；每批最多 200 条、每轮最多 10 批，各批独立事务。只更新截止时间小于等于当前 UTC 时间的 WAITING，其他状态不变。积压超过一轮容量时下一轮继续补处理。

关闭自动扫描可设置 app.task.expiration.enabled=false；间隔可设 app.task.expiration.fixed-delay-ms。隔离测试默认关闭，通过固定时钟直接触发任务服务进行验证。扫描没有公开 HTTP 入口。

大厅、mine 和详情继续即时判断截止时间；GET 不修改数据库，取消和未来抢单不以定时任务是否执行作为可操作依据。

## 4 当前验证

- Maven verify 通过：共 112 项，包含既有 77 项及新增 B 部分 35 项，失败和错误均为 0。
- B 覆盖本人查询隔离、全部状态筛选、过期即时展示、稳定分页、隐私缺省、正常/重复/越权取消、到期提交与 409、并发重试、取消与扫描竞争、等待行锁期间到期、201 条积压分批处理。
- A 的 48 项和 B 的 35 项，共 83 项，在独立临时 MySQL 库另行运行并通过。临时库已删除，微信网关使用模拟。
- 可执行 JAR 在另一独立 MySQL 库验证：健康、新接口匿名 401、关闭扫描时保留 WAITING、重启开启扫描后自动 EXPIRED、未来任务和 CANCELLED 不变、重复扫描不改写更新时间。
- 只向临时库写入虚构数据，业务库学生与任务计数检查前后一致。验证进程和临时库已清理，原 8080 服务未替换。
- 当前报告为 target/surefire-reports、target/task-b-mysql-results.txt、target/task-b-smoke-results.txt；clean 会清除这些产物，本说明保存结论。

在 IDEA 打开 src/test/java/cn/campus/errand/task/TaskManagementIntegrationTest.java，使用项目 JDK25 运行，预期 35 项通过。微信与时间在测试内模拟，正式服务不提供任意指定学生身份的登录接口。真实学生 HTTP 操作仍等待微信配置和有效 code。

启动本轮 JAR：

```powershell
Set-Location 'D:\计网项目和实验\backend'
& 'C:\Program Files\Java\jdk-25.0.3\bin\java.exe' -jar '.\target\campus-errand-backend-0.4.0-SNAPSHOT.jar'
```

本轮没有修改 Windows 或 IDEA 终端环境，尚未完成使用者的 B 验收、真实微信和小程序端到端联调。真实抢单竞争、接单者权限、操作记录和后续结算仍待实现。
