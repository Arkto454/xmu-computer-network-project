const { getEnvironment } = require('../config/env')
const navigation = require('./navigation')
function createScaffold(options) {
  const settings = options || {}
  return {
    data: Object.assign({}, getEnvironment(), settings.data || {}),
    onLoad(query) {
      this.setData(getEnvironment())
      if (settings.load) settings.load.call(this, query || {})
    },
    go(event) {
      const data = event.currentTarget.dataset
      navigation.open(data.page, data.taskId)
    },
    back: navigation.back,
    explain() { wx.showModal({ title: '页面骨架阶段', content: '当前仅检查页面与导航。此操作尚未接入业务服务，不会提交数据。', showCancel: false }) }
  }
}
module.exports = { createScaffold }
