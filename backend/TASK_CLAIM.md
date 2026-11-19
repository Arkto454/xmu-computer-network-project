# 接单接口

版本 0.5.0-SNAPSHOT。两个接口都要求学生 Bearer 凭据。未登录或账号失效返回 HTTP 401。

## 提交接单与确认结果

`POST /api/student/tasks/{id}/claim`

```json
{
  "requestId": "7707d7f2-c0ac-4cd2-a7e0-f029a599a715",
  "runnerPhone": "13900000000",
  "runnerWechat": "demo_runner"
}
```

requestId 是本次操作的 UUID，由客户端在提交前生成并保留。手机号必填，微信号可选。示例联系方式仅作测试。

| HTTP / code | data.outcome 或结果 |
| --- | --- |
| 202 / CLAIM_PENDING | PENDING，申请已受理，等待接单确认 |
| 200 / OK | ACCEPTED 为该请求接单成功，ALREADY_ACCEPTED 表示任务此前已分给本人 |
| 409 / TASK_TAKEN | 任务已被其他人接走 |
| 409 / TASK_CANCELLED | 发布者已取消任务 |
| 409 / TASK_EXPIRED | 任务已到期 |
| 409 / OWN_TASK | 本人发布的任务不能接 |
| 409 / NOT_ELIGIBLE | 窗口结束时无符合资格的申请者 |
| 409 / TASK_STATE_CONFLICT | 当前任务状态不允许接单 |
| 409 / CLAIM_REQUEST_MISMATCH | 同一账号复用了编号，但任务或联系方式发生变化 |
| 409 / CLAIM_ALREADY_PENDING | 同一学生已用其他编号申请此任务，请用原编号确认 |
| 400 / INVALID_REQUEST | 编号或字段格式错误 |
| 404 / TASK_NOT_FOUND | 任务不存在 |

受理与接单结果的 data 返回 taskId、requestId、outcome；输入绑定错误沿用统一错误格式。202 或网络结果不明时，用**原编号、原任务和原表单**重发同一 POST。可以先等约 1 秒再查。后端已记录结果时会直接返回原结果。结果确定后再读取任务详情，以获取最新进度。

第一笔有效申请开始默认 1000 毫秒窗口，由服务端锁定任务后取时。窗口内发布者可以直接取消。窗口结束后即使后台定时扫描还没执行，迟到的取消也会先确认这轮接单结果。到期任务按过期结清。确认时按数据库登记顺序选择仍符合资格的学生。在同一事务中更新任务状态、写入唯一接单归属并保存各申请结果。申请结清后清除申请表内的联系字段，赢家的交接信息保存在归属表。

## 我接单的任务

`GET /api/student/tasks/accepted?page=1&size=20&status=ACCEPTED`

status 可省略，默认查本人所有已接任务；列表按接单确认时间和任务编号倒序，响应沿用分页摘要，无私密字段。任务详情增加 acceptedByMe。进行中只有发布者和正式接单者收到 privateInfo，其中 runnerPhone、runnerWechat 对应接单者本次填写的资料，微信号为空时省略。结束后双方可读历史详情，私密信息省略。

## 验证

- 2026-11-19 全量 Maven verify 通过，共 123 项。新增 TaskClaimIntegrationTest 11 项。
- 独立 MySQL 库的任务套件共 94 项通过，验证行锁、请求唯一键、到期、取消和接单竞争及访问权限。
- 在临时库放入已到确认时间的待处理申请，启动 0.5 JAR 后后台自动补处理。确认只生成一条接单归属，申请结果与任务均为已接单，申请内暂存的电话已清除。
- 需要本机复跑时，用 IDEA 运行 TaskClaimIntegrationTest。测试使用模拟微信网关和可控服务端时钟。真实微信与 S07 小程序联调，以及课程要求的 10、30、50 人压力实验待进行。
- 接单后双方取消、确认取件及取件后客服审核已在 0.6 实现，见 [后端设计第 18 节](../后端设计计划.md#18-第五部分-确认取件与双方取消)。
