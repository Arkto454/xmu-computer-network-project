const { createScaffold } = require('../../utils/scaffold')
const { isTaskId } = require('../../utils/navigation')
Page(createScaffold({data: {taskId: ''}, load(query) {this.setData({taskId: isTaskId(query.taskId) ? query.taskId : ''})}}))
