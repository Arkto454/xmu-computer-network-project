const fixtures = require('./fixtures')
const { createScenarioController } = require('./scenarios')
const { ServiceError } = require('../services/errors')

const PUBLIC_FIELDS = [
  'id', 'title', 'typeName', 'pickupLocation', 'deliveryLocation',
  'rewardText', 'deadlineText', 'status', 'description'
]
const ACTIVE_STATUSES = ['已接单', '已取件', '待确认']

function createMockService(options) {
  const settings = options || {}
  const scenarios = settings.scenarios || createScenarioController()
  let state = fixtures.createInitialState()

  function findTask(taskId) {
    return state.tasks.find(task => task.id === taskId) || null
  }
  function accountExists(userId) {
    return state.accounts.some(account => account.id === userId)
  }
  function requireAccount(userId) {
    if (!accountExists(userId)) throw new ServiceError('validation', '请先选择演示账号。', { outcome: 'not_sent' })
  }
  function publicTask(task) {
    const result = {}
    PUBLIC_FIELDS.forEach(key => { result[key] = task[key] })
    result.isDemo = true
    return result
  }
  function relation(task, userId) {
    if (!userId) return null
    if (task.ownerId === userId) return 'owner'
    if (task.runnerId === userId) return 'runner'
    return null
  }
  function privateFor(task, userId) {
    const role = relation(task, userId)
    if (!role || !ACTIVE_STATUSES.includes(task.status)) return null
    const info = task.privateInfo || {}
    return {
      owner: fixtures.clone(info.owner),
      runner: info.runner ? fixtures.clone(info.runner) : null
    }
  }
  function taskNotFound() {
    return new ServiceError('business', '没有这个演示任务。', {
      businessCode: 'MOCK_TASK_NOT_FOUND', outcome: 'rejected'
    })
  }
  function waitAndMaybeFail(operation) {
    const scenario = scenarios.get()
    if (scenario.delayMs > 0) {
      return new Promise(resolve => setTimeout(resolve, scenario.delayMs)).then(() => checkFailure(operation))
    }
    try { return Promise.resolve(checkFailure(operation)) } catch (error) { return Promise.reject(error) }
  }
  function checkFailure(operation) {
    if (scenarios.get().failure) {
      throw new ServiceError('network', 'Mock场景模拟了请求失败，可切回正常样例后重试。', {
        requestId: 'mock-' + operation, outcome: 'not_sent'
      })
    }
    return true
  }
  function run(operation, action) {
    return waitAndMaybeFail(operation).then(action)
  }
  function taskDetail(task, userId) {
    const detail = publicTask(task)
    const role = relation(task, userId)
    detail.relation = role || 'guest'
    detail.privateInfo = privateFor(task, userId)
    detail.closeReason = task.closeReason || ''
    detail.settlementStatus = task.settlementStatus
    return detail
  }
  function applyPendingScenario() {
    if (scenarios.current().key !== 'pending') return
    const task = findTask('demo-001')
    if (task && task.status === '待接单') {
      task.status = '待确认'
      task.runnerId = 'mock-b'
      task.settlementStatus = '待模拟结算'
      task.privateInfo.runner = { phone: '000-TEST-B', wechatId: 'mock_b', note: '仅演示的接单方说明 B' }
    }
  }
  function reset(options) {
    state = fixtures.createInitialState()
    if (options && options.scenario) scenarios.set(options.scenario)
    else scenarios.reset()
    applyPendingScenario()
    return snapshot()
  }
  function snapshot() {
    return {
      accounts: fixtures.clone(state.accounts),
      tasks: state.tasks.map(task => taskDetail(task, null)),
      scenario: scenarios.current()
    }
  }

  const tasks = {
    listPublic(options) {
      const userId = options && options.userId
      return run('list', () => {
        applyPendingScenario()
        return state.tasks.filter(task => task.status === '待接单').map(publicTask)
      })
    },
    getPublic(taskId, options) {
      const userId = options && options.userId
      return run('detail', () => {
        applyPendingScenario()
        const task = findTask(taskId)
        if (!task) throw taskNotFound()
        return taskDetail(task, userId)
      })
    },
    listMine(userId, relationship) {
      return run('mine', () => {
        requireAccount(userId)
        const mine = state.tasks.filter(task => relationship === 'published'
          ? task.ownerId === userId
          : relationship === 'accepted'
            ? task.runnerId === userId
            : task.ownerId === userId || task.runnerId === userId)
        return mine.map(task => taskDetail(task, userId))
      })
    },
    publish(draft, userId) {
      return run('publish', () => {
        requireAccount(userId)
        const value = draft || {}
        if (!String(value.title || '').trim() || !String(value.typeName || '').trim() ||
          !String(value.pickupLocation || '').trim() || !String(value.deliveryLocation || '').trim() ||
          !String(value.phone || '').trim()) {
          throw new ServiceError('validation', '演示发布需要填写标题、类型、地点和本次手机号。')
        }
        if (!fixtures.taskTypes.includes(value.typeName)) throw new ServiceError('validation', '请选择演示任务类型。')
        const id = 'mock-new-' + String(state.nextTaskNumber++).padStart(3, '0')
        const task = {
          id, title: String(value.title).trim(), typeName: value.typeName,
          pickupLocation: String(value.pickupLocation).trim(), deliveryLocation: String(value.deliveryLocation).trim(),
          rewardText: String(value.rewardText || '0.00'), deadlineText: String(value.deadlineText || '演示时间'),
          description: String(value.description || ''), ownerId: userId, runnerId: null,
          status: '待接单', settlementStatus: '未开始',
          privateInfo: {
            owner: { phone: String(value.phone).trim(), wechatId: String(value.wechatId || '').trim(), note: String(value.privateNote || '').trim() },
            runner: null
          }
        }
        state.tasks.unshift(task)
        return taskDetail(task, userId)
      })
    },
    accept(taskId, userId, contact) {
      return run('accept', () => {
        requireAccount(userId)
        const task = findTask(taskId)
        if (!task) throw taskNotFound()
        if (task.ownerId === userId) throw new ServiceError('business', '不能接自己发布的演示任务。', { businessCode: 'MOCK_SELF_ACCEPT', outcome: 'rejected' })
        if (task.status !== '待接单') throw new ServiceError('business', '该演示任务当前不可接单。', { businessCode: 'MOCK_TASK_NOT_OPEN', outcome: 'rejected' })
        if (!contact || !String(contact.phone || '').trim()) throw new ServiceError('validation', '接单需要填写本次手机号。')
        task.runnerId = userId
        task.privateInfo.runner = {
          phone: String(contact.phone).trim(), wechatId: String(contact.wechatId || '').trim(),
          note: String(contact.privateNote || '').trim()
        }
        task.status = '已接单'
        return taskDetail(task, userId)
      })
    },
    cancel(taskId, userId) {
      return run('cancel', () => {
        requireAccount(userId)
        const task = findTask(taskId)
        if (!task) throw taskNotFound()
        if (task.ownerId !== userId || task.status !== '待接单') {
          throw new ServiceError('business', '当前演示任务不可取消。', { businessCode: 'MOCK_CANCEL_NOT_ALLOWED', outcome: 'rejected' })
        }
        task.status = '已取消'
        task.closeReason = '发布者取消（Mock操作）'
        return taskDetail(task, userId)
      })
    },
    markPickedUp(taskId, userId) {
      return run('pickup', () => {
        requireAccount(userId)
        const task = findTask(taskId)
        if (!task) throw taskNotFound()
        if (task.runnerId !== userId || task.status !== '已接单') {
          throw new ServiceError('business', '只有当前接单账号能确认取件。', { businessCode: 'MOCK_PICKUP_NOT_ALLOWED', outcome: 'rejected' })
        }
        task.status = '已取件'
        return taskDetail(task, userId)
      })
    },
    markDelivered(taskId, userId) {
      return run('delivered', () => {
        requireAccount(userId)
        const task = findTask(taskId)
        if (!task) throw taskNotFound()
        if (task.runnerId !== userId || task.status !== '已取件') {
          throw new ServiceError('business', '当前演示任务不能标记送达。', { businessCode: 'MOCK_DELIVERY_NOT_ALLOWED', outcome: 'rejected' })
        }
        task.status = '待确认'
        task.settlementStatus = '待模拟结算'
        return taskDetail(task, userId)
      })
    },
    confirmCompletion(taskId, userId) {
      return run('complete', () => {
        requireAccount(userId)
        const task = findTask(taskId)
        if (!task) throw taskNotFound()
        if (task.ownerId !== userId || task.status !== '待确认') {
          throw new ServiceError('business', '当前账号不能确认该演示任务。', { businessCode: 'MOCK_CONFIRM_NOT_ALLOWED', outcome: 'rejected' })
        }
        task.status = '已完成'
        task.settlementStatus = '模拟结算完成'
        return taskDetail(task, userId)
      })
    },
    getDeliveryCode(taskId, userId) {
      return run('delivery-code', () => {
        requireAccount(userId)
        const task = findTask(taskId)
        if (!task) throw taskNotFound()
        if (task.runnerId !== userId || !ACTIVE_STATUSES.includes(task.status)) {
          throw new ServiceError('business', '当前账号无权查看演示交付码。', { businessCode: 'MOCK_CODE_NOT_ALLOWED', outcome: 'rejected' })
        }
        const expired = scenarios.get().codeExpired || !task.deliveryCode || Date.parse(task.deliveryCode.expiresAt) <= Date.now()
        if (expired) throw new ServiceError('business', '演示交付码已过期，请重新打开演示任务。', { businessCode: 'MOCK_CODE_EXPIRED', outcome: 'rejected' })
        return { taskId, code: task.deliveryCode.value, expiresAt: task.deliveryCode.expiresAt, isDemo: true }
      })
    }
  }

  return { tasks, scenarios, reset, snapshot }
}

const mockService = createMockService()
module.exports = Object.assign({ createMockService }, mockService)
