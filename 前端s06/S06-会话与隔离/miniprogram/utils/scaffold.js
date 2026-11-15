const { getEnvironment } = require('../config/env')
const navigation = require('./navigation')
const { createPageSession } = require('./page-session')
const { loginNavigation } = require('./login-navigation')
function createScaffold(options) {
  const settings = options || {}
  return Object.assign({
    data: Object.assign({}, getEnvironment(), settings.data || {}),
    onLoad(query) {
      this._sessionScope = createPageSession(this, { reset: settings.clearAccountData })
      this.setData(getEnvironment())
      if (settings.load) settings.load.call(this, query || {})
    },
    onUnload() {
      if (this._sessionScope) this._sessionScope.dispose()
      if (settings.unload) settings.unload.call(this)
    },
    go(event) {
      const data = event.currentTarget.dataset
      if (data.page === 'login') loginNavigation.open()
      else navigation.open(data.page, data.taskId)
    },
    back: navigation.back,
    explain() { wx.showModal({ title: '页面骨架阶段', content: '当前仅检查页面与导航。此操作尚未接入业务服务，不会提交数据。', showCancel: false }) }
  }, settings.methods || {})
}
module.exports = { createScaffold }
