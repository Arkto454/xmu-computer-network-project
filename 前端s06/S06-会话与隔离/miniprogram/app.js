const { getEnvironment } = require('./config/env')
const { session } = require('./utils/session')
App({
  globalData: { environment: getEnvironment(), session: session.snapshot() },
  onLaunch() {
    if (this._unsubscribeSession) this._unsubscribeSession()
    this.globalData.session = session.snapshot()
    this._unsubscribeSession = session.subscribe(value => { this.globalData.session = value })
  }
})
