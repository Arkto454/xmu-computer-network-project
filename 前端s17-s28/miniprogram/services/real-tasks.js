const { ServiceError } = require('./errors')
const { createSessionRequest } = require('./session-request')
const { decodeResponse } = require('./real-auth')
const { realTaskTypeOptions } = require('../config/task-types')
const { normalizeMoney, exceedsMoney } = require('../utils/money')
const { createDeliveryService } = require('./delivery')
const { createDeliveryConfirmationService } = require('./delivery-confirmation')
const { createSettlementService } = require('./settlement')
const STATUS = Object.freeze({ WAITING: '待接单', ACCEPTED: '已接单', PICKED_UP: '已取件', PENDING_CONFIRMATION: '待确认', COMPLETED: '已完成', CANCELLED: '已取消', EXPIRED: '已过期', CLOSED: '已关闭' })
const STATUS_CODE = Object.freeze(Object.keys(STATUS).reduce((result, key) => { result[STATUS[key]] = key; return result }, {}))
const ENDED = ['COMPLETED', 'CANCELLED', 'EXPIRED', 'CLOSED']
const knownStatus = value => Object.prototype.hasOwnProperty.call(STATUS, value)
function protocol(write) { return new ServiceError('protocol', write ? '发布结果尚未确认，请勿重复发布。' : '任务响应格式无法识别，请刷新或联系项目维护者。', { outcome: write ? 'unknown' : 'rejected' }) }
function validId(id) { return typeof id === 'string' && /^[1-9]\d{0,15}$/.test(id) && Number.isSafeInteger(Number(id)) }
function idText(id) { if (!Number.isSafeInteger(id) || id < 1) throw protocol(); return String(id) }
function text(value, max, nullable) {
  if (nullable && value === null) return ''
  if (typeof value !== 'string' || value.length > max) throw protocol()
  return value
}
function timeText(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d\d-\d\dT.*(?:Z|[+-]\d\d:\d\d)$/.test(value) || !Number.isFinite(Date.parse(value))) throw protocol()
  const d = new Date(value), pad = n => String(n).padStart(2, '0')
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes())
}
function summary(value) {
  if (!value || typeof value !== 'object') throw protocol()
  const money = typeof value.rewardAmount === 'number' && Number.isFinite(value.rewardAmount) ? normalizeMoney(String(value.rewardAmount)) : null
  if (!money || exceedsMoney(money, '99999999.99') || typeof value.type !== 'string' || typeof value.status !== 'string') throw protocol()
  const type = realTaskTypeOptions.find(item => item.code === value.type)
  return {
    id: idText(value.id), title: text(value.title, 100), typeName: type ? type.label : '未知类型',
    pickupLocation: text(value.pickupLocation, 200), deliveryLocation: text(value.deliveryLocation, 200),
    deadlineAt: value.deadlineAt, deadlineText: timeText(value.deadlineAt), rewardText: money,
    status: knownStatus(value.status) ? STATUS[value.status] : '状态待确认', statusText: knownStatus(value.status) ? STATUS[value.status] : '状态待确认',
    publishTimeText: timeText(value.createdAt)
  }
}
function payload(draft, now) {
  const d = draft || {}, fail = () => { throw new ServiceError('validation', '发布内容未通过校验，请检查表单。') }
  const fields = { title: 100, pickupLocation: 200, deliveryLocation: 200, description: 1000, phone: 16, wechatId: 64, privateNote: 1000 }
  Object.keys(fields).forEach(key => { if (typeof d[key] !== 'string' || d[key].length > fields[key]) fail() })
  if (['title', 'pickupLocation', 'deliveryLocation'].some(key => !d[key].trim()) || !realTaskTypeOptions.some(type => type.code === d.typeCode) || !/^\+?[1-9][0-9]{6,14}$/.test(d.phone)) fail()
  const reward = normalizeMoney(d.rewardText)
  if (!reward || exceedsMoney(reward, '99999999.99') || typeof d.deadlineAt !== 'string' || !/(?:Z|[+-]\d\d:\d\d)$/.test(d.deadlineAt) || !Number.isFinite(Date.parse(d.deadlineAt)) || Date.parse(d.deadlineAt) <= now) fail()
  // 金额作为十进制字符串交给后端BigDecimal，不在客户端做金额运算。
  return { title: d.title.trim(), type: d.typeCode, pickupLocation: d.pickupLocation.trim(), deliveryLocation: d.deliveryLocation.trim(), deadlineAt: d.deadlineAt,
    rewardAmount: reward, description: d.description.trim(), publisherPhone: d.phone, publisherWechat: d.wechatId.trim(), privateInstructions: d.privateNote.trim() }
}
function taskError(error) {
  const messages = {
    INVALID_REQUEST: '任务内容或查询条件未通过校验，请检查后重试。',
    TASK_NOT_FOUND: '任务不存在或暂不可访问，请返回大厅。',
    UNAUTHORIZED: '登录已失效，请重新登录。',
    TASK_EXPIRED: '任务已过期，不能再取消。',
    TASK_STATE_CONFLICT: '任务状态已变化，请刷新详情后再操作。',
    CLAIM_REQUEST_MISMATCH: '原接单请求与当前任务或联系方式不一致，已停止重试。',
    CLAIM_ALREADY_PENDING: '这条任务已有待确认的接单请求，请继续确认原请求。',
    TASK_TAKEN: '任务已被其他同学接走。',
    TASK_CANCELLED: '发布者已取消任务。',
    OWN_TASK: '不能接自己发布的任务。',
    NOT_ELIGIBLE: '当前账号暂不能接这条任务。'
  }
  if (error && Object.prototype.hasOwnProperty.call(messages, error.businessCode)) return new ServiceError(error.kind, messages[error.businessCode], { httpStatus: error.httpStatus, businessCode: error.businessCode, outcome: error.outcome })
  return error instanceof ServiceError ? error : protocol()
}
function createRealTaskService(options) {
  const config = options || {}, store = config.session, now = config.now || (() => Date.now())
  const delivery = createDeliveryService(config)
  const confirmation = createDeliveryConfirmationService(config)
  const settlement = createSettlementService(config)
  const request = createSessionRequest({ session: store, baseUrl: config.baseUrl, send: config.send, decodeResponse })
  const claimRequest = createSessionRequest({ session: store, baseUrl: config.baseUrl, send: config.send, decodeResponse: response => {
    const decoded = decodeResponse(response)
    return decoded && decoded.code === 'CLAIM_PENDING' ? Object.assign({}, decoded, { ok: true }) : decoded
  } })
  // 仅保存当前运行内的编号与结果状态，不保存表单、联系方式或凭据。
  // 无后端幂等/找回协议，未知发布结果不能通过重开页面绕过。
  const publications = new Map()
  function identity() {
    if (!store || store.mode !== 'real') throw new ServiceError('configuration', '真实任务只能使用真实登录会话。')
    const captured = store.snapshot()
    if (!captured.authenticated || !validId(captured.userId)) throw new ServiceError('auth', '请先使用微信登录。')
    return captured
  }
  return {
    listEarnings: settlement.list,
    getPublishState() {
      const captured = store && store.snapshot()
      const state = captured && captured.authenticated ? publications.get(captured.userId) : null
      return state ? Object.assign({}, state) : null
    },
    startNewPublish() {
      const captured = identity(), state = publications.get(captured.userId)
      if (state && state.kind !== 'created') throw new ServiceError('busy', '前一次发布尚未确认，请勿重复提交。', { outcome: 'unknown' })
      publications.delete(captured.userId)
    },
    listPublic(query) {
      let q, size, page
      try {
        identity(); q = query || {}; size = q.pageSize == null ? 4 : q.pageSize
        if (!Number.isInteger(size) || size < 1 || size > 100 || q.sortOrder && q.sortOrder !== 'newest') throw new ServiceError('validation', '真实大厅仅支持最新发布排序。')
        page = q.cursor == null ? 1 : /^page-[1-9]\d*$/.test(q.cursor) ? Number(q.cursor.slice(5)) : NaN
        if (!Number.isInteger(page) || page < 1 || page > 2147483647) throw new ServiceError('validation', '分页条件无效，请刷新列表。')
        if (q.typeName && !realTaskTypeOptions.some(item => item.label === q.typeName)) throw new ServiceError('validation', '任务类型无法识别。')
      } catch (e) { return Promise.reject(e) }
      const data = { page, size }, type = realTaskTypeOptions.find(item => item.label === q.typeName)
      if (type) data.type = type.code
      return request({ path: '/api/student/tasks', auth: true, data }).then(result => {
        const value = result.data
        if (!value || !Array.isArray(value.items) || value.items.length > size || value.page !== page || value.size !== size || !Number.isSafeInteger(value.total) || value.total < 0) throw protocol()
        const items = value.items.map(summary), hasMore = items.length > 0 && page * size < value.total && page < 2147483647
        return { items, hasMore, nextCursor: hasMore ? 'page-' + (page + 1) : null }
      }).catch(error => { throw taskError(error) })
    },
    getPublic(taskId) {
      let captured
      try { captured = identity(); if (!validId(taskId)) throw new ServiceError('validation', '任务编号无效，请从大厅重新打开。') } catch (e) { return Promise.reject(e) }
      return request({ path: '/api/student/tasks/' + taskId, auth: true }).then(result => {
        const v = result.data, task = summary(v)
        if (task.id !== taskId || typeof v.ownedByMe !== 'boolean' || v.ownedByMe !== (idText(v.publisherId) === captured.userId) ||
          v.acceptedByMe != null && typeof v.acceptedByMe !== 'boolean' || v.ownedByMe && v.acceptedByMe) throw protocol()
        const role = v.ownedByMe ? 'owner' : v.acceptedByMe === true ? 'runner' : 'guest'
        let deliveryPaused = false
        if (v.cancellation != null) {
          if (typeof v.cancellation.deliveryPaused !== 'boolean' || typeof v.cancellation.status !== 'string') throw protocol()
          const cancellationStates = ['AWAITING_PEER','AWAITING_REVIEW','WITHDRAWN','DECLINED','CANCELLED','APPROVED','REJECTED','TASK_FINISHED']
          deliveryPaused = v.cancellation.deliveryPaused || v.cancellation.status === 'AWAITING_REVIEW' || !cancellationStates.includes(v.cancellation.status)
        }
        const allowedActions = task.status === STATUS.WAITING ? (role === 'owner' ? ['cancel'] : role === 'guest' ? ['accept'] : []) :
          role === 'runner' ? (task.status === STATUS.ACCEPTED ? ['pickup'] : task.status === STATUS.PICKED_UP && !deliveryPaused ? ['deliver'] : task.status === STATUS.PENDING_CONFIRMATION ? ['code'] : []) :
          role === 'owner' && task.status === STATUS.PENDING_CONFIRMATION && !deliveryPaused ? ['scan'] : []
        Object.assign(task, { description: text(v.description, 1000, true), relation: role, allowedActions,
          ownerLabel: v.ownedByMe ? '我' : '其他同学', runnerLabel: role === 'runner' ? '我' : '暂未提供接单信息',
          deliveryPaused, phaseRecords: [], privateInfo: null, overdue: task.status === STATUS.EXPIRED })
        // 详情只提供审核摘要。关闭原因另由参与方读取审核记录，不从公开字段推断。
        if (v.status === 'CLOSED' && role !== 'guest') {
          task.closureId = v.cancellation && v.cancellation.status === 'APPROVED' && Number.isSafeInteger(v.cancellation.cancellationId) && v.cancellation.cancellationId > 0 ? String(v.cancellation.cancellationId) : ''
          task.closureMessage = task.closureId ? '正在读取关闭审核记录。' : '暂无可用的关闭审核记录，请刷新详情或联系管理员。'
          task.settlementStatus = '任务已关闭，不计入模拟收益。'
        }
        if (v.pickedUpAt != null) task.phaseRecords.push({ label: '已取件', timeText: timeText(v.pickedUpAt) })
        if (v.completedAt != null) task.phaseRecords.push({ label: '已完成', timeText: timeText(v.completedAt) })
        if (role !== 'guest' && v.status === 'COMPLETED' && v.settlement != null) {
          const s = v.settlement, settledMoney = typeof s.amount === 'number' && Number.isFinite(s.amount) ? normalizeMoney(String(s.amount)) : null
          if (!settledMoney || settledMoney !== task.rewardText || typeof v.completedAt !== 'string' || Date.parse(s.settledAt) !== Date.parse(v.completedAt)) throw protocol()
          task.settlementStatus = '已模拟结算 ¥' + settledMoney + ' · ' + timeText(s.settledAt) + '（无真实支付）'
        }
        // 仅映射服务端按关系授权的交接对象；访客和终态始终隐藏。
        if (role !== 'guest' && knownStatus(v.status) && ENDED.indexOf(v.status) === -1 && v.privateInfo != null) {
          const p = v.privateInfo
          task.privateInfo = { owner: { phone: text(p.publisherPhone, 16), wechatId: text(p.publisherWechat, 64, true), note: text(p.privateInstructions, 1000, true) } }
          if (p.runnerPhone != null) task.privateInfo.runner = { phone: text(p.runnerPhone, 16), wechatId: p.runnerWechat == null ? '' : text(p.runnerWechat, 64), note: '' }
        }
        return task
      }).catch(error => { throw taskError(error) })
    },
    getClosure(taskId, cancellationId) {
      try {
        identity()
        if (!validId(taskId) || !validId(cancellationId)) throw new ServiceError('validation', '关闭审核编号无效，请刷新详情。')
      } catch (error) { return Promise.reject(error) }
      return request({ path: '/api/student/tasks/' + taskId + '/cancellations/latest', auth: true }).then(result => {
        const v = result.data
        if (!v || idText(v.taskId) !== taskId || idText(v.cancellationId) !== cancellationId || v.taskStatus !== 'CLOSED' || v.status !== 'APPROVED' ||
          typeof v.reviewReason !== 'string' || !v.reviewReason.trim() || v.reviewReason.length > 500) throw protocol()
        return { closeReason: v.reviewReason, closedAtText: timeText(v.reviewedAt) }
      }).catch(error => { throw taskError(error) })
    },
    listMine(userId, relationship, query) {
      let captured, q, size, page, status
      try {
        captured = identity(); q = query || {}
        if (userId !== captured.userId || !['published', 'accepted'].includes(relationship)) throw new ServiceError('validation', '任务关系与当前账号不匹配。')
        size = q.pageSize == null ? 4 : q.pageSize
        if (!Number.isInteger(size) || size < 1 || size > 100) throw new ServiceError('validation', '分页条件无效，请刷新列表。')
        page = q.cursor == null ? 1 : /^page-[1-9]\d*$/.test(q.cursor) ? Number(q.cursor.slice(5)) : NaN
        if (!Number.isInteger(page) || page < 1 || page > 2147483647) throw new ServiceError('validation', '分页条件无效，请刷新列表。')
        if (q.status) {
          if (!Object.prototype.hasOwnProperty.call(STATUS_CODE, q.status)) throw new ServiceError('validation', '任务状态无法识别。')
          status = STATUS_CODE[q.status]
        }
      } catch (error) { return Promise.reject(error) }
      const data = { page, size }
      if (status) data.status = status
      const path = relationship === 'published' ? '/api/student/tasks/mine' : '/api/student/tasks/accepted'
      return request({ path, auth: true, data }).then(result => {
        const value = result.data
        if (!value || !Array.isArray(value.items) || value.items.length > size || value.page !== page || value.size !== size || !Number.isSafeInteger(value.total) || value.total < 0) throw protocol()
        const items = value.items.map(summary), hasMore = items.length > 0 && page * size < value.total && page < 2147483647
        return { items, hasMore, nextCursor: hasMore ? 'page-' + (page + 1) : null }
      }).catch(error => { throw taskError(error) })
    },
    publish(draft) {
      let captured, data
      try {
        captured = identity()
        const previous = publications.get(captured.userId)
        if (previous) throw new ServiceError('busy', previous.kind === 'created' ? '任务已经发布，请打开原任务。' : '前一次发布尚未确认，请勿重复提交。', { outcome: previous.kind === 'created' ? 'rejected' : 'unknown' })
        data = payload(draft, now())
      } catch (e) { return Promise.reject(e) }
      publications.set(captured.userId, { kind: 'pending' })
      return request({ path: '/api/student/tasks', method: 'POST', auth: true, header: { 'content-type': 'application/json' }, data }).then(result => {
        const value = result.data
        if (!value || !Number.isSafeInteger(value.id) || value.id < 1 || value.status !== 'WAITING') throw protocol(true)
        const receipt = { id: String(value.id), status: '待接单' }
        // 成功回执保留在当前运行中，页面重开也可回到原编号。
        publications.set(captured.userId, { kind: 'created', id: receipt.id })
        return receipt
      }).catch(error => {
        if (error && (error.outcome === 'not_sent' || error.outcome === 'rejected')) publications.delete(captured.userId)
        else publications.set(captured.userId, { kind: 'unknown' })
        throw taskError(error)
      })
    },
    cancel(taskId, userId) {
      try {
        const captured = identity()
        if (captured.userId !== userId || !validId(taskId)) throw new ServiceError('validation', '任务编号或当前身份无效。')
      } catch (error) { return Promise.reject(error) }
      return request({ path: '/api/student/tasks/' + taskId + '/cancel', method: 'POST', auth: true,
        header: { 'content-type': 'application/json' }, data: {} }).then(result => {
        const value = result.data
        if (!value || !Number.isSafeInteger(value.id) || value.id < 1 || String(value.id) !== taskId || value.status !== 'CANCELLED') throw protocol(true)
        return { id: taskId, status: STATUS.CANCELLED }
      }).catch(error => { throw taskError(error) })
    },
    accept(taskId, userId, contact, requestId) {
      let captured, value
      try {
        captured = identity()
        const c = contact || {}
        if (captured.userId !== userId || !validId(taskId) || !c || typeof c.phone !== 'string' || !/^\+?[1-9][0-9]{6,14}$/.test(c.phone) ||
          typeof c.wechatId !== 'string' || c.wechatId.length > 64 || c.phone !== c.phone.trim() || c.wechatId !== c.wechatId.trim()) {
          throw new ServiceError('validation', '接单手机号或微信号格式不符合要求。')
        }
        if (typeof requestId !== 'string' || !/^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$/.test(requestId)) throw new ServiceError('validation', '接单请求编号无效。')
        value = { requestId, runnerPhone: c.phone, runnerWechat: c.wechatId || '' }
      } catch (error) { return Promise.reject(error) }
      return claimRequest({ path: '/api/student/tasks/' + taskId + '/claim', method: 'POST', auth: true,
        header: { 'content-type': 'application/json' }, data: value }).then(result => {
        const body = result.data, status = result.httpStatus
        const outcomes = ['PENDING', 'ACCEPTED', 'ALREADY_ACCEPTED']
        if (!body || !Number.isSafeInteger(body.taskId) || body.taskId < 1 || String(body.taskId) !== taskId || body.requestId !== requestId || !outcomes.includes(body.outcome) ||
          status === 202 && (body.outcome !== 'PENDING' || result.businessCode !== 'CLAIM_PENDING') ||
          status === 200 && (!['ACCEPTED', 'ALREADY_ACCEPTED'].includes(body.outcome) || result.businessCode !== 'OK') ||
          status !== 200 && status !== 202) throw protocol(true)
        return { taskId, requestId, outcome: body.outcome }
      }).catch(error => { throw taskError(error) })
    },
    markPickedUp: delivery.pickup, markDelivered: delivery.request, getDeliveryCode: delivery.read,
    refreshDeliveryCode: delivery.refresh, revokeDeliveryCode: delivery.revoke,
    verifyDeliveryCode: confirmation.verify, confirmCompletion: confirmation.confirm
  }
}
module.exports = { createRealTaskService, validId }
