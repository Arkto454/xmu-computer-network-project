const { createScaffold } = require('../../utils/scaffold')
const { findPublicExample } = require('../../config/demo')
const { isTaskId } = require('../../utils/navigation')
Page(createScaffold({
 data: {taskId: '', task: null, message: ''},
 load(query) {
  if (!isTaskId(query.taskId)) {this.setData({message: '缺少有效任务编号，请返回大厅。'}); return}
  const task = this.data.isMock ? findPublicExample(query.taskId) : null
  this.setData({taskId: query.taskId, task, message: task ? '' : (this.data.isMock ? '没有这个样例任务，请返回大厅。' : '任务服务尚未接入，暂不能查询详情。')})
 }
}))
