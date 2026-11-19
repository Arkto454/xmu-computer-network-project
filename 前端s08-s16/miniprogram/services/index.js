const { getEnvironment } = require('../config/env')
const { ServiceError } = require('./errors')
const mock = require('./mock')
const real = require('./real')
function isTaskId(value) { return typeof value === 'string' && /^[A-Za-z0-9_-]{1,80}$/.test(value) }
function invokeTask(provider, method, args) {
  const operation = provider.tasks && provider.tasks[method]
  if (typeof operation !== 'function') return Promise.reject(new ServiceError('configuration', '当前服务暂未实现该Mock能力。'))
  return Promise.resolve().then(() => operation.apply(provider.tasks, args))
}
function createServices(options) {
  const settings = options || {}
  const environment = settings.environment || getEnvironment()
  if (environment.mode !== 'mock' && environment.mode !== 'real') throw new ServiceError('configuration', '未知数据模式。')
  const provider = environment.mode === 'mock' ? (settings.mockProvider || mock) : (settings.realProvider || real)
  return {
    mode: environment.mode,
    tasks: {
      getPublishState() { return provider.tasks && typeof provider.tasks.getPublishState === 'function' ? provider.tasks.getPublishState() : null },
      startNewPublish() { if (provider.tasks && typeof provider.tasks.startNewPublish === 'function') return provider.tasks.startNewPublish() },
      listPublic(query) { return invokeTask(provider, 'listPublic', [query]) },
      getPublic(taskId, query) {
        if (!isTaskId(taskId)) return Promise.reject(new ServiceError('validation', '缺少有效任务编号。'))
        return invokeTask(provider, 'getPublic', [taskId, query])
      },
      listMine(userId, relationship, query) { return invokeTask(provider, 'listMine', [userId, relationship, query]) },
      publish(draft, userId) { return invokeTask(provider, 'publish', [draft, userId]) },
      accept(taskId, userId, contact) { return invokeTask(provider, 'accept', [taskId, userId, contact]) },
      cancel(taskId, userId) { return invokeTask(provider, 'cancel', [taskId, userId]) },
      markPickedUp(taskId, userId) { return invokeTask(provider, 'markPickedUp', [taskId, userId]) },
      markDelivered(taskId, userId) { return invokeTask(provider, 'markDelivered', [taskId, userId]) },
      confirmCompletion(taskId, userId) { return invokeTask(provider, 'confirmCompletion', [taskId, userId]) },
      getDeliveryCode(taskId, userId) {
        if (!isTaskId(taskId)) return Promise.reject(new ServiceError('validation', '缺少有效任务编号。'))
        return invokeTask(provider, 'getDeliveryCode', [taskId, userId])
      }
    },
    scenarios: environment.mode === 'mock' ? provider.scenarios : null,
    resetMock: environment.mode === 'mock' && typeof provider.reset === 'function' ? provider.reset : null,
    snapshotMock: environment.mode === 'mock' && typeof provider.snapshot === 'function' ? provider.snapshot : null
  }
}
// 引入模块不会联网；业务页后续只调用这个入口，不自行选择数据来源。
const services = createServices()
module.exports = {
  createServices,
  tasks: services.tasks,
  mode: services.mode,
  scenarios: services.scenarios,
  resetMock: services.resetMock,
  snapshotMock: services.snapshotMock
}
