const { createScaffold } = require('../../utils/scaffold')
const { loginNavigation } = require('../../utils/login-navigation')
Page(createScaffold({ methods: { openLogin() { loginNavigation.open() } } }))
