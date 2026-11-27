const taskTypes = Object.freeze(['快递帮取', '外卖帮取', '资料帮取', '轻量搬运', '其他'])
// 仅用于Mock表单，不代表后端类型编码。
const taskTypeOptions = Object.freeze(taskTypes.map((label, index) => Object.freeze({
  code: ['mock-parcel', 'mock-food', 'mock-documents', 'mock-moving', 'mock-other'][index], label
})))
const realTaskTypeOptions = Object.freeze(['EXPRESS', 'FOOD', 'DOCUMENT', 'MOVING', 'OTHER'].map((code, index) => Object.freeze({ code, label: taskTypes[index] })))
function getTaskTypeOptions(mode) {
  if (mode !== 'mock' && mode !== 'real') throw new Error('未知任务类型模式')
  return mode === 'real' ? realTaskTypeOptions : taskTypeOptions
}
module.exports = { taskTypes, taskTypeOptions, realTaskTypeOptions, getTaskTypeOptions }
