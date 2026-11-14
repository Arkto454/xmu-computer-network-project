const { getEnvironment } = require('./config/env')
App({ globalData: { environment: getEnvironment() } })
