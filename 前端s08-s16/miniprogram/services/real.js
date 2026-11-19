const { createRealTaskService } = require('./real-tasks')
const { getEnvironment } = require('../config/env')
const { session } = require('../utils/session')
// 引入模块不联网；只接已确认的三A能力，不以Mock填补缺失接口。
module.exports = { tasks: createRealTaskService({ session, baseUrl: getEnvironment().httpBaseUrl }) }
