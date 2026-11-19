const { createScaffold } = require('../../utils/scaffold')
const { session } = require('../../utils/session')
const { auth, emptyProfile, validateProfile } = require('../../services/auth')
const { loginNavigation } = require('../../utils/login-navigation')
const { routes } = require('../../utils/navigation')
function normalize(value) {
  if (!value || Object.keys(emptyProfile()).some(key => typeof value[key] !== 'string')) throw Error('Invalid profile')
  const checked = validateProfile(value)
  if (!checked.valid) throw Error('Invalid profile')
  return checked.profile
}
Page(createScaffold({
  data: { form: emptyProfile(), errors: {}, dirty: false, loading: false, saving: false, loaded: false, loadError: false, message: '', guardNotice: '', scenarioIndex: 0,
    scenarios: [{ key: 'normal', label: '正常' }, { key: 'delayed', label: '延迟' }, { key: 'failure', label: '失败' }] },
  clearAccountData() {
    this._sequence = (this._sequence || 0) + 1; this._modalPending = false; this._baseline = emptyProfile()
    this.setData({ form: emptyProfile(), errors: {}, dirty: false, loading: false, saving: false, loaded: false, loadError: false, message: '身份已变化，旧资料输入已清除。' })
    this.syncLeaveGuard()
  },
  load() {
    this._dead = false; this._visible = true; this._sequence = 0; this._baseline = emptyProfile(); this._shownOnce = false
    this._unsubscribe = session.subscribe(() => {
      Promise.resolve().then(() => { if (!this._dead && this._visible) this.loadProfile() })
    })
    return this.loadProfile()
  },
  unload() {
    this._dead = true; this._sequence++
    if (this._unsubscribe) this._unsubscribe()
    if (typeof wx.disableAlertBeforeUnload === 'function') wx.disableAlertBeforeUnload({})
  },
  methods: {
    onShow() {
      this._visible = true
      if (!this._shownOnce) { this._shownOnce = true; return }
      if (!this.data.loaded && !this.data.dirty) return this.loadProfile()
    },
    onHide() {
      this._visible = false; this._sequence++
      if (this.data.saving) this.setData({ saving: false, message: '本次模拟保存已中止，输入仍保留，请返回后重新保存。' })
      if (this.data.loading) this.setData({ loading: false })
    },
    current(sequence, captured) { return !this._dead && this._visible && sequence === this._sequence && session.isCurrent(captured) },
    loadProfile() {
      if (this._dead || !this._visible || !this.data.isMock || !session.snapshot().authenticated || this.data.loading || this.data.saving || this.data.dirty) return Promise.resolve()
      const captured = session.snapshot(), sequence = ++this._sequence
      this.setData({ loading: true, loaded: false, loadError: false, message: '' })
      return auth.getProfile(captured, this.data.scenarios[this.data.scenarioIndex].key).then(value => {
        if (!this.current(sequence, captured)) return
        const form = normalize(value); this._baseline = Object.assign({}, form)
        this.setData({ form, loading: false, loaded: true, loadError: false, dirty: false, errors: {} })
      }).catch(() => {
        if (this.current(sequence, captured)) this.setData({ loading: false, loadError: true, message: '暂时无法读取资料，请重试。' })
      })
    },
    locked() { return this._dead || !this._visible || !this.data.isMock || !session.snapshot().authenticated || !this.data.loaded || this.data.saving || this.data.loading },
    onInput(event) {
      const field = event.currentTarget.dataset.field
      if (this.locked() || !Object.prototype.hasOwnProperty.call(emptyProfile(), field)) return
      const form = Object.assign({}, this.data.form, { [field]: String(event.detail.value || '') })
      const dirty = Object.keys(form).some(key => form[key] !== this._baseline[key])
      const errors = Object.assign({}, this.data.errors); delete errors[field]
      this.setData({ form, dirty, errors, message: '' }); this.syncLeaveGuard()
    },
    save() {
      if (this.locked() || !this.data.dirty) return Promise.resolve()
      const checked = validateProfile(this.data.form)
      this.setData({ errors: checked.errors, message: '' })
      if (!checked.valid) {
        const field = Object.keys(checked.errors)[0]
        wx.pageScrollTo({ selector: '#field-' + field, duration: 180 }); return Promise.resolve()
      }
      const captured = session.snapshot(), sequence = ++this._sequence
      this.setData({ saving: true })
      return auth.saveProfile(captured, checked.profile, this.data.scenarios[this.data.scenarioIndex].key, () => this.current(sequence, captured)).then(value => {
        if (!this.current(sequence, captured)) return
        const form = normalize(value); this._baseline = Object.assign({}, form)
        this.setData({ form, dirty: false, saving: false, errors: {}, message: '模拟资料已保存，仅本次运行有效。已有任务的交接信息不会改变。' })
        this.syncLeaveGuard()
      }).catch(() => {
        if (this.current(sequence, captured)) this.setData({ saving: false, message: '本次未保存，输入已保留，请检查后重试。' })
      })
    },
    syncLeaveGuard() {
      if (this._dead) return
      const enabled = this.data.dirty
      if (enabled === this._guardEnabled) return
      this._guardEnabled = enabled
      if (!enabled) { if (typeof wx.disableAlertBeforeUnload === 'function') wx.disableAlertBeforeUnload({}); return }
      if (typeof wx.enableAlertBeforeUnload !== 'function') { this.setData({ guardNotice: '当前环境不支持系统返回提醒，请使用页内返回按钮。' }); return }
      wx.enableAlertBeforeUnload({ message: '资料尚未保存，离开后输入会丢失。', fail: () => {
        if (!this._dead && this._guardEnabled) this.setData({ guardNotice: '系统提醒未开启，请使用页内返回按钮。' })
      } })
    },
    back() {
      if (this.data.saving || this._modalPending) return
      const captured = session.snapshot()
      const leave = () => {
        if (this._dead || !session.isCurrent(captured)) return
        const navigate = () => {
          if (this._dead || !session.isCurrent(captured)) return
          this._guardEnabled = false
          const fail = () => { if (!this._dead) { this.setData({ message: '暂时无法返回，请重试。' }); this.syncLeaveGuard() } }
          if (getCurrentPages().length > 1) wx.navigateBack({ delta: 1, fail })
          else wx.switchTab({ url: routes.profile, fail })
        }
        if (typeof wx.disableAlertBeforeUnload === 'function') wx.disableAlertBeforeUnload({ complete: navigate }); else navigate()
      }
      if (!this.data.dirty) { leave(); return }
      this._modalPending = true
      wx.showModal({ title: '离开资料编辑？', content: '输入尚未保存，离开后将丢失。', confirmText: '离开', cancelText: '继续填写', success: result => { if (result.confirm) leave() }, complete: () => { this._modalPending = false } })
    },
    goLogin() { loginNavigation.open({ page: 'profileEdit' }) },
    chooseScenario(event) {
      const index = Number(event.detail.value)
      if (!this.data.isMock || this.data.saving || this.data.loading || !this.data.scenarios[index]) return
      this.setData({ scenarioIndex: index })
    },
    fillExample() {
      if (this.locked() || this.data.dirty) return
      const suffix = session.snapshot().userId.slice(-1).toUpperCase()
      this.setData({ form: { nickname: '模拟同学 ' + suffix, phone: '000-TEST-' + suffix, wechatId: 'demo_' + suffix }, dirty: true, message: '', errors: {} })
      this.syncLeaveGuard()
    }
  }
}))
