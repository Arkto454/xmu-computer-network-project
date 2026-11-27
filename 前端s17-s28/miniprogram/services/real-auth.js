const { ServiceError } = require('./errors')
const { createRequestClient } = require('./request')
const { createSessionRequest } = require('./session-request')
function decodeResponse(response) {
  const body = response && response.data
  if (!body || typeof body.code !== 'string' || typeof body.message !== 'string' || !Object.prototype.hasOwnProperty.call(body, 'data')) return null
  return { ok: body.code === 'OK', data: body.data, code: body.code, kind: body.code === 'UNAUTHORIZED' ? 'auth' : 'business' }
}
function studentProfile(value) {
  if (!value || !Number.isSafeInteger(value.id) || value.id < 1 ||
    !(value.nickname === null || typeof value.nickname === 'string') ||
    !(value.contact === null || typeof value.contact === 'string')) throw new ServiceError('protocol', '学生资料格式无法识别，请联系后端同学检查。')
  return { id: String(value.id), nickname: value.nickname || '', contact: value.contact || '' }
}
function friendlyError(error) {
  const messages = {
    INVALID_REQUEST: '登录请求未通过校验，请重新登录。',
    WECHAT_LOGIN_FAILED: '微信登录凭证已失效，请点击登录重新获取。',
    ACCOUNT_DISABLED: '账号已被禁用，请联系管理员。',
    WECHAT_NOT_CONFIGURED: '微信登录配置尚未完成，请联系项目维护者。',
    WECHAT_UNAVAILABLE: '微信登录暂时不可用，请稍后重新登录。',
    UNAUTHORIZED: '登录已失效，请重新登录。'
  }
  if (error && Object.prototype.hasOwnProperty.call(messages, error.businessCode)) return new ServiceError(
    error.businessCode === 'ACCOUNT_DISABLED' ? 'disabled' : error.kind, messages[error.businessCode],
    { httpStatus: error.httpStatus, businessCode: error.businessCode, outcome: error.outcome })
  if (error && ['timeout', 'network'].indexOf(error.kind) !== -1) return new ServiceError(error.kind, '连接未完成，请检查网络后重新登录或重试读取。')
  return error instanceof ServiceError ? error : new ServiceError('protocol', '服务响应无法识别，请稍后重试。')
}
function createRealAuthService(options) {
  const config = options || {}, store = config.session, now = config.now || (() => Date.now())
  const request = createRequestClient({ baseUrl: config.baseUrl, decodeResponse, send: config.send })
  const authenticatedRequest = createSessionRequest({ session: store, baseUrl: config.baseUrl, decodeResponse, send: config.send })
  let pending = false
  function ready() { if (!store || store.mode !== 'real') throw new ServiceError('configuration', '真实服务只能使用真实登录会话。') }
  function getCode() {
    return new Promise((resolve, reject) => {
      const fail = () => reject(new ServiceError('wechat', '未能获取微信登录凭证，请点击登录重试。'))
      try {
        const login = config.loginApi || (args => wx.login(args))
        login({ timeout: 10000, success: result => {
          if (!result || typeof result.code !== 'string' || !result.code.trim() || result.code.length > 512) { fail(); return }
          resolve(result.code)
        }, fail })
      } catch (_) { fail() }
    })
  }
  return {
    login(_account, _scenario, permit) {
      try { ready() } catch (e) { return Promise.reject(e) }
      if (pending) return Promise.reject(new ServiceError('busy', '登录正在进行，请稍候。'))
      pending = true
      const allowed = () => !permit || permit()
      return Promise.resolve().then(() => {
        if (!allowed()) throw new ServiceError('cancelled', '本次登录已取消。')
        return getCode()
      }).then(code => {
        if (!allowed()) throw new ServiceError('cancelled', '本次登录已取消。')
        return request({ path: '/api/student/auth/wechat', method: 'POST', data: { code }, header: { 'content-type': 'application/json' } })
      }).then(result => {
        if (!allowed()) throw new ServiceError('cancelled', '本次登录已取消。')
        const value = result.data || {}, expiresAt = typeof value.expiresAt === 'string' ? Date.parse(value.expiresAt) : NaN
        const profile = studentProfile(value.student)
        if (value.tokenType !== 'Bearer' || typeof value.accessToken !== 'string' || !value.accessToken.trim() || /[\s]/.test(value.accessToken) ||
          !Number.isFinite(expiresAt) || expiresAt <= now()) throw new ServiceError('protocol', '登录结果缺少有效凭据或有效期，请重新登录。')
        return { mode: 'real', userId: profile.id, token: value.accessToken, expiresAt }
      }).catch(error => { throw friendlyError(error) }).finally(() => { pending = false })
    },
    getProfile(captured) {
      try {
        ready()
        if (!captured || captured.mode !== 'real' || !captured.authenticated || !store.isCurrent(captured)) throw new ServiceError('session', '身份已变化，请重新登录。')
      } catch (e) { return Promise.reject(e) }
      return authenticatedRequest({ path: '/api/student/me', auth: true }).then(result => {
        const profile = studentProfile(result.data)
        if (profile.id !== captured.userId) { store.invalidate(captured); throw new ServiceError('protocol', '返回身份与当前账号不一致，请重新登录。') }
        return profile
      }).catch(error => { throw friendlyError(error) })
    },
    saveProfile() { return Promise.reject(new ServiceError('configuration', '个人资料修改接口尚未提供，当前仅可读取。')) }
  }
}
module.exports = { createRealAuthService, decodeResponse, studentProfile }
