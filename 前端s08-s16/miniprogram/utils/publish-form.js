const { getTaskTypeOptions } = require('../config/task-types')
const { normalizeMoney, exceedsMoney } = require('./money')
const { parseLocalDeadline } = require('./time')
// 后端未定值仅作Mock占位。真实接入前需替换，不视为产品固定限制。
const limits = Object.freeze({ title: 60, pickupLocation: 100, deliveryLocation: 100, description: 500, wechatId: 64, privateNote: 500, reward: '9999.99' })
const realLimits = Object.freeze({ title: 100, pickupLocation: 200, deliveryLocation: 200, description: 1000, wechatId: 64, privateNote: 1000, reward: '99999999.99' })
function emptyForm() { return { title: '', typeCode: '', pickupLocation: '', deliveryLocation: '', deadlineDate: '', deadlineTime: '', reward: '', description: '', phone: '', wechatId: '', privateNote: '' } }
function isDirty(form) { return Object.keys(emptyForm()).some(key => String(form[key] || '') !== '') }
function validateForm(form, now, mode) {
  const real = mode === 'real', bounds = real ? realLimits : limits
  const f = form || {}, errors = {}, values = {}
  Object.keys(emptyForm()).forEach(key => { values[key] = typeof f[key] === 'string' ? f[key].trim() : '' })
  ;['title', 'pickupLocation', 'deliveryLocation'].forEach(key => { if (!values[key]) errors[key] = '请填写' + ({ title: '任务标题', pickupLocation: '取件地点', deliveryLocation: '送达地点' })[key] })
  Object.keys(bounds).filter(key => key !== 'reward').forEach(key => {
    // 真实模式按Java @Size的UTF-16单元计数，表情通常占2。
    const length = real ? (typeof f[key] === 'string' ? f[key].length : 0) : Array.from(values[key]).length
    if (length > bounds[key]) errors[key] = (real ? '最多' : '演示暂限') + bounds[key] + (real ? '个字符，表情可能占2个，请缩短。' : '字，请缩短后再提交。')
  })
  const type = getTaskTypeOptions(real ? 'real' : 'mock').find(item => item.code === values.typeCode)
  if (!type) errors.typeCode = '请选择任务类型。'
  const deadline = parseLocalDeadline(values.deadlineDate, values.deadlineTime)
  if (!values.deadlineDate || !values.deadlineTime) errors.deadlineDate = '请选择完整的日期和时间。'
  else if (!deadline) errors.deadlineDate = '日期或时间无效，请重新选择。'
  else if (deadline.timestamp <= now) errors.deadlineDate = '最晚送达时间需要晚于现在。'
  const reward = normalizeMoney(values.reward)
  if (!reward) errors.reward = '请输入大于0、最多两位小数的金额。'
  else if (exceedsMoney(reward, bounds.reward)) errors.reward = real ? '奖励金额不能超过99999999.99元。' : '演示金额暂不超过9999.99元。'
  if (!values.phone) errors.phone = '请填写本次任务手机号。'
  else if (real ? !/^\+?[1-9][0-9]{6,14}$/.test(values.phone) : !/^1[3-9]\d{9}$/.test(values.phone) && !/^000-TEST-[ABC]$/.test(values.phone)) errors.phone = real ? '请填7—15位数字，首位非0，可带前导加号。' : '请填11位手机号；演示可用000-TEST-A。'
  const order = ['title', 'typeCode', 'pickupLocation', 'deliveryLocation', 'deadlineDate', 'reward', 'description', 'phone', 'wechatId', 'privateNote']
  const firstError = order.find(key => errors[key]) || ''
  return { errors, firstError, valid: !firstError, draft: firstError ? null : {
    title: values.title, typeCode: type.code, typeName: type.label,
    pickupLocation: values.pickupLocation, deliveryLocation: values.deliveryLocation,
    deadlineAt: deadline.iso, deadlineText: deadline.text, rewardText: reward,
    description: values.description, phone: values.phone, wechatId: values.wechatId, privateNote: values.privateNote
  } }
}
module.exports = { limits, realLimits, emptyForm, isDirty, validateForm }
