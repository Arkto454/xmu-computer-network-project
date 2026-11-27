const { createScaffold } = require('../../utils/scaffold')
const { session } = require('../../utils/session')
const { auth, accounts } = require('../../services/auth')
Page(createScaffold({
  data: { displayName: '', accountLabel: '', profileError: false, profileLoading: false, profileVerified: false },
  clearAccountData() { this._sequence = (this._sequence || 0) + 1; this.setData({ displayName: '', accountLabel: '', profileError: false, profileLoading: false, profileVerified: false }) },
  load() {
    this._dead = false; this._visible = true; this._sequence = 0; this._shownOnce = false
    this._unsubscribe = session.subscribe(() => { Promise.resolve().then(() => { if (!this._dead && this._visible) this.reloadIdentity() }) })
    return this.reloadIdentity()
  },
  unload() { this._dead = true; this._sequence++; if (this._unsubscribe) this._unsubscribe() },
  methods: {
    onShow() { this._visible = true; if (!this._shownOnce) { this._shownOnce = true; return } return this.reloadIdentity() },
    onHide() { this._visible = false; this._sequence++; this.setData({ profileLoading: false }) },
    reloadIdentity() {
      if (this._dead || !this._visible || this.data.profileLoading) return Promise.resolve()
      const sequence = ++this._sequence, captured = session.snapshot(), account = accounts.find(a => a.id === captured.userId)
      this.setData({ displayName: '', accountLabel: this.data.isMock && account && captured.authenticated ? account.label : '', profileError: false, profileLoading: false, profileVerified: false })
      if (!captured.authenticated) return Promise.resolve()
      this.setData({ profileLoading: true })
      return auth.getProfile(captured).then(value => {
        if (!this._dead && this._visible && sequence === this._sequence && session.isCurrent(captured)) this.setData({ displayName: String(value.nickname || ''), profileLoading: false, profileVerified: true })
      }).catch(() => { if (!this._dead && this._visible && sequence === this._sequence && session.isCurrent(captured)) this.setData({ profileError: true, profileLoading: false }) })
    },
    logout() {
      if (!session.snapshot().authenticated || this._logoutPending) return
      this._logoutPending = true
      const captured = session.snapshot()
      wx.showModal({ title: this.data.isMock ? '退出模拟登录？' : '退出当前登录？', content: this.data.isMock ? '旧账号的页面内容和未保存输入会清除。已保存的模拟资料仅在本次运行内保留。' : '将清除本机当前登录和未保存输入。后端凭据仍可能有效，重新使用需要点击微信登录。', confirmText: '退出', success: result => { if (result.confirm && !this._dead && this._visible && session.isCurrent(captured)) session.logout() }, complete: () => { this._logoutPending = false } })
    }
  }
}))
