// 仅在mock且显式传入uiPreview=all时展示，用于S04开发验收，无网络或业务写入。
function createUiPreview() {
  return {
    longTask: {
      id: 'demo-long', title: '帮忙将图书馆课程参考资料和装订讲义从北门打印店送到第二教学楼公共交接区，请注意防雨并保持资料平整',
      typeName: '资料帮取', pickupLocation: '北门校内打印店公共取件区域（靠近学生服务中心的入口处）',
      deliveryLocation: '第二教学楼东侧公共交接区域LongLocationWithoutSpacesABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789',
      rewardText: '99999.99', deadlineText: '样例最晚送达时间：2026年11月14日晚上18:30前', statusText: '已取件', overdue: true
    },
    statuses: ['待接单', '已接单', '已取件', '待确认', '已完成', '已取消', '已过期', '已关闭', '未知编码'],
    longError: '暂时无法读取本次任务。请检查网络连接后重新加载。LongErrorWithoutSpacesABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'
  }
}
module.exports = { createUiPreview }
