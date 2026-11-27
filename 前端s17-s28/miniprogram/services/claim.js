const { ServiceError } = require('./errors')
const { createPendingClaims, validId } = require('../utils/pending-claims')

function uuid() {
  const bytes = Array.from({ length: 16 }, () => Math.floor(Math.random() * 256))
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = bytes.map(value => value.toString(16).padStart(2, '0')).join('')
  return hex.slice(0, 8) + '-' + hex.slice(8, 12) + '-' + hex.slice(12, 16) + '-' + hex.slice(16, 20) + '-' + hex.slice(20)
}
function createClaimService(options) {
  const config = options || {}, store = config.session, records = new Map()
  const pending = createPendingClaims({ storage: config.storage, scope: config.scope, enabled: config.persistent !== false })
  if (store && typeof store.onClear === 'function') store.onClear(() => records.clear())
  function identity() {
    const captured = store && store.snapshot()
    if (!captured || !captured.authenticated || !validId(captured.userId)) throw new ServiceError('auth', '请先登录后接单。')
    return captured
  }
  const key = (userId, taskId) => userId + ':' + taskId
  function find(captured, taskId) {
    if (!validId(taskId)) throw new ServiceError('validation', '任务编号无效，请刷新详情。')
    const name = key(captured.userId, taskId)
    if (!records.has(name)) {
      const saved = pending.get(captured.userId, taskId)
      if (saved) records.set(name, { ...saved, inFlight: false })
    }
    return records.get(name)
  }
  function remove(captured, taskId, record) {
    pending.remove(captured.userId, taskId, record.requestId)
    if (records.get(key(captured.userId, taskId)) === record) records.delete(key(captured.userId, taskId))
  }
  function dispatch(captured, taskId, record) {
    if (record.inFlight) return Promise.reject(new ServiceError('busy', '这条任务的接单请求正在处理中。'))
    const previous = record.status
    // 写请求发出前落盘；发送中断按未知结果恢复。
    try { pending.put(captured.userId, taskId, { ...record, status: 'unknown' }) } catch (error) {
      if (previous === 'idle') records.delete(key(captured.userId, taskId))
      return Promise.reject(error)
    }
    record.inFlight = true
    record.status = 'sending'
    return Promise.resolve().then(() => {
      if (!store.isCurrent(captured)) throw new ServiceError('stale', '账号已切换，请返回原账号确认。', { outcome: previous === 'idle' ? 'not_sent' : 'unknown' })
      return config.accept(taskId, captured.userId, { ...record.contact }, record.requestId)
    }).then(result => {
      if (!result || result.taskId !== taskId || result.requestId !== record.requestId || !['PENDING', 'ACCEPTED', 'ALREADY_ACCEPTED'].includes(result.outcome)) {
        throw new ServiceError('protocol', '接单结果格式无法识别，原请求仍待确认。', { outcome: 'unknown' })
      }
      if (!store.isCurrent(captured)) throw new ServiceError('stale', '账号已切换，请返回原账号确认。', { outcome: 'unknown' })
      if (result.outcome === 'PENDING') {
        record.status = 'pending'
        try { pending.update(captured.userId, taskId, record) } catch (_) { /* 落盘的unknown仍可安全恢复。 */ }
        return { outcome: result.outcome, status: record.status }
      }
      try { remove(captured, taskId, record) } catch (_) {
        record.status = 'unknown'
        return { outcome: result.outcome, status: record.status, storageWarning: true }
      }
      record.status = 'accepted'
      return { outcome: result.outcome, status: record.status }
    }).catch(error => {
      const keep = !error || !['rejected', 'not_sent'].includes(error.outcome) ||
        ['CLAIM_ALREADY_PENDING', 'CLAIM_REQUEST_MISMATCH'].includes(error.businessCode) ||
        previous !== 'idle' && (error.outcome === 'not_sent' || error.kind === 'auth')
      if (keep) {
        record.status = 'unknown'
        try { pending.update(captured.userId, taskId, record) } catch (_) { /* 保留提交前已落盘的原请求。 */ }
      } else {
        try { remove(captured, taskId, record) } catch (_) { record.status = 'unknown' }
      }
      throw error
    }).finally(() => { record.inFlight = false })
  }
  return {
    listPending() {
      const captured = identity()
      return pending.list(captured.userId).map(row => ({ taskId: row.taskId, status: row.status }))
    },
    getState(taskId) {
      const record = find(identity(), taskId)
      return record ? { status: record.status, inFlight: record.inFlight } : { status: 'idle', inFlight: false }
    },
    getDraft(taskId) {
      const record = find(identity(), taskId)
      return record ? { ...record.contact } : { phone: '', wechatId: '' }
    },
    submit(taskId, contact) {
      try {
        const captured = identity(), value = contact || {}, existing = find(captured, taskId)
        if (typeof value.phone !== 'string' || !/^\+?[1-9][0-9]{6,14}$/.test(value.phone) ||
          typeof value.wechatId !== 'string' || value.wechatId.length > 64 || value.wechatId !== value.wechatId.trim()) {
          throw new ServiceError('validation', '请填写格式正确的本次手机号，微信号最多 64 个字符。')
        }
        if (existing) {
          if (existing.contact.phone !== value.phone || existing.contact.wechatId !== value.wechatId) throw new ServiceError('business', '原接单结果尚未确认，请用原手机号与请求继续确认。', { outcome: 'unknown' })
          return dispatch(captured, taskId, existing)
        }
        const record = { requestId: uuid(), contact: { phone: value.phone, wechatId: value.wechatId }, status: 'idle', inFlight: false }
        records.set(key(captured.userId, taskId), record)
        return dispatch(captured, taskId, record)
      } catch (error) { return Promise.reject(error) }
    },
    retry(taskId) {
      try {
        const captured = identity(), record = find(captured, taskId)
        if (!record) throw new ServiceError('validation', '没有待确认的原接单请求，请刷新任务详情。')
        return dispatch(captured, taskId, record)
      } catch (error) { return Promise.reject(error) }
    }
  }
}
module.exports = { createClaimService, uuid }
