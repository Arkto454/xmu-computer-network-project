const { routes, isTaskId } = require('./navigation')
const { session } = require('./session')

const labels = Object.freeze({ hall: '任务大厅', myTasks: '我的任务', profile: '我的', publish: '发布任务', detail: '任务详情', profileEdit: '个人资料', earnings: '模拟收益', deliveryCode: '交付码', scanConfirm: '扫码核对' })
const tabs = ['hall', 'myTasks', 'profile']
const taskPages = ['detail', 'deliveryCode', 'scanConfirm']
function normalizeTarget(value) {
  if (!value || !Object.prototype.hasOwnProperty.call(labels, value.page)) return null
  if (taskPages.indexOf(value.page) !== -1) return isTaskId(value.taskId) ? { page: value.page, taskId: value.taskId } : null
  return { page: value.page }
}
function targetFromPage(page) {
  if (!page || typeof page.route !== 'string') return null
  const key = Object.keys(labels).find(name => routes[name] === '/' + page.route)
  return normalizeTarget({ page: key, taskId: page.options && page.options.taskId })
}
function createLoginNavigation(options) {
  const config = options || {}
  const store = config.session || session
  const api = config.api || { navigateTo: value => wx.navigateTo(value), redirectTo: value => wx.redirectTo(value), switchTab: value => wx.switchTab(value) }
  const pages = config.getPages || (() => getCurrentPages())
  let target = null
  let pending = false
  let visit = 0
  function reset() { target = null; pending = false; visit++ }
  function currentTarget() {
    const stack = pages() || []
    return targetFromPage(stack[stack.length - 1]) || { page: 'hall' }
  }
  function open(value) {
    if (pending) return false
    target = normalizeTarget(value) || currentTarget()
    pending = true
    const attempt = ++visit
    const stack = pages() || []
    if (stack.length && '/' + stack[stack.length - 1].route === routes.login) return true
    try {
      api.navigateTo({ url: routes.login, fail() { if (visit === attempt) reset() } })
      return true
    } catch (_) { if (visit === attempt) reset(); return false }
  }
  function finish(captured) {
    if (!captured || !captured.authenticated || !store.isCurrent(captured)) return false
    const destination = target || { page: 'hall' }
    const url = routes[destination.page] + (destination.taskId ? '?taskId=' + encodeURIComponent(destination.taskId) : '')
    reset()
    if (tabs.indexOf(destination.page) !== -1) api.switchTab({ url })
    else api.redirectTo({ url })
    return true
  }
  return {
    open,
    enter() { return visit },
    leave(ticket) { if (ticket === visit) reset() },
    finish,
    currentTarget,
    getTarget() { return target ? Object.assign({}, target) : null },
    getTargetLabel() { return target ? labels[target.page] : '任务大厅' },
    cancel: reset
  }
}
const loginNavigation = createLoginNavigation()
module.exports = { createLoginNavigation, loginNavigation, normalizeTarget }
