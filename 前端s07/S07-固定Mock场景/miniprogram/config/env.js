// S03只建立配置边界；切到real不会启用尚未实现的登录或业务请求。
const MODE = 'mock'
const ENDPOINTS = Object.freeze({ httpBaseUrl: 'http://localhost:8080', wsBaseUrl: '' })
function getEnvironment() {
  if (MODE !== 'mock' && MODE !== 'real') throw new Error('未知数据模式')
  return {
    mode: MODE, isMock: MODE === 'mock',
    modeLabel: MODE === 'mock' ? 'Mock 样例 · 尚未连接后端' : '真实服务配置 · 业务接口尚未接入',
    httpBaseUrl: ENDPOINTS.httpBaseUrl, wsBaseUrl: ENDPOINTS.wsBaseUrl
  }
}
module.exports = { getEnvironment }
