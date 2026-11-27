const { getEnvironment } = require('../config/env')
const { ServiceError } = require('./errors')
const mock = require('./mock')
const real = require('./real')
const { session } = require('../utils/session')
const { createClaimService } = require('./claim')
function isTaskId(value) { return typeof value === 'string' && /^[A-Za-z0-9_-]{1,80}$/.test(value) }
function invokeTask(provider, method, args) {
  const operation = provider.tasks && provider.tasks[method]
  if (typeof operation !== 'function') return Promise.reject(new ServiceError('configuration', '当前服务暂未实现该Mock能力。'))
  return Promise.resolve().then(() => operation.apply(provider.tasks, args))
}
function createServices(options) {
  const settings = options || {}
  const identity = settings.session || session
  const environment = settings.environment || getEnvironment()
  if (environment.mode !== 'mock' && environment.mode !== 'real') throw new ServiceError('configuration', '未知数据模式。')
  const provider = environment.mode === 'mock' ? (settings.mockProvider || mock) : (settings.realProvider || real)
  const taskService = {
    mode: environment.mode,
    tasks: {
      listEarnings(userId, query) { return invokeTask(provider, 'listEarnings', [userId, query]) },
      getClosure(taskId, cancellationId) { return invokeTask(provider, 'getClosure', [taskId, cancellationId]) },
      getPublishState() { return provider.tasks && typeof provider.tasks.getPublishState === 'function' ? provider.tasks.getPublishState() : null },
      startNewPublish() { if (provider.tasks && typeof provider.tasks.startNewPublish === 'function') return provider.tasks.startNewPublish() },
      listPublic(query) { return invokeTask(provider, 'listPublic', [query]) },
      getPublic(taskId, query) {
        if (!isTaskId(taskId)) return Promise.reject(new ServiceError('validation', '缺少有效任务编号。'))
        return invokeTask(provider, 'getPublic', [taskId, query])
      },
      listMine(userId, relationship, query) { return invokeTask(provider, 'listMine', [userId, relationship, query]) },
      publish(draft, userId) { return invokeTask(provider, 'publish', [draft, userId]) },
      accept(taskId, userId, contact, requestId) { return invokeTask(provider, 'accept', [taskId, userId, contact, requestId]) },
      cancel(taskId, userId) { return invokeTask(provider, 'cancel', [taskId, userId]) },
      markPickedUp(taskId, userId) { return invokeTask(provider, 'markPickedUp', [taskId, userId]) },
      markDelivered(taskId, userId) { return invokeTask(provider, 'markDelivered', [taskId, userId]) },
      refreshDeliveryCode(taskId, userId, version) { return invokeTask(provider, 'refreshDeliveryCode', [taskId, userId, version]) },
      revokeDeliveryCode(taskId, userId, version) { return invokeTask(provider, 'revokeDeliveryCode', [taskId, userId, version]) },
      verifyDeliveryCode(taskId, userId, payload) { return invokeTask(provider, 'verifyDeliveryCode', [taskId, userId, payload]) },
      confirmCompletion(taskId, userId, payload) { return invokeTask(provider, 'confirmCompletion', [taskId, userId, payload]) },
      getDeliveryCode(taskId, userId) {
        if (!isTaskId(taskId)) return Promise.reject(new ServiceError('validation', '缺少有效任务编号。'))
        return invokeTask(provider, 'getDeliveryCode', [taskId, userId])
      }
    },
    scenarios: environment.mode === 'mock' ? provider.scenarios : null,
    resetMock: environment.mode === 'mock' && typeof provider.reset === 'function' ? provider.reset : null,
    snapshotMock: environment.mode === 'mock' && typeof provider.snapshot === 'function' ? provider.snapshot : null
  }
  return Object.assign(taskService, { claim: createClaimService({ session: identity,
    persistent: environment.mode === 'real', storage: settings.claimStorage,
    scope: environment.mode + ':' + environment.httpBaseUrl,
    accept: (taskId, userId, contact, requestId) => taskService.tasks.accept(taskId, userId, contact, requestId) }) })
}
// 引入模块不会联网；业务页后续只调用这个入口，不自行选择数据来源。
const services = createServices()
module.exports = {
  createServices,
  tasks: services.tasks,
  mode: services.mode,
  scenarios: services.scenarios,
  resetMock: services.resetMock,
  snapshotMock: services.snapshotMock,
  claim: services.claim
}
