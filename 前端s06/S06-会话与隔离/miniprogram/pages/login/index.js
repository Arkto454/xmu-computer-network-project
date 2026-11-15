const { createScaffold } = require('../../utils/scaffold')
const { loginNavigation } = require('../../utils/login-navigation')
Page(createScaffold({
  data: { returnTargetLabel: '' },
  load() {
    this._loginVisit = loginNavigation.enter()
    this.setData({ returnTargetLabel: loginNavigation.getTargetLabel() })
  },
  unload() { loginNavigation.leave(this._loginVisit) }
}))
