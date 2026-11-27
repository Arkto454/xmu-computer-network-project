function pad(value) { return String(value).padStart(2, '0') }
function localParts(timestamp) {
  const date = new Date(timestamp)
  return { date: date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate()), time: pad(date.getHours()) + ':' + pad(date.getMinutes()) }
}
function parseLocalDeadline(date, time) {
  if (typeof date !== 'string' || typeof time !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(date) || !/^\d{2}:\d{2}$/.test(time)) return null
  const d = date.split('-').map(Number), t = time.split(':').map(Number)
  if (d[0] < 2000 || d[1] < 1 || d[1] > 12 || d[2] < 1 || d[2] > 31 || t[0] > 23 || t[1] > 59) return null
  const value = new Date(d[0], d[1] - 1, d[2], t[0], t[1], 0, 0)
  // 拒绝日期滚动和夏令时不存在的本地时间。
  if (value.getFullYear() !== d[0] || value.getMonth() !== d[1] - 1 || value.getDate() !== d[2] || value.getHours() !== t[0] || value.getMinutes() !== t[1]) return null
  return { timestamp: value.getTime(), iso: value.toISOString(), text: date + ' ' + time }
}
module.exports = { localParts, parseLocalDeadline }
