const { createScaffold } = require('../../utils/scaffold')
const { loginNavigation } = require('../../utils/login-navigation')
const { session } = require('../../utils/session')
const { auth, accounts } = require('../../services/auth')
const { routes } = require('../../utils/navigation')
Page(createScaffold({
  data: { returnTargetLabel: '', accounts, accountIndex: 0, busy: false, navigating: false, message: '', scenarioIndex: 0,
    scenarios: [{ key: 'normal', label: '正常' }, { key: 'delayed', label: '延迟' }, { key: 'failure', label: '失败' }, { key: 'refused', label: '取消登录' }] },
  clearAccountData() {
    if (this._committing) return
    this.cancelAttempt()
    this.setData({ message: '身份已变化，请确认当前账号后继续。' })
  },
  load() {
    this._dead = false; this._visible = true; this._attempt = 0
    this._loginVisit = loginNavigation.enter()
    this.setData({ returnTargetLabel: loginNavigation.getTargetLabel() })
  },
  unload() { this.cancelAttempt(); this._dead = true; loginNavigation.leave(this._loginVisit) },
  methods: {
    onShow() { this._visible = true },
    onHide() { this._visible = false; this.cancelAttempt() },
    cancelAttempt() {
      this._attempt = (this._attempt || 0) + 1
      if (this._ticket) session.cancelLogin(this._ticket)
      this._ticket = null
      if (!this._dead) this.setData({ busy: false, navigating: false })
    },
    chooseAccount(event) {
      const index = Number(event.detail.value)
      if (!this.data.isMock || this.data.busy || this.data.navigating || !accounts[index]) return
      this.setData({ accountIndex: index, message: '' })
    },
    chooseScenario(event) {
      const index = Number(event.detail.value)
      if (!this.data.isMock || this.data.busy || this.data.navigating || !this.data.scenarios[index]) return
      this.setData({ scenarioIndex: index, message: '' })
    },
    login() {
      if (this._dead || !this._visible || this.data.busy || this.data.navigating) return Promise.resolve()
      if (session.snapshot().authenticated) { this.resume(); return Promise.resolve() }
      const attempt = ++this._attempt, ticket = session.beginLogin()
      this._ticket = ticket
      this.setData({ busy: true, message: '' })
      return auth.login(this.data.isMock ? accounts[this.data.accountIndex].id : undefined,
        this.data.isMock ? this.data.scenarios[this.data.scenarioIndex].key : undefined,
        () => !this._dead && this._visible && attempt === this._attempt).then(identity => {
        if (this._dead || !this._visible || attempt !== this._attempt) return
        this._committing = true
        let completed
        try { completed = session.completeLogin(ticket, identity) } finally { this._committing = false }
        this._ticket = null
        this.setData({ busy: false })
        if (completed) this.resume()
        else this.setData({ message: '本次登录已失效，请重新确认账号。' })
      }).catch(error => {
        if (this._dead || !this._visible || attempt !== this._attempt) return
        session.cancelLogin(ticket); this._ticket = null
        this.setData({ busy: false, message: this.data.isMock ? (error && error.kind === 'cancelled' ? '已取消本次模拟登录，可重新选择后重试。' : '本次未登录，请重试或返回。') :
          (error && error.message || '本次未登录，请重试或返回。') })
      })
    },
    resume() {
      if (this._dead || !this._visible || this.data.navigating) return
      const captured = session.snapshot()
      if (!captured.authenticated) return
      this.setData({ navigating: true, message: '' })
      const started = loginNavigation.finish(captured, { fail: () => {
        if (!this._dead && this._visible && session.isCurrent(captured)) this.setData({ navigating: false, message: '已登录，但未能返回原页面。请点下方按钮重试返回，无需重新登录。' })
      } })
      if (!started) this.setData({ navigating: false, message: '请确认当前身份后重试返回。' })
    },
    back() {
      if (this.data.navigating) return
      this.cancelAttempt()
      const fail = () => { if (!this._dead) this.setData({ message: '暂时无法返回，请稍后重试。' }) }
      if (getCurrentPages().length > 1) wx.navigateBack({ delta: 1, fail })
      else wx.switchTab({ url: routes.hall, fail })
    }
  }
}))
