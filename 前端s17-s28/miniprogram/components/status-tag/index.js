// 中文仅为前端展示值，后端状态编码将在S08契约适配层转换。
const displayStates = {
  '待接单': 'available', '已接单': 'active', '已取件': 'active',
  '待确认': 'warning', '已完成': 'complete', '已取消': 'ended',
  '已过期': 'ended', '已关闭': 'closed'
}
Component({
  properties: { status: { type: String, value: '' }, overdue: { type: Boolean, value: false } },
  data: { label: '状态待确认', tone: 'unknown' },
  observers: {
    status(value) {
      const known = Object.prototype.hasOwnProperty.call(displayStates, value)
      this.setData({ label: known ? value : '状态待确认', tone: known ? displayStates[value] : 'unknown' })
    }
  }
})
