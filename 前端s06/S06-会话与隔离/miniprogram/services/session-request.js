const { createRequestClient } = require('./request')
const { ServiceError } = require('./errors')
const { session } = require('../utils/session')

function createSessionRequest(options) {
  const config = options || {}
  const store = config.session || session
  return function request(input) {
    const settings = input || {}
    const captured = store.snapshot()
    const requestId = typeof settings.requestId === 'string' ? settings.requestId : null
    const method = String(settings.method || 'GET').toUpperCase()
    const write = ['GET', 'HEAD', 'OPTIONS'].indexOf(method) === -1
    const stale = () => new ServiceError('stale', '账号已切换，已忽略旧请求结果。', {
      requestId, outcome: write ? 'unknown' : 'ignored'
    })
    const client = createRequestClient(Object.assign({}, config, {
      getToken: () => store.getToken(captured)
    }))
    return client(settings).then(result => {
      if (!store.isCurrent(captured)) throw stale()
      return result
    }, error => {
      if (!store.isCurrent(captured)) throw stale()
      // 只有当前代次的首个服务端401会清理账号，旧请求不能退出新账号。
      if (settings.auth === true && error.kind === 'auth' && error.outcome !== 'not_sent' && store.invalidate(captured)) {
        if (typeof config.onAuthRequired === 'function') {
          try { config.onAuthRequired() } catch (_) { /* 导航失败不改变认证错误。 */ }
        }
      }
      throw error
    })
  }
}
module.exports = { createSessionRequest }
