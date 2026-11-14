# 任务基础接口 第三部分 A

实现日期：2026-11-14。当前完成任务发布、任务大厅和任务详情的后端；三个接口均要求有效学生凭据，管理员凭据不能替代。真实小程序联调仍等待微信配置。

## 1 入口与调用关系

| 方法与路径 | 功能 |
| --- | --- |
| POST /api/student/tasks | 发布任务 |
| GET /api/student/tasks | 分页任务大厅 |
| GET /api/student/tasks/{id} | 任务详情 |

请求携带 Authorization: Bearer <学生accessToken>。WebConfig 识别当前学生 → TaskController 接收输入 → TaskService 校验与控制可见范围 → TaskMapper 操作 errand_task。

TaskRecord 只用于数据库访问。列表使用 Summary，详情使用 Detail 和受控的 privateInfo，不直接将数据库对象序列化给客户端。

## 2 发布任务

POST /api/student/tasks，Content-Type: application/json。

```json
{
  "title": "帮取快递",
  "type": "EXPRESS",
  "pickupLocation": "校园快递站东门",
  "deliveryLocation": "宿舍楼一楼门口",
  "deadlineAt": "2099-12-31T10:00:00Z",
  "rewardAmount": 3.50,
  "description": "两个小包裹",
  "publisherPhone": "13800000000",
  "publisherWechat": "demo_wechat_contact",
  "privateInstructions": "DEMO-PICKUP-CODE"
}
```

以上为测试示例；实际发布应填写合适的未来送达时间。号码、微信号、交接码均为示例占位信息。

| 字段 | 当前约定 |
| --- | --- |
| title | 必填，最多 100 字符，保存时去首尾空白 |
| type | 必填：EXPRESS 快递、FOOD 外卖、DOCUMENT 资料、MOVING 搬运、OTHER 其他 |
| pickupLocation / deliveryLocation | 必填自由文本，各最多 200 字符 |
| deadlineAt | 必填 ISO 8601 时间，须带时区并晚于服务端当前时间 |
| rewardAmount | 必填，至少 0.01，最多 8 位整数和 2 位小数，对应 DECIMAL(10,2) 的存储范围 |
| description | 选填公开说明，最多 1000 字符 |
| publisherPhone | 必填，基本格式为 7—15 位数字，可带前导加号；不含空格，不验证归属或能否联系 |
| publisherWechat | 选填，最多 64 字符，当前仅做长度检查 |
| privateInstructions | 选填私密说明，最多 1000 字符 |

手机号格式支持本地号码和带国家码的形式，属于本轮实现的基础校验，可按后续需求调整。微信号与私密说明空白时保存为空。

发布者来自已验证的学生身份；客户端额外传 publisherId、status 等字段不会改变实际发布者和初始状态。成功时 HTTP 200：

```json
{"code":"OK","message":"成功","data":{"id":1,"status":"WAITING"}}
```

发布操作采用数据库事务。日期以 UTC 的 DATETIME(6) 存储、以 ISO 8601 返回，比较与保存精度统一到微秒。未增加发布请求编号机制，发布响应丢失后的防重复发布仍待设计。

## 3 任务大厅

GET /api/student/tasks?page=1&size=20&type=EXPRESS

- page 默认 1、至少为 1；size 默认 20、范围 1—100。
- type 选填，值与发布类型一致。
- 只展示存储状态为 WAITING 且 deadlineAt 晚于当前时间的任务。
- 按 createdAt 降序、id 降序排列，时间相同时也保持确定顺序。
- data 包含 items、total、page、size。超出末页返回空 items。
- 摘要字段为 id、title、type、取送地点、deadlineAt、rewardAmount、status、createdAt。
- 即使发布者查看大厅，也不返回联系方式和私密说明。数据库大厅查询本身不读取私密列。

## 4 任务详情与历史边界

GET /api/student/tasks/{id}

普通详情包含任务公开字段、publisherId、description 和服务端计算的 ownedByMe。

- 发布者查看自己未结束的任务：增加 privateInfo，包含 publisherPhone、publisherWechat、privateInstructions。
- 其他学生查看进行中任务：不返回 privateInfo。
- 本轮没有接单能力，尚不提供接单者的私密读取权限。
- 当前实现采用较保守的历史边界：已完成、已取消、已过期、已关闭任务，仅发布者能查看其公开历史；不返回 privateInfo。无关用户返回 404。
- 发布者修改个人联系方式不改变任务内保存的交接信息。
- 在请求参数中自称发布者或设置 ownedByMe=true 不能改变权限。

WAITING 任务达到截止时间后，大厅立即过滤，发布者详情显示计算出的 EXPIRED，但本轮不写回数据库状态。定时过期、取消与接单竞争留到第三部分 B 和抢单模块。历史私密信息的长期保留与管理员查询规则仍待细化。

## 5 返回与错误

| HTTP / code | 含义 |
| --- | --- |
| 200 / OK | 发布、查询成功 |
| 400 / INVALID_REQUEST | 必填、类型、时间、金额、手机号、分页参数或编号格式不符 |
| 401 / UNAUTHORIZED | 未登录、凭据无效或过期、账号当前不可用，或使用了管理员凭据 |
| 404 / TASK_NOT_FOUND | 任务不存在，或当前用户无权查看结束态任务 |

## 6 验证与运行

- Maven verify 通过，共 77 个测试：既有身份模块 29 个、任务模块 48 个。
- 任务测试覆盖 A/B 身份差异、私密字段隔离、伪造发布者、字段约束、分页排序、截止时间与存储精度、个人资料变化等。
- 同一任务测试类在独立临时 MySQL 库另行运行 48 项，全部通过；临时库已删除，不向 campus_errand 插入模拟账号或任务。
- 2026-11-14 用户反馈本机复跑 48 项任务隔离测试，全部通过；真实微信与小程序联调仍待完成。
- 新 JAR 在独立临时端口启动验证：健康接口通过，三个任务接口均拒绝匿名访问。业务 errand_task 表已创建，验证时任务与学生记录均为 0。
- H2 报告位于 target/surefire-reports；MySQL 验证记录为 target/task-mysql-results.txt；启动检查为 target/task-smoke-results.txt，clean 会清除这些结果。

在 IDEA 打开 src/test/java/cn/campus/errand/task/TaskIntegrationTest.java，使用项目 JDK25 运行即可查看隔离测试。微信身份在测试内模拟，正式服务没有任意指定学生身份的公开登录入口。

启动当前版本可使用绝对路径，不修改终端环境：

```powershell
Set-Location 'D:\计网项目和实验\backend'
& 'C:\Program Files\Java\jdk-25.0.3\bin\java.exe' -jar '.\target\campus-errand-backend-0.3.0-SNAPSHOT.jar'
```

本轮未实现：我的任务、取消、持久化过期处理、抢单、交付、结算、Redis、WebSocket。尚未完成真实小程序端到端联调，也未宣称已通过并发抢单实验。
