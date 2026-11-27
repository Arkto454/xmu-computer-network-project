const { createScaffold } = require('../../utils/scaffold')
const { isTaskId } = require('../../utils/navigation')
const services = require('../../services/index')
const { session } = require('../../utils/session')
const EMPTY = { task: null, pageState: 'loading', message: '', notice: '', loading: false, busy: false,
  imageSrc: '', imageReady: false, imageError: false, codeStatus: '', remainingText: '', expiresText: '',
  canRequest: false, canRefresh: false, canRevoke: false, paused: false }
Page(createScaffold({
  recover() { if (!this._operation) return this.reload({ periodic: true }) },
  data: { taskId: '', ...EMPTY },
  clearAccountData() { this.clearCode(); this._seq = (this._seq || 0) + 1; this._operation = null; this._knownVersion = null; this.setData({ ...EMPTY }) },
  load(query) {
    this._dead = false; this._visible = true; this._seq = 0; this._shown = false; this._knownVersion = null
    this.setData({ taskId: isTaskId(query.taskId) ? query.taskId : '' })
    this._unsub = session.subscribe(() => {
      const seq = this._seq
      Promise.resolve().then(() => { if (!this._dead && this._visible && seq === this._seq) this.reload() })
    })
    return this.reload()
  },
  unload() { this._dead = true; this._seq++; this.clearTimer(); this._code = null; this._knownVersion = null; if (this._unsub) this._unsub() },
  methods: {
    onPageScroll(event) { this._scrollTop = Math.max(0, Number(event.scrollTop) || 0) },
    onShow() { this._visible = true; if (!this._shown) { this._shown = true; return } return this.reload() },
    onHide() { this._visible = false; this._seq++; this._operation = null; this._knownVersion = null; this.clearCode(); this.setData({ ...EMPTY }) },
    clearTimer() { if (this._timer) clearTimeout(this._timer); this._timer = null },
    clearCode() { this.clearTimer(); this._code = null; this.setData({ imageSrc: '', imageReady: false, imageError: false, remainingText: '', expiresText: '', canRefresh: false, canRevoke: false }) },
    current(captured, seq) { return !this._dead && this._visible && seq === this._seq && session.isCurrent(captured) },
    async reload(options) {
      if (this._dead || !this._visible) return
      const restoreScroll = options && options.periodic === true ? this._scrollTop || 0 : null
      const seq = ++this._seq, captured = session.snapshot()
      this.clearCode(); this.setData({ ...EMPTY, loading: true, busy: !!this._operation })
      if (this.data.isMock) { this.setData({ pageState: 'mock', loading: false, message: 'Mock预览不生成可扫描交付码。请在真实模式下进入接单任务验收。' }); return }
      if (!isTaskId(this.data.taskId) || !/^[1-9]\d{0,15}$/.test(this.data.taskId)) { this.setData({ pageState: 'unavailable', loading: false, message: '缺少有效任务编号，请从任务详情重新打开。' }); return }
      if (!captured.authenticated) { this.setData({ pageState: 'auth', loading: false, message: '请使用原接单账号登录。' }); return }
      try {
        const detail = await services.tasks.getPublic(this.data.taskId)
        if (!this.current(captured, seq)) return
        const task = { id: detail.id, title: detail.title, status: detail.status, pickupLocation: detail.pickupLocation, deliveryLocation: detail.deliveryLocation }
        this.setData({ task })
        if (detail.relation !== 'runner') { this._knownVersion = null; this.setData({ pageState: 'unavailable', message: '仅本任务的正式接单者可管理交付码。' }); return }
        if (!['已取件', '待确认'].includes(detail.status)) { this._knownVersion = null; this.setData({ pageState: 'unavailable', message: ['已完成','已取消','已过期','已关闭'].includes(detail.status) ? '任务已结束，交付码已停止展示。' : '请先在任务详情确认已取件。' }); return }
        if (detail.deliveryPaused) {
          this.setData({ pageState: '', paused: true, message: '取消申请正在审核，暂停签发、刷新和交付。', canRevoke: detail.status === '待确认' && this._knownVersion !== null })
          return
        }
        if (detail.status === '已取件') { this.setData({ pageState: '', canRequest: true, message: '到达约定交付地点后，再点击申请交付。进入此页不会自动提交。' }); return }
        const code = await services.tasks.getDeliveryCode(this.data.taskId, captured.userId)
        if (!this.current(captured, seq)) return
        this._knownVersion = code.codeVersion
        // 读取图片后再核对任务，避免较早的码响应覆盖已关闭/完成/暂停状态。
        const latest = await services.tasks.getPublic(this.data.taskId)
        if (!this.current(captured, seq)) return
        if (latest.relation !== 'runner' || latest.status !== '待确认' || latest.deliveryPaused) {
          this.setData({ task: { ...task, status: latest.status }, pageState: '', paused: !!latest.deliveryPaused,
            message: latest.deliveryPaused ? '取消审核中，交付已暂停。' : '任务进度已变化，已停止展示交付码。', canRevoke: latest.relation === 'runner' && latest.status === '待确认' && !!latest.deliveryPaused })
          return
        }
        this._code = code; this._checkedAt = Date.now()
        this.setData({ pageState: '', codeStatus: code.codeStatus, message: '', canRefresh: true, canRevoke: code.codeStatus === 'ACTIVE' })
        this.tick(captured, seq)
      } catch (error) {
        if (!this.current(captured, seq)) return
        this.clearCode(); this.setData({ pageState: error.kind === 'auth' ? 'auth' : 'error', message: error.message || '交付码暂时无法读取，请重试。' })
      } finally {
        if (this.current(captured, seq)) this.setData({ loading: false, busy: !!this._operation }, () => {
          if (restoreScroll !== null && this.current(captured, seq)) wx.pageScrollTo({ scrollTop: restoreScroll, duration: 0 })
        })
      }
    },
    tick(captured, seq) {
      if (!this.current(captured, seq) || !this._code) return
      const code = this._code, remaining = code.expiresAt == null ? 0 : Math.max(0, Math.floor((code.expiresAt - Date.now()) / 1000))
      if (code.codeStatus !== 'ACTIVE' || remaining <= 0) {
        this.clearCode(); this.setData({ codeStatus: 'EXPIRED', canRefresh: true, message: '交付码已过期。任务仍为待确认，可主动生成新码。' }); return
      }
      const until = new Date(code.expiresAt)
      this.setData({ imageSrc: code.imageSrc, remainingText: Math.floor(remaining / 60) + ':' + String(remaining % 60).padStart(2,'0'),
        expiresText: until.toLocaleTimeString(), canRefresh: true, canRevoke: true })
      this._timer = setTimeout(() => {
        if (!this.current(captured, seq)) return
        // 仅可见的有效码期间，每30秒只读核对；离开、过期即停止，不自动重发写请求。
        if (Date.now() - this._checkedAt >= 30000) this.reload({ periodic: true })
        else this.tick(captured, seq)
      }, 1000)
    },
    codeLoaded(event) { if (this._visible && !this._dead && event.currentTarget.dataset.src === this.data.imageSrc && this.data.imageSrc) this.setData({ imageReady: true, imageError: false }) },
    codeFailed(event) {
      if (!this._visible || this._dead || event.currentTarget.dataset.src !== this.data.imageSrc) return
      this.clearTimer(); this._code = null
      this.setData({ imageSrc: '', imageReady: false, imageError: true, message: '图片未能显示，请重新读取现有码；不要重复申请交付。' })
    },
    async act(event) {
      const action = event.currentTarget.dataset.action
      if (this.data.isMock || this.data.busy || this.data.loading || this._operation || !['request','refresh','revoke'].includes(action)) return
      if (action === 'request' ? !this.data.canRequest : action === 'refresh' ? !this.data.canRefresh : !this.data.canRevoke) return
      const captured = session.snapshot(), seq = this._seq, version = this._knownVersion, token = {}
      this._operation = token; this.clearTimer(); this.setData({ busy: true })
      const content = { request: '确认已到达约定地点？将申请交付并获取本任务交付码。', refresh: '生成新码会立即使旧码失效。请让发布者扫描新码。', revoke: '停用后当前码立即失效，任务仍为待确认。后续需主动生成新码。' }[action]
      const confirmed = await new Promise(resolve => wx.showModal({ title: { request: '申请交付', refresh: '重新生成交付码？', revoke: '停用交付码？' }[action], content, success: r => resolve(r.confirm === true), fail: () => resolve(false) }))
      if (!this.current(captured, seq)) { if (this._operation === token) this._operation = null; return }
      if (!confirmed) { this._operation = null; this.setData({ busy: false }); if (this._code) this.tick(captured, seq); return }
      this.clearCode(); this.setData({ notice: '' })
      let notice = ''
      try {
        if (action === 'request') await services.tasks.markDelivered(this.data.taskId, captured.userId)
        else if (action === 'refresh') await services.tasks.refreshDeliveryCode(this.data.taskId, captured.userId, version)
        else await services.tasks.revokeDeliveryCode(this.data.taskId, captured.userId, version)
        notice = { request: '交付申请已处理，已重新查询当前任务和码。', refresh: '刷新请求已处理，请使用重新查询后的交付码。', revoke: '交付码已停用；任务仍待确认。' }[action]
      } catch (error) { notice = error.outcome === 'unknown' ? '操作结果待确认，已停止展示旧码并重新查询。请勿连续提交。' : error.message || '操作未完成，请查询当前状态。' }
      if (this.current(captured, seq)) {
        await this.reload()
        if (!this._dead && this._visible && session.isCurrent(captured)) this.setData({ notice })
      }
      if (this._operation === token) this._operation = null
      if (!this._dead && this._visible && session.isCurrent(captured)) this.setData({ busy: false })
    }
  }
}))
