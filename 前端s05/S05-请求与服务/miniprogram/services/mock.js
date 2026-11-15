const { listPublicExamples, findPublicExample } = require('../config/demo')
const { ServiceError } = require('./errors')
// S05只复用现有公开样例；多账号、状态变化与故障场景由S07扩展。
const tasks = {
  listPublic() { return Promise.resolve(listPublicExamples()) },
  getPublic(taskId) {
    const task = findPublicExample(taskId)
    return task ? Promise.resolve(task) : Promise.reject(new ServiceError('business', '没有这个样例任务。', { businessCode: 'MOCK_TASK_NOT_FOUND', outcome: 'rejected' }))
  }
}
module.exports = { tasks }
