const { ServiceError } = require('../services/errors')
const LEGACY_KEY = 'campus-errand.pending-claims.v1'
const DEFAULT_SCOPE = 'real:http://127.0.0.1:18080'
const validId = value => typeof value === 'string' && /^[1-9]\d{0,15}$/.test(value) && Number.isSafeInteger(Number(value))
const validRequestId = value => typeof value === 'string' && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value)
function storageError() { return new ServiceError('storage', '待确认接单记录无法读取或保存，请检查设备存储后重试。为保留原申请，本次未发送请求。', { outcome: 'not_sent' }) }
function copy(record) { return { requestId: record.requestId, contact: { phone: record.contact.phone, wechatId: record.contact.wechatId }, status: record.status } }
function validRecord(record) {
  return !!record && validRequestId(record.requestId) && record.contact &&
    typeof record.contact.phone === 'string' && /^\+?[1-9][0-9]{6,14}$/.test(record.contact.phone) &&
    typeof record.contact.wechatId === 'string' && record.contact.wechatId.length <= 64 && record.contact.wechatId === record.contact.wechatId.trim() &&
    ['pending', 'unknown'].includes(record.status)
}
function parse(value) {
  if (value === '' || value === undefined || value === null) return {}
  if (typeof value !== 'object' || Array.isArray(value)) throw storageError()
  const rows = {}
  Object.keys(value).forEach(key => {
    const pair = key.split(':')
    if (pair.length !== 2 || !validId(pair[0]) || !validId(pair[1]) || !validRecord(value[key])) throw storageError()
    rows[key] = copy(value[key])
  })
  return rows
}
function createPendingClaims(options) {
  const config = options || {}, enabled = config.enabled !== false, scope = config.scope || DEFAULT_SCOPE
  const key = 'campus-errand.pending-claims.v2:' + encodeURIComponent(scope)
  let memory = {}
  const storage = config.storage || { get: name => wx.getStorageSync(name), set: (name, value) => wx.setStorageSync(name, value) }
  function read() {
    try {
      if (!enabled) return parse(memory)
      const current = storage.get(key)
      if (current !== '' && current !== undefined && current !== null) return parse(current)
      if (scope === DEFAULT_SCOPE) {
        const previous = parse(storage.get(LEGACY_KEY))
        storage.set(key, previous)
        storage.set(LEGACY_KEY, {})
        return previous
      }
      return {}
    } catch (_) { throw storageError() }
  }
  function write(rows) { try { if (enabled) storage.set(key, rows); else memory = parse(rows) } catch (_) { throw storageError() } }
  function composite(userId, taskId) {
    if (!validId(userId) || !validId(taskId)) throw new ServiceError('validation', '当前账号或任务编号无效。')
    return userId + ':' + taskId
  }
  return {
    list(userId) {
      if (!validId(userId)) throw new ServiceError('validation', '当前账号无效。')
      const rows = read(), prefix = userId + ':'
      return Object.keys(rows).filter(name => name.indexOf(prefix) === 0).map(name => ({ taskId: name.slice(prefix.length), ...copy(rows[name]) }))
    },
    get(userId, taskId) { return read()[composite(userId, taskId)] || null },
    put(userId, taskId, record) {
      if (!validRecord(record)) throw storageError()
      const rows = read(), name = composite(userId, taskId)
      if (rows[name] && rows[name].requestId !== record.requestId) throw new ServiceError('business', '此任务已有原接单申请，请先确认原请求。', { outcome: 'unknown' })
      rows[name] = copy(record)
      write(rows)
    },
    update(userId, taskId, record) {
      const rows = read(), name = composite(userId, taskId)
      // 同编号的较新确认已清除记录时，迟到响应不能重新创建记录。
      if (!rows[name] || rows[name].requestId !== record.requestId) return
      rows[name] = copy(record)
      write(rows)
    },
    remove(userId, taskId, requestId) {
      const rows = read(), name = composite(userId, taskId)
      if (rows[name] && rows[name].requestId === requestId) { delete rows[name]; write(rows) }
    }
  }
}
module.exports = { createPendingClaims, validId, validRequestId }
