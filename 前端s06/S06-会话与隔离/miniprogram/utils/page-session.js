const { session } = require('./session')

function createPageSession(page, options) {
  const config = options || {}
  const store = config.session || session
  let active = true
  const removeCleanup = store.onClear(() => {
    if (active && typeof config.reset === 'function') config.reset.call(page)
  })
  const unsubscribe = store.subscribe(value => {
    if (active && typeof page.setData === 'function') page.setData({ session: value })
  })
  page.setData({ session: store.snapshot() })
  return {
    capture() { return store.snapshot() },
    guard(captured, callback) {
      return function guarded() {
        if (!active || !store.isCurrent(captured)) return false
        callback.apply(page, arguments)
        return true
      }
    },
    dispose() {
      active = false
      removeCleanup()
      unsubscribe()
    }
  }
}
module.exports = { createPageSession }
