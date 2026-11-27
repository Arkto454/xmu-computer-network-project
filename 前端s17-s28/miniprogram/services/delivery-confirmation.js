const { ServiceError } = require('./errors')
const { createSessionRequest } = require('./session-request')
const { decodeResponse } = require('./real-auth')
const { validId } = require('../utils/pending-claims')
const { normalizeMoney, exceedsMoney } = require('../utils/money')
function validScannedPayload(value, taskId) {
  return validId(taskId) && typeof value === 'string' && value.length <= 100 &&
    value.startsWith('CED1.' + taskId + '.') && /^[A-Za-z0-9_-]{43}$/.test(value.slice(('CED1.' + taskId + '.').length))
}
function protocol(write) { return new ServiceError('protocol', '交付核对响应无法识别，请重新查询任务。', { outcome: write ? 'unknown' : 'rejected' }) }
function instant(value, write) {
  if (typeof value !== 'string' || !/^\d{4}-\d\d-\d\dT.*Z$/.test(value) || !Number.isFinite(Date.parse(value))) throw protocol(write)
  return Date.parse(value)
}
function money(value, write) {
  const result = typeof value === 'number' && Number.isFinite(value) ? normalizeMoney(String(value)) : null
  if (!result || exceedsMoney(result, '99999999.99')) throw protocol(write)
  return result
}
function createDeliveryConfirmationService(options) {
  const config = options || {}, store = config.session, request = createSessionRequest({ ...config, decodeResponse })
  const messages = {
    TASK_ACTOR_FORBIDDEN: '仅本任务发布者可核对并确认交付。', TASK_NOT_FOUND: '任务不存在或暂不可访问。',
    TASK_STATE_CONFLICT: '任务进度已变化，请查询最新详情。', DELIVERY_PAUSED: '取消申请正在审核，交付已暂停。',
    DELIVERY_CODE_INVALID: '请扫描当前任务的交付码。', DELIVERY_CODE_EXPIRED: '交付码已过期、更新或停用，请让接单者展示当前有效码。',
    INVALID_REQUEST: '扫码内容未通过校验，请扫描当前任务交付码。'
  }
  function operation(taskId, userId, payload, action) {
    try {
      const captured = store && store.snapshot()
      if (!store || store.mode !== 'real' || !captured.authenticated) throw new ServiceError('auth', '请先使用微信登录。')
      if (captured.userId !== userId || !validScannedPayload(payload, taskId)) throw new ServiceError('validation', '请扫描当前任务的交付码。')
    } catch (error) { return Promise.reject(error) }
    const write = action === 'confirm'
    return request({ path: '/api/student/tasks/' + taskId + '/delivery/' + action, method: 'POST', auth: true,
      header: { 'content-type': 'application/json' }, data: { payload } }).then(result => {
      if (result.httpStatus !== 200 || result.businessCode !== 'OK') throw protocol(write)
      const v = result.data
      if (!v || !Number.isSafeInteger(v.taskId) || String(v.taskId) !== taskId) throw protocol(write)
      if (!write) {
        if (!['PENDING_CONFIRMATION', 'COMPLETED'].includes(v.taskStatus) || typeof v.title !== 'string' || !v.title.trim() || v.title.length > 100) throw protocol(false)
        return { taskId, title: v.title, rewardText: money(v.rewardAmount, false), status: v.taskStatus === 'COMPLETED' ? '已完成' : '待确认',
          expiresAt: v.taskStatus === 'COMPLETED' ? null : instant(v.expiresAt, false) }
      }
      if (v.taskStatus !== 'COMPLETED' || !v.settlement) throw protocol(true)
      const completedAt = instant(v.completedAt, true), settledAt = instant(v.settlement.settledAt, true)
      if (completedAt !== settledAt) throw protocol(true)
      return { taskId, status: '已完成', completedAt, settlement: { amountText: money(v.settlement.amount, true), settledAt } }
    }).catch(error => {
      const message = messages[error && error.businessCode] || (error && error.kind === 'auth' ? '登录已失效，请重新登录。' :
        write && error && error.outcome === 'unknown' ? '确认结果待查询，请勿连续提交。' : '交付核对未完成，请查询任务后重试。')
      throw new ServiceError(error && error.kind || 'protocol', message, error)
    })
  }
  return { verify: (id, user, value) => operation(id, user, value, 'verify'), confirm: (id, user, value) => operation(id, user, value, 'confirm') }
}
module.exports = { createDeliveryConfirmationService, validScannedPayload }
