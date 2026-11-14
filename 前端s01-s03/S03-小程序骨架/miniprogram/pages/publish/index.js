const { createScaffold } = require('../../utils/scaffold')
const { taskTypes } = require('../../config/demo')
Page(createScaffold({ data: { taskTypeHint: taskTypes.join('、') } }))
