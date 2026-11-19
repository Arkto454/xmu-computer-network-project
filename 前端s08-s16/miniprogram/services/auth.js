const { getEnvironment } = require('../config/env')
const { session } = require('../utils/session')
const { ServiceError } = require('./errors')
const accounts = Object.freeze([{ id: 'mock-a', label: '模拟同学 A' }, { id: 'mock-b', label: '模拟同学 B' }, { id: 'mock-c', label: '模拟同学 C' }])
function emptyProfile() { return { nickname: '', phone: '', wechatId: '' } }
function validateProfile(value) {
  const form = value || {}, profile = emptyProfile(), errors = {}
  Object.keys(profile).forEach(key => { profile[key] = typeof form[key] === 'string' ? form[key].trim() : '' })
  if (Array.from(profile.nickname).length > 30) errors.nickname = '演示昵称暂限30字。'
  if (profile.phone && !/^1[3-9]\d{9}$/.test(profile.phone) && !/^000-TEST-[ABC]$/.test(profile.phone)) errors.phone = '请填11位手机号；演示可用000-TEST-A，也可留空。'
  if (Array.from(profile.wechatId).length > 64) errors.wechatId = '演示微信号暂限64字。'
  return { profile, errors, valid: Object.keys(errors).length === 0 }
}
function createAuthService(options) {
  const settings = options || {}, mode = settings.mode || getEnvironment().mode, store = settings.session || session
  if (mode === 'real') return require('./real-auth').createRealAuthService(Object.assign({}, settings, {
    session: store, baseUrl: settings.baseUrl || getEnvironment().httpBaseUrl
  }))
  const profiles = Object.create(null), wait = settings.wait || (ms => new Promise(resolve => setTimeout(resolve, ms)))
  function run(scenario, operation) {
    if (mode !== 'mock') return Promise.reject(new ServiceError('configuration', '真实登录与资料接口尚未接入。'))
    if (['normal', 'delayed', 'failure', 'refused'].indexOf(scenario || 'normal') < 0) return Promise.reject(new ServiceError('validation', '未知演示场景。'))
    return Promise.resolve().then(() => wait(scenario === 'delayed' ? 800 : 0)).then(() => {
      if (scenario === 'failure') throw new ServiceError('network', '本次演示未成功，请重试。')
      if (scenario === 'refused') throw new ServiceError('cancelled', '已取消本次模拟登录。')
      return operation()
    })
  }
  function requireIdentity(captured) {
    if (!captured || !captured.authenticated || captured.mode !== mode || !store.isCurrent(captured) || !accounts.some(a => a.id === captured.userId)) throw new ServiceError('session', '身份已变化，请重新登录。')
  }
  return {
    login(accountId, scenario) {
      return run(scenario, () => {
        if (!accounts.some(a => a.id === accountId)) throw new ServiceError('validation', '请选择有效的模拟账号。')
        return { mode: 'mock', userId: accountId, token: 'MOCK-ONLY-' + accountId }
      })
    },
    getProfile(captured, scenario) {
      return run(scenario, () => { requireIdentity(captured); return Object.assign(emptyProfile(), profiles[captured.userId] || {}) })
    },
    saveProfile(captured, form, scenario, permit) {
      const checked = validateProfile(form)
      if (!checked.valid) return Promise.reject(new ServiceError('validation', '请检查资料输入。'))
      return run(scenario, () => {
        requireIdentity(captured)
        if (permit && !permit()) throw new ServiceError('session', '本次模拟保存已取消。')
        profiles[captured.userId] = Object.assign({}, checked.profile)
        return Object.assign({}, checked.profile)
      })
    }
  }
}
const auth = createAuthService()
module.exports = { auth, createAuthService, accounts, emptyProfile, validateProfile }
