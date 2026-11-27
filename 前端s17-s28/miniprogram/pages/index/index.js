const { createScaffold } = require('../../utils/scaffold')
const navigation = require('../../utils/navigation')
const { createUiPreview } = require('../../config/ui-preview')
const taskServices = require('../../services/index').tasks
const { taskTypes } = require('../../config/task-types')

const { session } = require('../../utils/session')
const PAGE_SIZE = 4
const TYPE_OPTIONS = [{ value: '', label: '全部' }].concat(taskTypes.map(value => ({ value, label: value })))
const SORT_OPTIONS = [
  { value: 'newest', label: '最新发布' },
  { value: 'oldest', label: '最早发布' }
]

function uniqueById(items) {
  const seen = Object.create(null)
  return items.filter(item => {
    if (!item || typeof item.id !== 'string' || seen[item.id]) return false
    seen[item.id] = true
    return true
  })
}

function normalizePage(result) {
  if (!result || !Array.isArray(result.items) || typeof result.hasMore !== 'boolean') {
    throw new Error('任务列表返回格式无效。')
  }
  return result
}

Page(createScaffold({
  recover() { return this.loadFirstPage({ preserveOnFailure: true, refreshing: true }) },
  data: {
    tasks: [], typeOptions: TYPE_OPTIONS, sortOptions: SORT_OPTIONS,
    selectedType: '', sortOrder: 'newest', pageState: 'loading',
    hasMore: false, nextCursor: null, isLoadingMore: false,
    isRefreshing: false, refreshError: false, moreError: false,
    uiPreview: null, previewState: 'error', previewRetries: 0
  },
  clearAccountData() {
    if (this.data.isMock) return
    this._filterGeneration = (this._filterGeneration || 0) + 1
    this._requestSequence = (this._requestSequence || 0) + 1
    this._activeMoreRequestId = null
    this.setData({ tasks: [], hasMore: false, nextCursor: null, isLoadingMore: false, isRefreshing: false, refreshError: false, moreError: false, pageState: 'auth' })
  },
  load(query) {
    this._dead = false; this._visible = true; this._shownOnce = false
    this._filterGeneration = 0
    this._requestSequence = 0
    this._activeMoreRequestId = null
    this._pullDownRequestId = null
    if (!this.data.isMock) {
      this.setData({ sortOptions: [SORT_OPTIONS[0]] })
      this._unsubscribe = session.subscribe(() => {
        if (this._reloadQueued) return
        this._reloadQueued = true
        Promise.resolve().then(() => { this._reloadQueued = false; if (!this._dead && this._visible) this.loadFirstPage({ clear: true }) })
      })
    }
    this.setData({ uiPreview: this.data.isMock && query.uiPreview === 'all' ? createUiPreview() : null })
    return this.loadFirstPage({ clear: true })
  },
  unload() {
    this._dead = true
    if (this._unsubscribe) this._unsubscribe()
    this._filterGeneration++
    this._requestSequence++
    this._activeMoreRequestId = null
  },
  pullDownRefresh() {
    return this.loadFirstPage({ preserveOnFailure: true, refreshing: true })
  },
  reachBottom() { return this.loadMore() },
  methods: {
    onShow() { this._visible = true; if (!this._shownOnce) { this._shownOnce = true; return } if (!this.data.isMock) return this.loadFirstPage({ clear: true }) },
    onHide() { this._visible = false; if (!this.data.isMock) { this._requestSequence++; this._activeMoreRequestId = null; this.setData({ isRefreshing: false, isLoadingMore: false }) } },
    openTask(event) {
      const taskId = event.detail && event.detail.taskId || event.currentTarget.dataset.taskId
      navigation.open('detail', taskId)
    },
    chooseType(event) {
      const value = event.currentTarget.dataset.value || ''
      if (value === this.data.selectedType) return
      this._filterGeneration++
      this.setData({ selectedType: value, tasks: [], nextCursor: null, hasMore: false, moreError: false })
      return this.loadFirstPage({ clear: true })
    },
    chooseSort(event) {
      const value = event.currentTarget.dataset.value
      if (value !== 'newest' && value !== 'oldest' || !this.data.isMock && value !== 'newest' || value === this.data.sortOrder) return
      this._filterGeneration++
      this.setData({ sortOrder: value, tasks: [], nextCursor: null, hasMore: false, moreError: false })
      return this.loadFirstPage({ clear: true })
    },
    refreshList() { return this.loadFirstPage({ preserveOnFailure: true, refreshing: true }) },
    loadFirstPage(options) {
      const settings = options || {}
      const captured = session.snapshot()
      if (!this.data.isMock && !captured.authenticated) { this._filterGeneration++; this._requestSequence++; this._activeMoreRequestId = null; this.setData({ tasks: [], hasMore: false, nextCursor: null, isLoadingMore: false, isRefreshing: false, refreshError: false, moreError: false, pageState: 'auth' }); if (typeof wx.stopPullDownRefresh === 'function') wx.stopPullDownRefresh(); return Promise.resolve() }
      const generation = this._filterGeneration
      const requestId = ++this._requestSequence
      const preserveOnFailure = !!settings.preserveOnFailure && this.data.tasks.length > 0
      const query = {
        typeName: this.data.selectedType,
        sortOrder: this.data.sortOrder,
        pageSize: PAGE_SIZE
      }
      this._activeMoreRequestId = null
      this._pullDownRequestId = settings.refreshing ? requestId : null
      this.setData({
        pageState: preserveOnFailure ? '' : 'loading',
        tasks: preserveOnFailure ? this.data.tasks : [],
        isRefreshing: !!settings.refreshing,
        isLoadingMore: false,
        refreshError: false,
        moreError: false,
        hasMore: preserveOnFailure ? this.data.hasMore : false,
        nextCursor: preserveOnFailure ? this.data.nextCursor : null
      })
      return taskServices.listPublic(query).then(value => {
        if (this._dead || generation !== this._filterGeneration || requestId !== this._requestSequence || !this.data.isMock && !session.isCurrent(captured)) return
        const page = normalizePage(value)
        this.setData({
          tasks: uniqueById(page.items),
          nextCursor: page.nextCursor || null,
          hasMore: page.hasMore,
          pageState: page.items.length ? '' : 'empty',
          isRefreshing: false,
          refreshError: false,
          moreError: false
        })
      }).catch(() => {
        if (this._dead || generation !== this._filterGeneration || requestId !== this._requestSequence || !this.data.isMock && !session.isCurrent(captured)) return
        if (preserveOnFailure) {
          this.setData({ pageState: '', isRefreshing: false, refreshError: true })
        } else {
          this.setData({ pageState: 'error', isRefreshing: false })
        }
      }).finally(() => {
        if (this._pullDownRequestId === requestId) {
          this._pullDownRequestId = null
          if (typeof wx !== 'undefined' && wx.stopPullDownRefresh) wx.stopPullDownRefresh()
        }
      })
    },
    loadMore() {
      const captured = session.snapshot()
      if (!this.data.isMock && !captured.authenticated) return this.loadFirstPage({ clear: true })
      if (!this.data.hasMore || !this.data.nextCursor || this._activeMoreRequestId !== null) return Promise.resolve()
      const generation = this._filterGeneration
      const requestId = ++this._requestSequence
      this._activeMoreRequestId = requestId
      const query = {
        typeName: this.data.selectedType,
        sortOrder: this.data.sortOrder,
        pageSize: PAGE_SIZE,
        cursor: this.data.nextCursor
      }
      this.setData({ isLoadingMore: true, moreError: false })
      return taskServices.listPublic(query).then(value => {
        if (this._dead || generation !== this._filterGeneration || requestId !== this._requestSequence || !this.data.isMock && !session.isCurrent(captured)) return
        const page = normalizePage(value)
        this.setData({
          tasks: uniqueById(this.data.tasks.concat(page.items)),
          nextCursor: page.nextCursor || null,
          hasMore: page.hasMore,
          isLoadingMore: false,
          moreError: false
        })
      }).catch(() => {
        if (this._dead || generation !== this._filterGeneration || requestId !== this._requestSequence || !this.data.isMock && !session.isCurrent(captured)) return
        this.setData({ isLoadingMore: false, moreError: true })
      }).finally(() => {
        if (this._activeMoreRequestId === requestId) this._activeMoreRequestId = null
      })
    },
    retryList() {
      if (this.data.moreError) return this.loadMore()
      return this.loadFirstPage({ preserveOnFailure: this.data.tasks.length > 0 })
    },
    handleStateAction(event) {
      if (event.detail.state === 'empty') return navigation.open('publish')
      return this.retryList()
    },
    retryPreview() {
      if (!this.data.isMock || !this.data.uiPreview) return
      this.setData({ previewState: 'empty', previewRetries: this.data.previewRetries + 1 })
    }
  }
}))
