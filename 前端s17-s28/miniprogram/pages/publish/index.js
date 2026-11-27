const { createScaffold } = require('../../utils/scaffold')
const services = require('../../services/index')
const { session } = require('../../utils/session')
const { routes, isTaskId } = require('../../utils/navigation')
const { getTaskTypeOptions } = require('../../config/task-types')
const { emptyForm, isDirty, validateForm } = require('../../utils/publish-form')
const { localParts } = require('../../utils/time')
Page(createScaffold({
  data: {
    form: emptyForm(), errors: {}, typeOptions: getTaskTypeOptions('mock'), typeIndex: 0, typeLabel: '',
    dirty: false, isSubmitting: false, resultUnknown: false, createdTaskId: '', submitMessage: '', guardNotice: '',
    showPreview: false, mockScenarioIndex: 0,
    mockAccounts: [{ id: '', label: '未登录' }, { id: 'mock-a', label: '账号 A' }, { id: 'mock-b', label: '账号 B' }, { id: 'mock-c', label: '账号 C' }],
    mockScenarios: [{ key: 'normal', label: '正常' }, { key: 'delayed', label: '延迟' }, { key: 'failure', label: '失败' }]
  },
  clearAccountData() {
    this._attempt = (this._attempt || 0) + 1
    this._busy = false; this._modalPending = false; this._redirecting = false
    this.setData({ form: emptyForm(), errors: {}, typeIndex: 0, typeLabel: '', dirty: false, isSubmitting: false, resultUnknown: false, createdTaskId: '', submitMessage: '身份已变化，本页输入已清除，请重新填写。' })
    this.syncLeaveGuard()
    if (!this.data.isMock) this.syncPublishState()
  },
  load() {
    this._dead = false; this._attempt = 0; this._busy = false; this._guardEnabled = false
    this.setData({ typeOptions: getTaskTypeOptions(this.data.mode) }); this.syncPublishState()
    if (!this.data.isMock) this._unsubscribePublish = session.subscribe(() => {
      if (this._syncQueued) return
      this._syncQueued = true
      Promise.resolve().then(() => { this._syncQueued = false; if (!this._dead) this.syncPublishState() })
    })
  },
  unload() {
    this._dead = true; this._attempt++
    if (this._unsubscribePublish) this._unsubscribePublish()
    if (typeof wx.disableAlertBeforeUnload === 'function') wx.disableAlertBeforeUnload({})
    if (this._previewScenarioChanged && services.scenarios) services.scenarios.reset()
  },
  methods: {
    onShow() { if (!this.data.isMock) this.syncPublishState() },
    syncPublishState() {
      if (this.data.isMock) return
      const state = services.tasks.getPublishState()
      if (!state) return
      if (state.kind === 'created') this.setData({ createdTaskId: state.id, isSubmitting: false, resultUnknown: false, dirty: false, form: emptyForm(), submitMessage: '任务已发布，请打开原任务详情。' })
      else this.setData({ resultUnknown: true, isSubmitting: false, submitMessage: '前一次发布仍在处理或结果待确认。任务可能已创建，请勿重复提交；关闭页面不会取消后端操作。' })
    },
    startAnotherTask() {
      if (this.data.isMock || !this.data.createdTaskId || this._busy) return
      try { services.tasks.startNewPublish() } catch (_) { this.setData({ submitMessage: '暂时无法开始新任务，请先确认当前登录和前次发布结果。' }); return }
      this.setData({ createdTaskId: '', form: emptyForm(), errors: {}, typeIndex: 0, typeLabel: '', dirty: false, resultUnknown: false, submitMessage: '' })
      this._redirecting = false
    },
    locked() { return this._busy || this.data.resultUnknown || !!this.data.createdTaskId || !this.data.isMock && !session.snapshot().authenticated },
    updateForm(form) {
      if (this.locked()) return
      const typeIndex = this.data.typeOptions.findIndex(item => item.code === form.typeCode)
      this.setData({ form, dirty: isDirty(form), typeIndex: Math.max(0, typeIndex), typeLabel: typeIndex < 0 ? '' : this.data.typeOptions[typeIndex].label, submitMessage: '' })
      this.syncLeaveGuard()
    },
    onInput(event) {
      const field = event.currentTarget.dataset.field
      if (!Object.prototype.hasOwnProperty.call(emptyForm(), field) || field === 'typeCode') return
      const form = Object.assign({}, this.data.form, { [field]: String(event.detail.value || '') })
      this.updateForm(form)
      if (!this.locked() && this.data.errors[field]) this.validateField(event)
    },
    validateField(event) {
      if (this.locked()) return
      let field = event.currentTarget.dataset.field
      if (field === 'deadlineTime') field = 'deadlineDate'
      if (!Object.prototype.hasOwnProperty.call(emptyForm(), field)) return
      const next = Object.assign({}, this.data.errors), error = validateForm(this.data.form, Date.now(), this.data.mode).errors[field]
      if (error) next[field] = error; else delete next[field]
      this.setData({ errors: next })
    },
    chooseType(event) {
      const index = Number(event.detail.value), type = this.data.typeOptions[index]
      if (!type || this.locked()) return
      this.updateForm(Object.assign({}, this.data.form, { typeCode: type.code }))
      this.validateField({ currentTarget: { dataset: { field: 'typeCode' } } })
    },
    chooseDeadline(event) { this.onInput(event); this.validateField(event) },
    syncLeaveGuard() {
      if (this._dead) return
      const enabled = this.data.dirty && !this.data.createdTaskId
      if (enabled === this._guardEnabled) return
      this._guardEnabled = enabled
      if (!enabled) { if (typeof wx.disableAlertBeforeUnload === 'function') wx.disableAlertBeforeUnload({}); return }
      if (typeof wx.enableAlertBeforeUnload !== 'function') { this.setData({ guardNotice: '当前环境不支持系统返回提醒，请用页内返回按钮；离开后输入不会保存。' }); return }
      wx.enableAlertBeforeUnload({ message: '当前输入尚未保存，离开会丢失。若正在发布，请先等待结果。', fail: () => {
        if (!this._dead && this._guardEnabled) this.setData({ guardNotice: '系统返回提醒未开启，请用页内返回按钮；离开后输入不会保存。' })
      } })
    },
    withGuardOff(action) {
      this._guardEnabled = false
      if (typeof wx.disableAlertBeforeUnload === 'function') wx.disableAlertBeforeUnload({ complete: action })
      else action()
    },
    back() {
      if (this._busy) { wx.showToast({ title: '正在发布，请先等待结果', icon: 'none' }); return }
      if (this._modalPending) return
      const identity = session.snapshot()
      const leave = () => this.withGuardOff(() => {
        if (this._dead || !session.isCurrent(identity)) return
        const failed = () => { if (!this._dead) { this.setData({ submitMessage: '暂时无法返回，请稍后重试。' }); this.syncLeaveGuard() } }
        if (getCurrentPages().length > 1) wx.navigateBack({ delta: 1, fail: failed })
        else wx.switchTab({ url: routes.hall, fail: failed })
      })
      if (!this.data.dirty) { leave(); return }
      this._modalPending = true
      const captured = session.snapshot()
      wx.showModal({ title: this.data.resultUnknown ? '发布结果尚未确认' : '离开填写页？', content: this.data.resultUnknown ? '任务可能已经创建。离开后不要重复发布，请先核对结果。输入不会保存。' : '输入尚未保存，离开后将丢失。', confirmText: '离开', cancelText: '继续填写', success: result => {
        if (result.confirm && !this._dead && session.isCurrent(captured)) leave()
      }, complete: () => { this._modalPending = false } })
    },
    submit() {
      if (this.locked()) return Promise.resolve()
      const captured = session.snapshot()
      if (!captured.authenticated || captured.mode !== this.data.mode) { this.setData({ submitMessage: this.data.isMock ? '请先在演示检查中选择账号，再发布模拟任务。' : '请先使用微信登录，再填写和发布任务。' }); return Promise.resolve() }
      const checked = validateForm(this.data.form, Date.now(), this.data.mode)
      this.setData({ errors: checked.errors, submitMessage: checked.valid ? '' : '请修改标出的内容，输入已保留。' })
      if (!checked.valid) {
        if (typeof wx.pageScrollTo === 'function') wx.pageScrollTo({ selector: '#field-' + checked.firstError, duration: 200 })
        return Promise.resolve()
      }
      this._busy = true
      const attempt = ++this._attempt
      this.setData({ isSubmitting: true })
      return services.tasks.publish(checked.draft, captured.userId).then(result => {
        if (this._dead || attempt !== this._attempt || !session.isCurrent(captured)) return
        if (!result || !isTaskId(result.id)) throw new Error('Unconfirmed publish result')
        this._busy = false
        this.setData({ form: emptyForm(), errors: {}, dirty: false, isSubmitting: false, createdTaskId: result.id, typeIndex: 0, typeLabel: '', submitMessage: this.data.isMock ? '模拟任务已发布，正在打开详情。' : '任务已发布，正在打开详情。' })
        this.openCreatedTask()
      }).catch(error => {
        if (this._dead || attempt !== this._attempt || !session.isCurrent(captured)) return
        this._busy = false
        const definite = error && (error.outcome === 'not_sent' || error.outcome === 'rejected')
        this.setData({ isSubmitting: false, resultUnknown: !definite, submitMessage: definite ? '本次未发布，输入已保留。请检查后重试。' : '发布结果尚未确认，请勿重复提交。需要核对是否已经创建，当前暂不能再次发布。' })
      })
    },
    openCreatedTask() {
      if (!isTaskId(this.data.createdTaskId) || this._redirecting || this._dead) return
      this._redirecting = true
      const id = this.data.createdTaskId, captured = session.snapshot(), attempt = this._attempt
      this.withGuardOff(() => {
        if (this._dead || attempt !== this._attempt || !session.isCurrent(captured)) return
        const failed = () => {
          if (!this._dead && attempt === this._attempt && session.isCurrent(captured)) { this._redirecting = false; this.setData({ submitMessage: (this.data.isMock ? '模拟任务' : '任务') + '已发布，但详情未能打开。请点击下方按钮重试打开，不要再次发布。' }) }
        }
        try { wx.redirectTo({ url: routes.detail + '?taskId=' + encodeURIComponent(id), fail: failed }) } catch (_) { failed() }
      })
    },
    togglePreview() { if (this.data.isMock) this.setData({ showPreview: !this.data.showPreview }) },
    switchMockAccount(event) {
      const id = event.currentTarget.dataset.userId || ''
      if (!this.data.isMock || this._busy || this.data.dirty || this.data.createdTaskId || !this.data.mockAccounts.some(account => account.id === id)) return
      if (id) session.completeLogin(session.beginLogin(), { mode: 'mock', userId: id, token: 'MOCK-ONLY-' + id }); else session.logout()
    },
    fillSample() {
      if (!this.data.isMock || this.data.dirty || this.locked()) return
      const deadline = localParts(Date.now() + 2 * 60 * 60 * 1000)
      this.updateForm(Object.assign(emptyForm(), { title: '演示：帮取一份打印资料', typeCode: 'mock-documents', pickupLocation: '校内打印店门口', deliveryLocation: '图书馆入口', deadlineDate: deadline.date, deadlineTime: deadline.time, reward: '2.50', phone: '000-TEST-A' }))
      this.setData({ errors: {} })
    },
    chooseMockScenario(event) {
      const index = Number(event.detail.value), item = this.data.mockScenarios[index]
      if (!this.data.isMock || this.locked() || !item || !services.scenarios) return
      services.scenarios.set(item.key); this._previewScenarioChanged = true; this.setData({ mockScenarioIndex: index })
    }
  }
}))
