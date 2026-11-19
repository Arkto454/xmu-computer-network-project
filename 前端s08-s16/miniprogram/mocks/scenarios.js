const definitions = Object.freeze({
  normal: Object.freeze({ label: '正常样例', delayMs: 0, failure: false, codeExpired: false }),
  delayed: Object.freeze({ label: '延迟响应', delayMs: 800, failure: false, codeExpired: false }),
  failure: Object.freeze({ label: '请求失败', delayMs: 0, failure: true, codeExpired: false }),
  pending: Object.freeze({ label: '待确认状态', delayMs: 0, failure: false, codeExpired: false }),
  codeExpired: Object.freeze({ label: '交付码已过期', delayMs: 0, failure: false, codeExpired: true })
})

function createScenarioController(initialName) {
  let currentName = definitions[initialName] ? initialName : 'normal'
  return {
    list() { return Object.keys(definitions).map(key => ({ key, label: definitions[key].label })) },
    current() { return { key: currentName, label: definitions[currentName].label } },
    get() { return definitions[currentName] },
    set(name) {
      if (!definitions[name]) throw new Error('未知Mock场景。')
      currentName = name
      return this.current()
    },
    reset() { currentName = 'normal'; return this.current() }
  }
}

module.exports = { definitions, createScenarioController }
