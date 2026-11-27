const { session } = require('../utils/session')
function createNetworkRecovery(options) {
  const config = options || {}, store = config.session || session
  let active = null, connected = null, foreground = true, started = false, listener = null, platform = null
  const records = new Map()
  function eligible(record, captured) {
    return !!record && active === record && foreground && connected === true && records.get(record.page) === record &&
      captured.mode === 'real' && captured.authenticated && store.isCurrent(captured)
  }
  function dispatch() {
    const record = active, captured = store.snapshot()
    if (!eligible(record, captured) || record.queued || record.running) return
    record.queued = true
    Promise.resolve().then(() => {
      record.queued = false
      if (!eligible(record, captured) || record.running) return
      record.running = true
      return Promise.resolve().then(() => {
        if (eligible(record, captured)) return record.refresh.call(record.page)
      }).catch(() => { /* 页面负责显示读取失败；恢复监听不改变业务结果。 */ }).finally(() => { record.running = false })
    })
  }
  return {
    start(api) {
      if (started) return
      platform = api
      if (!platform || typeof platform.onNetworkStatusChange !== 'function') return
      const currentListener = value => {
        if (!started || listener !== currentListener || !value || typeof value.isConnected !== 'boolean') return
        const wasOffline = connected === false
        connected = value.isConnected
        if (wasOffline && connected) dispatch()
      }
      listener = currentListener
      started = true
      try { platform.onNetworkStatusChange(listener) } catch (_) { started = false; listener = null; platform = null }
    },
    stop() {
      started = false; connected = null
      if (platform && listener && typeof platform.offNetworkStatusChange === 'function') {
        try { platform.offNetworkStatusChange(listener) } catch (_) { /* 不移除其他模块的监听。 */ }
      }
      listener = null; platform = null
    },
    watch(page, refresh) {
      const record = { page, refresh, queued: false, running: false }
      records.set(page, record)
      return () => { if (records.get(page) === record) records.delete(page); if (active === record) active = null }
    },
    show(page) { active = records.get(page) || null },
    hide(page) { if (active && active.page === page) active = null },
    foreground(value) { foreground = value === true },
    reset() { active = null }
  }
}
const networkRecovery = createNetworkRecovery()
module.exports = { createNetworkRecovery, networkRecovery }
