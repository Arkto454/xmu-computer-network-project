const { ServiceError } = require('./errors')
const { createSessionRequest } = require('./session-request')
const { decodeResponse } = require('./real-auth')
const { validId } = require('../utils/pending-claims')
function protocol(write) { return new ServiceError('protocol', '交付响应无法识别，请重新查询任务和当前交付码。', { outcome: write ? 'unknown' : 'rejected' }) }
function codeView(value, taskId, write) {
  if (!value || !Number.isSafeInteger(value.taskId) || String(value.taskId) !== taskId ||
    !Number.isSafeInteger(value.codeVersion) || value.codeVersion < 0 || !['ACTIVE', 'EXPIRED'].includes(value.codeStatus)) throw protocol(write)
  const result = { taskId, codeVersion: value.codeVersion, codeStatus: value.codeStatus, expiresAt: null, imageSrc: '' }
  if (value.codeStatus === 'ACTIVE') {
    if (typeof value.expiresAt !== 'string' || !/^\d{4}-\d\d-\d\dT.*Z$/.test(value.expiresAt) || !Number.isFinite(Date.parse(value.expiresAt)) ||
      typeof value.imageBase64 !== 'string' || value.imageBase64.length > 524288 || value.imageBase64.length % 4 !== 0 ||
      !/^iVBORw0KGgo[A-Za-z0-9+/]*={0,2}$/.test(value.imageBase64)) throw protocol(write)
    result.expiresAt = Date.parse(value.expiresAt)
    result.imageSrc = 'data:image/png;base64,' + value.imageBase64
  }
  return result
}
function createDeliveryService(options) {
  const config = options || {}, store = config.session
  const request = createSessionRequest({ ...config, decodeResponse })
  const messages = { TASK_ACTOR_FORBIDDEN: '仅本任务接单者可以管理交付码。', TASK_NOT_FOUND: '任务不存在或暂不可访问。',
    TASK_STATE_CONFLICT: '任务进度已变化，请先查询最新详情。', DELIVERY_PAUSED: '取消申请正在审核，交付已暂停。',
    DELIVERY_CODE_CHANGED: '交付码已被更新，请先读取当前版本再操作。', INVALID_REQUEST: '交付请求未通过校验，请重新查询。' }
  function operation(taskId, userId, action, version) {
    try {
      const captured = store && store.snapshot()
      if (!store || store.mode !== 'real' || !captured.authenticated) throw new ServiceError('auth', '请先使用微信登录。')
      if (captured.userId !== userId || !validId(taskId)) throw new ServiceError('validation', '任务编号或当前身份无效。')
      if (['refresh', 'revoke'].includes(action) && (!Number.isSafeInteger(version) || version < 0)) throw new ServiceError('validation', '请先读取当前交付码版本。')
    } catch (error) { return Promise.reject(error) }
    const write = action !== 'read'
    const suffix = { pickup: '/pickup', request: '/delivery', read: '/delivery/code', refresh: '/delivery/code/refresh', revoke: '/delivery/code/revoke' }[action]
    const input = { path: '/api/student/tasks/' + taskId + suffix, method: write ? 'POST' : 'GET', auth: true }
    if (['refresh', 'revoke'].includes(action)) { input.header = { 'content-type': 'application/json' }; input.data = { codeVersion: version } }
    return request(input).then(result => {
      if (result.httpStatus !== 200 || result.businessCode !== 'OK') throw protocol(write)
      if (action !== 'pickup') return codeView(result.data, taskId, write)
      const v = result.data
      if (!v || !Number.isSafeInteger(v.taskId) || String(v.taskId) !== taskId || v.taskStatus !== 'PICKED_UP' ||
        typeof v.pickedUpAt !== 'string' || !/Z$/.test(v.pickedUpAt) || !Number.isFinite(Date.parse(v.pickedUpAt))) throw protocol(true)
      return { taskId, status: '已取件', pickedUpAt: v.pickedUpAt }
    }).catch(error => {
      if (error && Object.prototype.hasOwnProperty.call(messages, error.businessCode)) throw new ServiceError(error.kind, messages[error.businessCode], error)
      throw error
    })
  }
  return {
    pickup: (id, user) => operation(id, user, 'pickup'),
    request: (id, user) => operation(id, user, 'request'),
    read: (id, user) => operation(id, user, 'read'),
    refresh: (id, user, version) => operation(id, user, 'refresh', version),
    revoke: (id, user, version) => operation(id, user, 'revoke', version)
  }
}
module.exports = { createDeliveryService, codeView }
