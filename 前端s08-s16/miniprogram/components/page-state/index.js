const states = {
  loading: { title: '正在加载', description: '请稍候。', action: '', marker: '…' },
  empty: { title: '暂无任务', description: '暂时没有符合条件的任务，可以稍后再来看看。', action: '', marker: '—' },
  error: { title: '加载失败', description: '暂时无法获取内容，请检查网络后重试。', action: '重新加载', marker: '!' },
  unknown: { title: '结果待确认', description: '尚未确认原操作结果，请先查询，不要重复提交。', action: '查询原结果', marker: '?' },
  login: { title: '尚未登录', description: '登录后可查看自己的任务。', action: '查看登录页', marker: '人' },
  unavailable: { title: '服务尚未接入', description: '当前功能仍在准备，暂时无法查询。', action: '', marker: '—' }
}
Component({
  properties: {
    state: { type: String, value: 'loading' }, title: { type: String, value: '' },
    description: { type: String, value: '' }, actionText: { type: String, value: '' },
    busy: { type: Boolean, value: false }
  },
  data: { view: states.loading, tone: 'loading', pending: true },
  observers: {
    state(value) {
      const known = Object.prototype.hasOwnProperty.call(states, value)
      this.setData({ view: known ? states[value] : states.unavailable, tone: known ? value : 'unavailable', pending: value === 'loading' })
    }
  },
  methods: {
    act() {
      if (this.properties.busy || this.data.pending) return
      this.triggerEvent('action', { state: this.properties.state })
    }
  }
})
