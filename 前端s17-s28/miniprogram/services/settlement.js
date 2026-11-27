const { ServiceError } = require('./errors')
const { createSessionRequest } = require('./session-request')
const { decodeResponse } = require('./real-auth')
const { validId } = require('../utils/pending-claims')
function protocol() { return new ServiceError('protocol', '模拟结算数据暂时无法识别，请刷新后重试。') }
function amount(value, zero) {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0 || value > 999999999999.99) throw protocol()
  const text = String(value)
  if (!/^\d+(?:\.\d{1,2})?$/.test(text)) throw protocol()
  const [whole, fraction = ''] = text.split('.'), cents = Number(whole + (fraction + '00').slice(0, 2))
  if (!Number.isSafeInteger(cents) || (!zero && cents === 0)) throw protocol()
  return whole + '.' + (fraction + '00').slice(0, 2)
}
function cents(text) { return Number(text.replace('.', '')) }
function queryPage(query) {
  const q = query || {}, page = q.page == null ? 1 : q.page, size = q.size == null ? 4 : q.size
  if (!Number.isInteger(page) || page < 1 || page > 2147483647 || !Number.isInteger(size) || size < 1 || size > 100) throw new ServiceError('validation', '收益分页条件无效，请刷新。')
  return { page, size }
}
function settledTime(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d\d-\d\dT.*Z$/.test(value) || !Number.isFinite(Date.parse(value))) throw protocol()
  const d = new Date(value), pad = n => String(n).padStart(2, '0')
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes())
}
function earningsView(value, query, mock) {
  const q = queryPage(query)
  if (!value || !Array.isArray(value.items) || value.page !== q.page || value.size !== q.size || !Number.isSafeInteger(value.total) || value.total < 0 ||
    value.items.length !== Math.min(q.size, Math.max(0, value.total - (q.page - 1) * q.size))) throw protocol()
  const ids = new Set(), totalAmountText = amount(value.totalAmount, true)
  const items = value.items.map(row => {
    if (!row || !(mock ? typeof row.taskId === 'string' && /^[A-Za-z0-9_-]{1,80}$/.test(row.taskId) : Number.isSafeInteger(row.taskId) && row.taskId > 0) ||
      typeof row.title !== 'string' || !row.title.trim() || row.title.length > 100 || ids.has(String(row.taskId))) throw protocol()
    ids.add(String(row.taskId))
    return { taskId: String(row.taskId), title: row.title, amountText: amount(row.amount, false), settledAt: row.settledAt, settledText: settledTime(row.settledAt) }
  })
  const subtotal = items.reduce((sum, row) => sum + cents(row.amountText), 0)
  if (!Number.isSafeInteger(subtotal) || subtotal > cents(totalAmountText) || (value.total === 0) !== (totalAmountText === '0.00')) throw protocol()
  if (value.total === items.length && cents(totalAmountText) !== subtotal) throw protocol()
  const hasMore = q.page * q.size < value.total && q.page < 2147483647
  return { items, totalAmountText, total: value.total, page: q.page, size: q.size, hasMore, nextPage: hasMore ? q.page + 1 : null }
}
function createSettlementService(options) {
  const config = options || {}, store = config.session, request = createSessionRequest({ ...config, decodeResponse })
  return { list(userId, query) {
    let q
    try {
      const captured = store && store.snapshot()
      if (!store || store.mode !== 'real' || !captured.authenticated) throw new ServiceError('auth', '请先使用微信登录。')
      if (captured.userId !== userId || !validId(userId)) throw new ServiceError('validation', '当前账号无效，请重新登录。')
      q = queryPage(query)
    } catch (error) { return Promise.reject(error) }
    return request({ path: '/api/student/earnings', method: 'GET', auth: true, data: q }).then(result => {
      if (result.httpStatus !== 200 || result.businessCode !== 'OK') throw protocol()
      return earningsView(result.data, q, false)
    }).catch(error => { throw new ServiceError(error.kind || 'protocol', error.kind === 'auth' ? '登录已失效，请重新登录。' : '模拟收益暂时无法查询，请重试刷新。', error) })
  } }
}
module.exports = { createSettlementService, earningsView, queryPage }
