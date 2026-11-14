const routes = Object.freeze({
  hall: '/pages/index/index', myTasks: '/pages/my-tasks/index', profile: '/pages/profile/index',
  login: '/pages/login/index', publish: '/pages/publish/index', detail: '/pages/task-detail/index',
  profileEdit: '/pages/profile-edit/index', deliveryCode: '/pages/delivery-code/index',
  scanConfirm: '/pages/scan-confirm/index', earnings: '/pages/earnings/index'
})
const tabs = ['hall', 'myTasks', 'profile']
const needsTask = ['detail', 'deliveryCode', 'scanConfirm']
function isTaskId(value) { return typeof value === 'string' && /^[A-Za-z0-9_-]{1,80}$/.test(value) }
function open(page, taskId) {
  if (!Object.prototype.hasOwnProperty.call(routes, page)) return false
  if (tabs.indexOf(page) !== -1) { wx.switchTab({ url: routes[page] }); return true }
  if (needsTask.indexOf(page) !== -1 && !isTaskId(taskId)) {
    wx.showToast({ title: '缺少有效任务编号', icon: 'none' }); return false
  }
  let url = routes[page]
  if (needsTask.indexOf(page) !== -1) url += '?taskId=' + encodeURIComponent(taskId)
  wx.navigateTo({ url })
  return true
}
function back() {
  if (getCurrentPages().length > 1) wx.navigateBack({ delta: 1, fail() { open('hall') } })
  else open('hall')
}
module.exports = { routes, open, back, isTaskId }
