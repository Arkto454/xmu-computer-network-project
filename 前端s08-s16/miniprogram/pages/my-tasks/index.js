const { createScaffold } = require('../../utils/scaffold')
const { loginNavigation } = require('../../utils/login-navigation')
const navigation = require('../../utils/navigation')
const services = require('../../services/index')
const { session } = require('../../utils/session')
const { states } = require('../../utils/task-state')
const RELATIONS = ['published', 'accepted']
const STATUS_OPTIONS = [{ value: '', label: '全部状态' }].concat(states.map(value => ({ value, label: value })))
function context() {
  return { status: '', items: [], nextCursor: null, hasMore: false, pageState: 'loading', loaded: false, stale: true,
    loading: false, loadingMore: false, refreshError: false, moreError: false, sequence: 0 }
}
function normalizePage(result, status) {
  if (!result || !Array.isArray(result.items) || typeof result.hasMore !== 'boolean' ||
    result.hasMore && (typeof result.nextCursor !== 'string' || !result.nextCursor)) throw Error('Invalid page')
  const seen = Object.create(null), items = []
  result.items.forEach(row => {
    if (!row || !navigation.isTaskId(row.id) || typeof row.status !== 'string' || status && row.status !== status) throw Error('Invalid task')
    if (seen[row.id]) return
    seen[row.id] = true
    const item = { id: row.id, statusText: row.status, overdue: row.overdue === true }
    ;['title', 'typeName', 'pickupLocation', 'deliveryLocation', 'rewardText', 'deadlineText', 'publishTimeText'].forEach(key => { item[key] = String(row[key] == null ? '' : row[key]) })
    items.push(item)
  })
  return { items, hasMore: result.hasMore, nextCursor: result.hasMore ? result.nextCursor : null }
}
Page(createScaffold({
  data: { relationship: 'published', view: context(), statusOptions: STATUS_OPTIONS, statusIndex: 0, statusLabel: '全部状态',
    showPreview: false, mockAccount: '', mockScenarioIndex: 0,
    mockAccounts: [{ id: '', label: '未登录' }, { id: 'mock-a', label: '账号 A' }, { id: 'mock-b', label: '账号 B' }, { id: 'mock-c', label: '账号 C' }],
    mockScenarios: [{ key: 'normal', label: '正常' }, { key: 'delayed', label: '延迟' }, { key: 'failure', label: '失败' }] },
  clearAccountData() {
    this.stopPull()
    this._epoch = (this._epoch || 0) + 1
    this._contexts = { published: context(), accepted: context() }
    this.setData({ relationship: 'published', view: context(), statusIndex: 0, statusLabel: '全部状态', mockAccount: '' })
  },
  load() {
    this._dead = false; this._visible = true; this._shownOnce = false; this._epoch = 0; this._firstPromises = {}
    this._contexts = { published: context(), accepted: context() }
    this._unsubscribe = session.subscribe(() => {
      const epoch = this._epoch
      this.setData({ mockAccount: session.snapshot().userId || '' })
      Promise.resolve().then(() => { if (!this._dead && this._visible && epoch === this._epoch) this.loadFirstPage() })
    })
    this.setData({ mockAccount: session.snapshot().userId || '' })
    return this.loadFirstPage()
  },
  unload() {
    this.stopPull(); this._dead = true; this._epoch++
    if (this._unsubscribe) this._unsubscribe()
    if (this._previewScenarioChanged && services.scenarios) services.scenarios.reset()
  },
  pullDownRefresh() { return this.loadFirstPage({ pull: true }) },
  reachBottom() { return this.loadMore() },
  methods: {
    onShow() {
      this._visible = true
      if (!this._shownOnce) { this._shownOnce = true; return }
      return this.loadFirstPage()
    },
    onHide() {
      this.stopPull(); this._visible = false; this._epoch++
      RELATIONS.forEach(key => { const c = this._contexts[key]; c.stale = true; c.loading = false; c.loadingMore = false; c.sequence++ })
      if (this._previewScenarioChanged && services.scenarios) {
        services.scenarios.reset(); this._previewScenarioChanged = false; this.setData({ mockScenarioIndex: 0 })
      }
    },
    stopPull() {
      if (!this._pullToken) return
      this._pullToken = null
      if (!this._dead && this._visible && typeof wx.stopPullDownRefresh === 'function') wx.stopPullDownRefresh()
    },
    renderView() {
      if (this._dead) return
      const c = this._contexts[this.data.relationship], index = STATUS_OPTIONS.findIndex(item => item.value === c.status)
      this.setData({ view: c, statusIndex: index, statusLabel: STATUS_OPTIONS[index].label })
    },
    canQuery() { const s = session.snapshot(); return this.data.isMock && s.mode === 'mock' && s.authenticated },
    showGate() {
      const c = this._contexts[this.data.relationship]
      c.items = []; c.hasMore = false; c.nextCursor = null; c.loading = false; c.loadingMore = false
      c.pageState = this.data.isMock ? 'login' : 'unavailable'; this.renderView()
    },
    chooseRelationship(event) {
      const key = event.currentTarget.dataset.relationship
      if (!RELATIONS.includes(key) || key === this.data.relationship) return
      this.stopPull(); this.setData({ relationship: key }); this.renderView()
      if (!this.canQuery()) { this.showGate(); return }
      const c = this._contexts[key]
      if (!c.loaded || c.stale) return this.loadFirstPage()
    },
    chooseStatus(event) {
      const option = STATUS_OPTIONS[Number(event.detail.value)], c = this._contexts[this.data.relationship]
      if (!option || option.value === c.status) return
      this.stopPull(); c.sequence++
      this._contexts[this.data.relationship] = Object.assign(context(), { status: option.value })
      this.renderView(); return this.loadFirstPage()
    },
    loadFirstPage(options) {
      const settings = options || {}, key = this.data.relationship, c = this._contexts[key]
      if (this._dead || !this._visible) return Promise.resolve()
      if (!this.canQuery()) { this.showGate(); if (settings.pull && typeof wx.stopPullDownRefresh === 'function') wx.stopPullDownRefresh(); return Promise.resolve() }
      if (c.loading) { if (settings.pull) this._pullToken = c; return this._firstPromises[key] || Promise.resolve() }
      this.stopPull()
      const captured = session.snapshot(), epoch = this._epoch, sequence = ++c.sequence, preserve = c.loaded && c.items.length > 0
      c.loading = true; c.loadingMore = false; c.refreshError = false; c.moreError = false; c.pageState = preserve ? '' : 'loading'
      if (!preserve) { c.items = []; c.nextCursor = null; c.hasMore = false }
      if (settings.pull) this._pullToken = c
      this.renderView()
      const current = () => !this._dead && this._visible && epoch === this._epoch && this._contexts[key] === c && sequence === c.sequence && session.isCurrent(captured)
      const pending = services.tasks.listMine(captured.userId, key, { status: c.status, pageSize: 4 }).then(result => {
        if (!current()) return
        const page = normalizePage(result, c.status)
        Object.assign(c, page, { loaded: true, stale: false, loading: false, pageState: page.items.length ? '' : 'empty' })
      }).catch(() => {
        if (!current()) return
        Object.assign(c, { loading: false, stale: true, refreshError: preserve, pageState: preserve ? '' : 'error' })
      }).finally(() => {
        if (!current()) return
        if (this._pullToken === c) this.stopPull()
        if (this.data.relationship === key) this.renderView()
      })
      // Promise只存实例内部，不写入页面数据。
      this._firstPromises[key] = pending
      return pending
    },
    refreshList() { return this.loadFirstPage() },
    loadMore() {
      const key = this.data.relationship, c = this._contexts[key]
      if (this._dead || !this._visible || !this.canQuery() || c.loading || c.loadingMore || c.stale || !c.hasMore || !c.nextCursor) return Promise.resolve()
      const captured = session.snapshot(), epoch = this._epoch, sequence = ++c.sequence, cursor = c.nextCursor
      c.loadingMore = true; c.moreError = false; this.renderView()
      const current = () => !this._dead && this._visible && epoch === this._epoch && this._contexts[key] === c && sequence === c.sequence && session.isCurrent(captured)
      return services.tasks.listMine(captured.userId, key, { status: c.status, pageSize: 4, cursor }).then(result => {
        if (!current()) return
        const page = normalizePage(result, c.status)
        if (page.hasMore && page.nextCursor === cursor) throw Error('Non advancing cursor')
        const ids = new Set(c.items.map(item => item.id))
        c.items = c.items.concat(page.items.filter(item => !ids.has(item.id)))
        c.nextCursor = page.nextCursor; c.hasMore = page.hasMore; c.loadingMore = false
      }).catch(() => { if (current()) { c.loadingMore = false; c.moreError = true } }).finally(() => {
        if (current() && this.data.relationship === key) this.renderView()
      })
    },
    openTask(event) {
      const id = event.detail && event.detail.taskId
      if (this.canQuery() && this._contexts[this.data.relationship].items.some(item => item.id === id)) navigation.open('detail', id)
    },
    openLogin() { loginNavigation.open() },
    handleStateAction(event) {
      if (event.detail.state === 'login') return this.openLogin()
      if (event.detail.state === 'empty') return navigation.open(this.data.relationship === 'published' ? 'publish' : 'hall')
      return this.refreshList()
    },
    togglePreview() { if (this.data.isMock) this.setData({ showPreview: !this.data.showPreview }) },
    switchMockAccount(event) {
      if (!this.data.isMock) return
      const id = event.currentTarget.dataset.userId || ''
      if (!this.data.mockAccounts.some(account => account.id === id) || id === (session.snapshot().userId || '')) return
      if (id) session.completeLogin(session.beginLogin(), { mode: 'mock', userId: id, token: 'MOCK-ONLY-' + id }); else session.logout()
    },
    chooseMockScenario(event) {
      const index = Number(event.detail.value), scenario = this.data.mockScenarios[index]
      if (!this.data.isMock || !services.scenarios || !scenario) return
      services.scenarios.set(scenario.key); this._previewScenarioChanged = true
      RELATIONS.forEach(key => { const c = this._contexts[key]; c.sequence++; c.loading = false; c.loadingMore = false; c.stale = true })
      this.setData({ mockScenarioIndex: index }); return this.refreshList()
    }
  }
}))
