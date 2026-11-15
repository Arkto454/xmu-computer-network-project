const { getEnvironment } = require('../config/env')
const { ServiceError } = require('../services/errors')

function createSession(options) {
  const mode = (options || {}).mode
  if (mode !== 'mock' && mode !== 'real') throw new ServiceError('configuration', '未知会话模式。')
  let generation = 0
  let loginSequence = 0
  let userId = null
  let token = null
  let switching = false
  const subscribers = new Set()
  const cleanups = new Set()

  function snapshot() {
    return Object.freeze({ mode, generation, userId, authenticated: token !== null })
  }
  function isCurrent(captured) {
    return !!captured && captured.mode === mode && captured.generation === generation &&
      captured.userId === userId
  }
  function notify() {
    const value = snapshot()
    Array.from(subscribers).forEach(listener => {
      try { listener(value) } catch (_) { /* 一个页面的清理异常不能阻塞其他页面。 */ }
    })
  }
  function clearResources() {
    Array.from(cleanups).forEach(cleanup => {
      try { cleanup() } catch (_) { /* 继续清理其他账号资源。 */ }
    })
  }
  function replace(identity) {
    if (switching) throw new ServiceError('configuration', '会话正在切换，请稍后重试。')
    switching = true
    try {
      generation++
      loginSequence++
      token = null
      userId = null
      clearResources()
      // 先通知页面清空旧账号，再发布新账号，避免旧资料短暂闪现。
      notify()
      if (identity) { userId = identity.userId; token = identity.token }
    } finally { switching = false }
    if (identity) notify()
    return snapshot()
  }
  function beginLogin() {
    if (switching) throw new ServiceError('configuration', '会话正在切换，请稍后重试。')
    loginSequence++
    return Object.freeze({ mode, generation, sequence: loginSequence })
  }
  function ticketCurrent(ticket) {
    return !!ticket && ticket.mode === mode && ticket.generation === generation && ticket.sequence === loginSequence
  }
  function completeLogin(ticket, identity) {
    if (!ticketCurrent(ticket)) return false
    const value = identity || {}
    if (value.mode !== mode || typeof value.userId !== 'string' || !value.userId.trim() || value.userId.length > 128 ||
      typeof value.token !== 'string' || !value.token.trim() || /[\r\n]/.test(value.token)) {
      throw new ServiceError('validation', '登录结果缺少有效身份或所属模式不匹配。')
    }
    return replace({ userId: value.userId, token: value.token })
  }
  function cancelLogin(ticket) {
    if (!ticketCurrent(ticket)) return false
    loginSequence++
    return true
  }
  function invalidate(captured) {
    if (switching || token === null || !isCurrent(captured)) return false
    replace(null)
    return true
  }
  return {
    mode,
    snapshot,
    isCurrent,
    beginLogin,
    completeLogin,
    cancelLogin,
    getToken(captured) { return isCurrent(captured) ? token : null },
    logout() { return replace(null) },
    invalidate,
    subscribe(listener) {
      if (typeof listener !== 'function') throw new TypeError('需要会话订阅函数')
      subscribers.add(listener)
      return () => subscribers.delete(listener)
    },
    onClear(cleanup) {
      if (typeof cleanup !== 'function') throw new TypeError('需要账号清理函数')
      cleanups.add(cleanup)
      return () => cleanups.delete(cleanup)
    }
  }
}

const session = createSession({ mode: getEnvironment().mode })
module.exports = { createSession, session }
