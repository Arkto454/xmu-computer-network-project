const { createScaffold } = require('../../utils/scaffold')
const navigation = require('../../utils/navigation')
Page(createScaffold({ methods: { openLogin() { navigation.open('login') } } }))
