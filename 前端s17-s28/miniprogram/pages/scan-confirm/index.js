const { createScaffold } = require('../../utils/scaffold')
const { isTaskId } = require('../../utils/navigation')
const { validScannedPayload } = require('../../services/delivery-confirmation')
const { isKnown, isTerminal } = require('../../utils/task-state')
const services = require('../../services/index')
const { session } = require('../../utils/session')
const EMPTY = { task: null, pageState: 'loading', message: '', notice: '', busy: false, canScan: false,
  review: null, canConfirm: false, remainingText: '', completed: false, settlementText: '', resultUnknown: false }
Page(createScaffold({
  recover() { if (!this._scan && !this._operation) return this.reload('网络已恢复，请重新核对当前任务；确认操作不会自动重发。') },
  data: { taskId: '', ...EMPTY },
  clearAccountData() {
    this._seq = (this._seq || 0) + 1; this._scan = null; this._operation = null; this._receipt = null; this._uncertain = false
    this.clearReview(); this.setData({ ...EMPTY })
  },
  load(query) {
    this._dead = false; this._visible = true; this._shown = false; this._seq = 0
    this.setData({ taskId: isTaskId(query.taskId) ? query.taskId : '' })
    this._unsubscribe = session.subscribe(() => {
      const seq = this._seq
      Promise.resolve().then(() => { if (!this._dead && this._visible && seq === this._seq) this.reload() })
    })
    return this.reload()
  },
  unload() { this._dead = true; this._seq++; this.clearReview(); this._scan = null; this._operation = null; this._receipt = null; if (this._unsubscribe) this._unsubscribe() },
  methods: {
    onShow() {
      this._visible = true
      if (!this._shown) { this._shown = true; return }
      if (this._scan) { if (this._scan.result) return this.consumeScan(this._scan); return }
      return this.reload()
    },
    onHide() {
      this._visible = false; this._seq++; this._operation = null; this._receipt = null
      this.clearReview(); this.setData({ ...EMPTY, busy: !!this._scan, resultUnknown: !!this._uncertain })
      // 原生扫码可能临时遮住页面；返回后重新检查任务，不沿用旧核对结果。
      if (this._scan) this._scan.result = null
    },
    clearReview() {
      if (this._timer) clearTimeout(this._timer)
      this._timer = null; this._payload = ''; this._verified = null
      if (!this._dead) this.setData({ review: null, canConfirm: false, remainingText: '' })
    },
    current(captured, seq) { return !this._dead && this._visible && seq === this._seq && session.isCurrent(captured) },
    taskView(detail) { return { id: detail.id, title: detail.title, rewardText: detail.rewardText, status: detail.status,
      pickupLocation: detail.pickupLocation, deliveryLocation: detail.deliveryLocation } },
    eligible(detail) { return detail.id === this.data.taskId && detail.relation === 'owner' && detail.status === '待确认' && !detail.deliveryPaused },
    async reload(notice) {
      if (this._dead || !this._visible || this._scan) return
      const seq = ++this._seq, captured = session.snapshot()
      this.clearReview(); this.setData({ ...EMPTY, busy: true, resultUnknown: !!this._uncertain })
      if (this.data.isMock) { this.setData({ pageState: 'mock', busy: false, message: '演示模式不调用相机，也不核验或完成真实任务。' }); return }
      if (!isTaskId(this.data.taskId) || !/^[1-9]\d{0,15}$/.test(this.data.taskId)) { this.setData({ pageState: 'unavailable', busy: false, message: '缺少有效任务编号，请从任务详情重新打开。' }); return }
      if (!captured.authenticated) { this.setData({ pageState: 'auth', busy: false, message: '请使用本任务发布者的微信登录。' }); return }
      try {
        const detail = await services.tasks.getPublic(this.data.taskId)
        if (!this.current(captured, seq)) return
        const wasUncertain = this._uncertain; this._uncertain = false
        const completed = detail.relation === 'owner' && detail.status === '已完成'
        this.setData({ task: this.taskView(detail), pageState: '', canScan: this.eligible(detail), completed, resultUnknown: false,
          settlementText: completed && this._receipt && this._receipt.taskId === detail.id && this._receipt.settlement.amountText === detail.rewardText ? this._receipt.settlement.amountText : '',
          message: completed ? '服务端当前任务已完成。' : !isKnown(detail.status) ? '当前状态暂时无法识别，请返回详情刷新确认。' : isTerminal(detail.status) ? '任务已结束，不能再扫码或确认交付。' : detail.relation !== 'owner' ? '仅本任务发布者可扫码核对并确认。' : detail.deliveryPaused ? '取消申请正在审核，交付已暂停。' : detail.status !== '待确认' ? '当前进度不允许确认交付，请等待接单者申请交付。' : '请当面扫描接单者展示的本任务有效交付码，核对后再确认完成。',
          notice: typeof notice === 'string' ? notice : wasUncertain ? (completed ? '已查询到服务端完成结果。' : '最新查询尚未显示完成。继续操作前，请重新扫码核对；不会自动重发确认。') : '' })
      } catch (_) {
        if (this.current(captured, seq)) this.setData({ pageState: session.snapshot().authenticated ? 'error' : 'auth',
          message: this._uncertain ? '确认结果仍待查询，请保留当前任务编号，稍后重查。' : '任务暂时无法读取，请重试。' })
      } finally { if (this.current(captured, seq)) this.setData({ busy: false }) }
    },
    startScan() {
      if (this.data.isMock || this.data.busy || !this.data.canScan || this._scan || this._operation || this._dead || !this._visible) return
      this.clearReview(); const token = { captured: session.snapshot(), result: null }
      this._scan = token; this.setData({ busy: true, canScan: false, notice: '' })
      const finish = result => {
        if (this._dead || this._scan !== token || !session.isCurrent(token.captured)) return
        const stack = getCurrentPages()
        if (!stack.length || stack[stack.length - 1] !== this) { this._scan = null; return }
        token.result = result
        if (this._visible) this.consumeScan(token)
      }
      try {
        wx.scanCode({ onlyFromCamera: true, scanType: ['qrCode'],
          success: result => finish({ kind: 'success', value: result && result.scanType === 'QR_CODE' ? result.result : '' }),
          fail: error => finish({ kind: error && /cancel/i.test(error.errMsg || '') ? 'cancel' : 'failure' }) })
      } catch (_) { finish({ kind: 'failure' }) }
    },
    async consumeScan(token) {
      if (this._scan !== token || !this._visible || this._dead || !session.isCurrent(token.captured)) return
      const result = token.result; token.result = null; this._scan = null
      if (!result) return
      if (result.kind !== 'success') return this.reload(result.kind === 'cancel' ? '已取消扫码，未提交核对或确认。' : '未能完成扫码。请检查相机权限后重试，未提交确认。')
      if (!validScannedPayload(result.value, this.data.taskId)) return this.reload('这不是当前任务的交付码，请让接单者展示当前有效码。')
      const captured = token.captured, seq = ++this._seq, payload = result.value
      this.setData({ busy: true, canScan: false, notice: '', message: '正在核对交付码，尚未确认完成。' })
      try {
        const before = await services.tasks.getPublic(this.data.taskId)
        if (!this.current(captured, seq)) return
        if (!this.eligible(before)) { await this.reload('任务进度或权限已变化，请按最新状态操作。'); return }
        const verified = await services.tasks.verifyDeliveryCode(this.data.taskId, captured.userId, payload)
        if (!this.current(captured, seq)) return
        const latest = await services.tasks.getPublic(this.data.taskId)
        if (!this.current(captured, seq)) return
        if (!this.eligible(latest) || verified.status !== '待确认') { await this.reload('已停止核对，请查看任务最新状态。'); return }
        if (verified.title !== latest.title || verified.rewardText !== latest.rewardText || verified.expiresAt <= Date.now()) {
          await this.reload('核对信息已变化或交付码已到期，请重新扫码。'); return
        }
        this._payload = payload; this._verified = verified
        this.setData({ task: this.taskView(latest), pageState: '', busy: false, review: { title: verified.title, rewardText: verified.rewardText },
          message: '交付码核对通过，尚未确认完成。请实际收到物品并核对下方信息。', canScan: true })
        this.tick(captured, seq)
      } catch (error) {
        if (this.current(captured, seq)) await this.reload(error && error.message || '交付码核对失败，请重新查询任务。')
      } finally { if (this.current(captured, seq)) this.setData({ busy: false }) }
    },
    tick(captured, seq) {
      if (!this.current(captured, seq) || !this._verified || !this._payload) return
      const remaining = Math.max(0, Math.floor((this._verified.expiresAt - Date.now()) / 1000))
      if (!remaining) { this.clearReview(); this.setData({ notice: '交付码已到期，请重新扫码核对。', canScan: true }); return }
      this.setData({ remainingText: Math.floor(remaining / 60) + ':' + String(remaining % 60).padStart(2, '0'), canConfirm: true })
      this._timer = setTimeout(() => this.tick(captured, seq), 1000)
    },
    async confirmTask() {
      if (this.data.isMock || this.data.busy || !this.data.canConfirm || !this._payload || !this._verified || this._operation || this._dead || !this._visible) return
      const captured = session.snapshot(), seq = this._seq, verified = this._verified, payload = this._payload, token = {}
      this._operation = token; this.setData({ busy: true })
      const confirmed = await new Promise(resolve => wx.showModal({ title: '确认收到物品并完成任务？',
        content: '请先实际收到物品并核对任务。确认后任务完成，服务端记录模拟结算，不会发生真实付款。',
        success: result => resolve(result.confirm === true), fail: () => resolve(false) }))
      if (!this.current(captured, seq) || this._operation !== token) return
      if (!confirmed) { this._operation = null; this.setData({ busy: false }); return }
      let sent = false, notice = ''
      try {
        const latest = await services.tasks.getPublic(this.data.taskId)
        if (!this.current(captured, seq) || this._operation !== token) return
        if (!this.eligible(latest) || verified.expiresAt <= Date.now() || latest.title !== verified.title || latest.rewardText !== verified.rewardText) {
          notice = '任务进度或交付码已变化，未提交确认，请重新扫码核对。'
        } else {
          this.clearReview(); sent = true; this._uncertain = true
          const receipt = await services.tasks.confirmCompletion(this.data.taskId, captured.userId, payload)
          if (!this.current(captured, seq)) return
          this._receipt = receipt; this._uncertain = false
          notice = '服务端已返回完成结果，以下为重新查询后的任务状态。'
        }
      } catch (error) {
        if (!this.current(captured, seq)) return
        this._uncertain = sent && error.outcome === 'unknown'
        notice = this._uncertain ? '确认结果待查询，已停止展示核对结果；不会自动重发确认。' : sent ? error.message : '最新任务未能读取，未提交确认。请查询后重新扫码。'
      } finally {
        if (this._operation === token) this._operation = null
        if (this.current(captured, seq)) await this.reload(notice)
      }
    }
  }
}))
