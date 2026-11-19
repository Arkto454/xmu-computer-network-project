// real开放登录/me及S16-A发布/大厅/详情；其他操作待接入。切换后需重新编译。
const MODE = 'real'
const ENDPOINTS = Object.freeze({ httpBaseUrl: 'http://127.0.0.1:18080', wsBaseUrl: '' })
function getEnvironment() {
  if (MODE !== 'mock' && MODE !== 'real') throw new Error('未知数据模式')
  return {
    mode: MODE, isMock: MODE === 'mock',
    modeLabel: MODE === 'mock' ? 'Mock 样例 · 尚未连接后端' : '真实服务 · 登录、发布与任务查询',
    httpBaseUrl: ENDPOINTS.httpBaseUrl, wsBaseUrl: ENDPOINTS.wsBaseUrl
  }
}
module.exports = { getEnvironment }
