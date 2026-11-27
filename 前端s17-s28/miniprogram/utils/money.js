// 十进制字符串处理；不做浮点金额运算。上限由调用方的契约决定。
function normalizeMoney(value) {
  if (typeof value !== 'string') return null
  const text = value.trim()
  if (!/^\d+(?:\.\d{1,2})?$/.test(text)) return null
  const parts = text.split('.')
  const integer = parts[0].replace(/^0+(?=\d)/, '')
  const fraction = ((parts[1] || '') + '00').slice(0, 2)
  if (integer === '0' && fraction === '00') return null
  return integer + '.' + fraction
}
function exceedsMoney(value, limit) {
  const left = normalizeMoney(value), right = normalizeMoney(limit)
  if (!left || !right) return true
  const a = left.replace('.', ''), b = right.replace('.', '')
  return a.length > b.length || a.length === b.length && a > b
}
module.exports = { normalizeMoney, exceedsMoney }
