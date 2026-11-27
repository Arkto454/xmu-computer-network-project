const { getEnvironment } = require('./config/env')
const { session } = require('./utils/session')
const { networkRecovery } = require('./services/network-recovery')
App({
  globalData: { environment: getEnvironment(), session: session.snapshot() },
  onLaunch() {
    if (this._unsubscribeSession) this._unsubscribeSession()
    this.globalData.session = session.snapshot()
    this._unsubscribeSession = session.subscribe(value => { this.globalData.session = value })
    if (getEnvironment().mode === 'real') networkRecovery.start(wx)
  },
  onShow() { networkRecovery.foreground(true) },
  onHide() { networkRecovery.foreground(false) }
})
