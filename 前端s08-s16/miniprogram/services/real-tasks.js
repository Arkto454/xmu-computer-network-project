const { ServiceError } = require('./errors')
const { createSessionRequest } = require('./session-request')
const { decodeResponse } = require('./real-auth')
const { realTaskTypeOptions } = require('../config/task-types')
const { normalizeMoney, exceedsMoney } = require('../utils/money')
const STATUS = Object.freeze({ WAITING: '待接单', ACCEPTED: '已接单', PICKED_UP: '已取件', PENDING_CONFIRMATION: '待确认', COMPLETED: '已完成', CANCELLED: '已取消', EXPIRED: '已过期', CLOSED: '已关闭' })
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
  const messages = { INVALID_REQUEST: '任务内容或查询条件未通过校验，请检查后重试。', TASK_NOT_FOUND: '任务不存在或暂不可访问，请返回大厅。', UNAUTHORIZED: '登录已失效，请重新登录。' }
  if (error && Object.prototype.hasOwnProperty.call(messages, error.businessCode)) return new ServiceError(error.kind, messages[error.businessCode], { httpStatus: error.httpStatus, businessCode: error.businessCode, outcome: error.outcome })
  return error instanceof ServiceError ? error : protocol()
}
function createRealTaskService(options) {
  const config = options || {}, store = config.session, now = config.now || (() => Date.now())
  const request = createSessionRequest({ session: store, baseUrl: config.baseUrl, send: config.send, decodeResponse })
  // 仅保存当前运行内的编号与结果状态，不保存表单、联系方式或凭据。
  // 无后端幂等/找回协议，未知发布结果不能通过重开页面绕过。
  const publications = new Map()
  function identity() {
    if (!store || store.mode !== 'real') throw new ServiceError('configuration', '真实任务只能使用真实登录会话。')
    const captured = store.snapshot()
    if (!captured.authenticated || !validId(captured.userId)) throw new ServiceError('auth', '请先使用微信登录。')
    return captured
  }
  function unavailable() { return Promise.reject(new ServiceError('configuration', '这项任务操作尚未接入，请等待后续开放。')) }
  return {
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
        if (task.id !== taskId || typeof v.ownedByMe !== 'boolean' || v.ownedByMe !== (idText(v.publisherId) === captured.userId)) throw protocol()
        Object.assign(task, { description: text(v.description, 1000, true), relation: v.ownedByMe ? 'owner' : 'guest', allowedActions: [],
          ownerLabel: v.ownedByMe ? '我' : '其他同学', runnerLabel: '暂未提供接单信息', phaseRecords: [], privateInfo: null, overdue: false })
        // 只转换后端实际授予本账号的私密对象。未知状态、结束态和其他账号一律隐藏。
        if (v.ownedByMe && knownStatus(v.status) && ENDED.indexOf(v.status) === -1 && v.privateInfo != null) {
          const p = v.privateInfo
          task.privateInfo = { owner: { phone: text(p.publisherPhone, 16), wechatId: text(p.publisherWechat, 64, true), note: text(p.privateInstructions, 1000, true) } }
        }
        return task
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
    listMine: unavailable, accept: unavailable, cancel: unavailable, markPickedUp: unavailable, markDelivered: unavailable, confirmCompletion: unavailable, getDeliveryCode: unavailable
  }
}
module.exports = { createRealTaskService, validId }
