const { createScaffold } = require('../../utils/scaffold')
const services = require('../../services/index')
const { session } = require('../../utils/session')
const { loginNavigation } = require('../../utils/login-navigation')
const navigation = require('../../utils/navigation')
const EMPTY = { items: [], totalAmountText: '', total: null, pageState: 'loading', loading: false, loadingMore: false,
  hasMore: false, nextPage: null, refreshError: false, moreError: false, stale: false, notice: '' }
Page(createScaffold({
  recover() { this._seq++; this.setData({ loading: false, loadingMore: false }); return this.refresh() },
  data: { ...EMPTY },
  clearAccountData() {
    this.stopPull(); this._seq = (this._seq || 0) + 1; this._loaded = false; this._pending = null
    this.setData({ ...EMPTY })
  },
  load() {
    this._dead = false; this._visible = true; this._shown = false; this._seq = 0; this._loaded = false
    this._unsubscribe = session.subscribe(() => {
      const seq = this._seq
      Promise.resolve().then(() => { if (!this._dead && this._visible && seq === this._seq) this.refresh() })
    })
    return this.refresh()
  },
  unload() { this.stopPull(); this._dead = true; this._seq++; if (this._unsubscribe) this._unsubscribe() },
  pullDownRefresh() { return this.refresh({ pull: true }) },
  reachBottom() { return this.loadMore() },
  methods: {
    onShow() { this._visible = true; if (!this._shown) { this._shown = true; return } return this.refresh() },
    onHide() {
      this.stopPull(); this._visible = false; this._seq++; this._pending = null; this._loaded = false
      this.setData({ ...EMPTY })
    },
    stopPull() {
      if (!this._pull) return
      this._pull = false
      if (!this._dead && this._visible) wx.stopPullDownRefresh()
    },
    current(captured, seq) { return !this._dead && this._visible && seq === this._seq && session.isCurrent(captured) },
    canQuery() { const s = session.snapshot(); return s.authenticated && s.mode === (this.data.isMock ? 'mock' : 'real') },
    refresh(options) {
      if (this._dead || !this._visible) return Promise.resolve()
      if (options && options.pull) this._pull = true
      if (this.data.loading) return this._pending || Promise.resolve()
      const seq = ++this._seq, captured = session.snapshot(), preserve = this._loaded
      if (!this.canQuery()) {
        this.setData({ ...EMPTY, pageState: 'login' }); this.stopPull(); return Promise.resolve()
      }
      this.setData({ loading: true, loadingMore: false, refreshError: false, moreError: false, stale: true,
        pageState: preserve ? '' : 'loading', notice: preserve ? '正在更新模拟收益，当前仍为上次查询结果。' : '' })
      const pending = services.tasks.listEarnings(captured.userId, { page: 1, size: 4 }).then(result => {
        if (!this.current(captured, seq)) return
        this._loaded = true
        this.setData({ items: result.items, totalAmountText: result.totalAmountText, total: result.total, nextPage: result.nextPage,
          hasMore: result.hasMore, pageState: result.items.length ? '' : 'empty', stale: false, notice: '' })
      }).catch(() => {
        if (!this.current(captured, seq)) return
        this.setData({ pageState: preserve ? '' : 'error', refreshError: preserve, stale: true,
          notice: preserve ? '刷新失败，已保留上次汇总和明细，内容可能已变化。请刷新成功后再继续翻页。' : '' })
      }).finally(() => {
        if (this.current(captured, seq)) { this.setData({ loading: false }); this.stopPull(); this._pending = null }
      })
      this._pending = pending
      return pending
    },
    loadMore() {
      if (this._dead || !this._visible || !this.canQuery() || this.data.loading || this.data.loadingMore || this.data.stale || !this.data.hasMore || !this.data.nextPage) return Promise.resolve()
      const captured = session.snapshot(), seq = ++this._seq, page = this.data.nextPage
      this.setData({ loadingMore: true, moreError: false })
      return services.tasks.listEarnings(captured.userId, { page, size: 4 }).then(result => {
        if (!this.current(captured, seq)) return
        const ids = new Set(this.data.items.map(row => row.taskId)), last = this.data.items[this.data.items.length - 1], first = result.items[0]
        const overlaps = result.items.some(row => ids.has(row.taskId))
        const orderChanged = last && first && (Date.parse(first.settledAt) > Date.parse(last.settledAt))
        const combined = this.data.items.concat(result.items)
        const loadedCents = combined.reduce((sum, row) => sum + Number(row.amountText.replace('.', '')), 0), totalCents = Number(result.totalAmountText.replace('.', ''))
        const amountMismatch = !Number.isSafeInteger(loadedCents) || loadedCents > totalCents || !result.hasMore && (combined.length !== result.total || loadedCents !== totalCents)
        if (result.total !== this.data.total || result.totalAmountText !== this.data.totalAmountText || overlaps || orderChanged || amountMismatch || result.nextPage !== null && result.nextPage <= page) {
          this.setData({ stale: true, moreError: false, notice: '结算记录已变化，已停止翻页。请刷新后查看最新汇总和明细。' }); return
        }
        this.setData({ items: combined, nextPage: result.nextPage, hasMore: result.hasMore })
      }).catch(() => {
        if (this.current(captured, seq)) this.setData({ moreError: true })
      }).finally(() => { if (this.current(captured, seq)) this.setData({ loadingMore: false }) })
    },
    openTask(event) {
      if (!this.canQuery() || this._dead || !this._visible) return
      const id = event.currentTarget.dataset.taskId
      if (this.data.items.some(row => row.taskId === id)) navigation.open('detail', id)
    },
    stateAction(event) { if (event.detail.state === 'login') return loginNavigation.open(); return this.refresh() }
  }
}))
