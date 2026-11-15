// 仅虚构公开字段；任务联系方式和私密说明不得混入大厅共享数据。
const tasks = [
  { id: 'demo-001', title: '帮取校园快递站包裹', typeName: '快递帮取', pickupLocation: '北门快递站', deliveryLocation: '第二教学楼门口', rewardText: '3.00', deadlineText: '样例：今天18:30前', statusText: '待接单', description: '一件轻便包裹，送到约定的公共交接区域。' },
  { id: 'demo-002', title: '帮带一份课程资料', typeName: '资料帮取', pickupLocation: '校内打印店', deliveryLocation: '图书馆入口', rewardText: '2.00', deadlineText: '样例：今天19:00前', statusText: '待接单', description: '一份装订资料，具体交接信息在成功接单后按权限查看。' }
]
const taskTypes = ['快递帮取', '外卖帮取', '资料帮取', '轻量搬运', '其他']
function listPublicExamples() { return tasks.map(task => Object.assign({}, task)) }
function findPublicExample(id) {
  const task = tasks.find(item => item.id === id)
  return task ? Object.assign({}, task) : null
}
module.exports = { taskTypes, listPublicExamples, findPublicExample }
