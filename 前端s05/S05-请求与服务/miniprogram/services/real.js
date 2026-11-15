const { ServiceError } = require('./errors')
function notConnected() {
  return Promise.reject(new ServiceError('configuration', '任务接口契约尚未确认，暂不能查询真实任务。'))
}
// 不猜测任务URL/分页/响应结构；S08确认后逐个接入request.js，禁止失败时退回Mock。
module.exports = { tasks: { listPublic: notConnected, getPublic: notConnected } }
