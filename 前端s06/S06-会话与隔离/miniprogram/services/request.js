const { ServiceError } = require('./errors')
const METHODS = ['GET', 'HEAD', 'POST', 'PUT', 'DELETE', 'OPTIONS']
const READ_METHODS = ['GET', 'HEAD', 'OPTIONS']
const DEFAULT_TIMEOUT = 10000
function scalarCode(value) {
  return typeof value === 'string' || (typeof value === 'number' && Number.isFinite(value)) ? value : null
}
function validBase(value) {
  return typeof value === 'string' && /^https?:\/\/[A-Za-z0-9.-]+(?::\d{1,5})?(?:\/[A-Za-z0-9_~-]+)*\/?$/.test(value)
}
function validPath(value) {
  return typeof value === 'string' && /^\/(?!\/)[A-Za-z0-9_~!$&'()*+,;=:@%/?-]*$/.test(value) && !/%(?:2f|5c|2e)/i.test(value)
}
// decodeResponse 是每组已确认契约的适配器，不预设 code=0 或 success=true。
// 它需返回 { ok: Boolean, data?, code?, message?, kind?: 'auth'|'business'|'unknown' }。
function createRequestClient(configuration) {
  const config = configuration || {}
  return function request(options) {
    const input = options || {}
    return new Promise((resolve, reject) => {
      const requestId = typeof input.requestId === 'string' ? input.requestId : null
      const notSent = message => reject(new ServiceError('configuration', message, { requestId }))
      const method = input.method == null ? 'GET' : (typeof input.method === 'string' ? input.method.toUpperCase() : '')
      const write = READ_METHODS.indexOf(method) === -1
      const timeout = input.timeout == null ? DEFAULT_TIMEOUT : input.timeout
      if (!validBase(config.baseUrl) || !validPath(input.path) || METHODS.indexOf(method) === -1) {
        notSent('请求地址或方法尚未正确配置。'); return
      }
      if (!Number.isInteger(timeout) || timeout < 1 || timeout > 60000) { notSent('请求超时时间无效。'); return }
      if (typeof config.decodeResponse !== 'function') { notSent('接口响应约定尚未接入。'); return }
      const header = Object.assign({}, input.header || {})
      // 受保护请求只接受统一凭据入口；页面不能绕过入口传旧 Authorization。
      if (Object.keys(header).some(key => key.toLowerCase() === 'authorization')) { notSent('请通过统一凭据入口设置身份。'); return }
      if (input.auth === true) {
        let token
        try { token = typeof config.getToken === 'function' ? config.getToken() : null } catch (_) { notSent('无法读取当前身份。'); return }
        if (typeof token !== 'string' || !token || /[\r\n]/.test(token)) {
          reject(new ServiceError('auth', '请先登录。', { requestId })); return
        }
        header.Authorization = 'Bearer ' + token
      }
      let settled = false
      function fail(kind, message, httpStatus, businessCode, unknown) {
        if (settled) return
        settled = true
        reject(new ServiceError(kind, message, { requestId, httpStatus, businessCode,
          outcome: unknown ? 'unknown' : 'rejected' }))
      }
      const unknownTransport = write
      const transportFailure = error => {
        const timedOut = !!(error && typeof error.errMsg === 'string' && /timeout|timed out/i.test(error.errMsg))
        fail(timedOut ? 'timeout' : 'network', write ? '操作结果待确认，请先确认原操作结果。' : (timedOut ? '请求超时，请稍后重试。' : '网络连接失败，请稍后重试。'), null, null, unknownTransport)
      }
      const success = response => {
        if (settled) return
        const status = response && response.statusCode
        if (!Number.isInteger(status) || status < 100 || status > 599) { fail('protocol', '服务响应格式无法识别。', null, null, write); return }
        // 非2xx即使出现在wx.request的success回调，也绝不会进入成功分支。
        let decoded = null
        try { decoded = config.decodeResponse(response) } catch (_) { /* 错误HTTP仍按HTTP状态分类。 */ }
        const code = decoded && scalarCode(decoded.code)
        if (status < 200 || status >= 300) {
          const auth = status === 401
          const unknown = write && (status >= 500 || status === 408)
          fail(auth ? 'auth' : 'http', auth ? '登录已失效，请重新登录。' : (unknown ? '操作结果待确认，请先确认原操作结果。' : '服务请求失败，请稍后再试。'), status, code, unknown)
          return
        }
        if (!decoded || typeof decoded.ok !== 'boolean') { fail('protocol', '服务响应格式无法识别。', status, code, write); return }
        if (!decoded.ok) {
          const kind = ['auth', 'business', 'unknown'].indexOf(decoded.kind) !== -1 ? decoded.kind : 'business'
          const message = typeof decoded.message === 'string' && decoded.message.trim() ? decoded.message.slice(0, 200) : (kind === 'auth' ? '登录已失效，请重新登录。' : '当前操作未成功，请检查后再试。')
          fail(kind, message, status, code, kind === 'unknown'); return
        }
        settled = true
        resolve({ data: decoded.data, httpStatus: status, businessCode: code })
      }
      const args = { url: config.baseUrl.replace(/\/$/, '') + input.path, method, data: input.data,
        header, timeout, success, fail: transportFailure }
      // 每次调用只发送一次，不重试、不跳转、不写存储，也不生成/替换业务请求编号。
      try {
        if (typeof config.send === 'function') config.send(args)
        else wx.request(args)
      } catch (error) { transportFailure(error) }
    })
  }
}
module.exports = { createRequestClient, DEFAULT_TIMEOUT }
