const { getEnvironment } = require('../config/env')
const { ServiceError } = require('./errors')
const mock = require('./mock')
const real = require('./real')
function isTaskId(value) { return typeof value === 'string' && /^[A-Za-z0-9_-]{1,80}$/.test(value) }
function createServices(options) {
  const settings = options || {}
  const environment = settings.environment || getEnvironment()
  if (environment.mode !== 'mock' && environment.mode !== 'real') throw new ServiceError('configuration', '未知数据模式。')
  const provider = environment.mode === 'mock' ? (settings.mockProvider || mock) : (settings.realProvider || real)
  return {
    mode: environment.mode,
    tasks: {
      listPublic() { return Promise.resolve().then(() => provider.tasks.listPublic()) },
      getPublic(taskId) {
        if (!isTaskId(taskId)) return Promise.reject(new ServiceError('validation', '缺少有效任务编号。'))
        return Promise.resolve().then(() => provider.tasks.getPublic(taskId))
      }
    }
  }
}
// 引入模块不会联网；业务页后续只调用这个入口，不自行选择数据来源。
const services = createServices()
module.exports = { createServices, tasks: services.tasks, mode: services.mode }
