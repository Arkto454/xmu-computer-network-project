const { taskTypes } = require('../config/task-types')

const accounts = [
  { id: 'mock-a', label: '演示账号 A' },
  { id: 'mock-b', label: '演示账号 B' },
  { id: 'mock-c', label: '演示账号 C' }
]

const tasks = [
  {
    id: 'demo-001', title: '帮取校园快递站包裹', typeName: taskTypes[0],
    pickupLocation: '北门快递站', deliveryLocation: '第二教学楼门口',
    rewardText: '3.00', deadlineText: '样例：今天18:30前',
    publishOrder: 1, publishTimeText: '较早发布',
    description: '一件轻便包裹，送到约定的公共交接区域。',
    ownerId: 'mock-a', runnerId: null, status: '待接单', settlementStatus: '未开始',
    privateInfo: {
      owner: { phone: '000-TEST-A', wechatId: 'mock_a', note: '仅演示的私密交接说明 A' },
      runner: null
    }
  },
  {
    id: 'demo-002', title: '帮带一份课程资料', typeName: taskTypes[2],
    pickupLocation: '校内打印店', deliveryLocation: '图书馆入口',
    rewardText: '2.00', deadlineText: '样例：今天19:00前',
    publishOrder: 2, publishTimeText: '较早发布',
    description: '一份装订资料，按约定的公共区域交接。',
    ownerId: 'mock-b', runnerId: 'mock-a', status: '已接单', settlementStatus: '未开始',
    privateInfo: {
      owner: { phone: '000-TEST-B', wechatId: 'mock_b', note: '仅演示的私密交接说明 B' },
      runner: { phone: '000-TEST-A', wechatId: 'mock_a', note: '仅演示的接单方说明 A' }
    },
    deliveryCode: { value: 'MOCK-CODE-DEMO-002', expiresAt: '2099-01-01T00:00:00.000Z' }
  },
  {
    id: 'demo-003', title: '帮送一份课程讲义', typeName: taskTypes[2],
    pickupLocation: '南区打印点', deliveryLocation: '第三教学楼一层',
    rewardText: '1.50', deadlineText: '样例：今天20:00前',
    publishOrder: 3, publishTimeText: '稍早发布',
    description: '公开摘要不包含联系方式或私密说明。',
    ownerId: 'mock-c', runnerId: 'mock-b', status: '待确认', settlementStatus: '待模拟结算',
    privateInfo: {
      owner: { phone: '000-TEST-C', wechatId: 'mock_c', note: '仅演示的私密交接说明 C' },
      runner: { phone: '000-TEST-B', wechatId: 'mock_b', note: '仅演示的接单方说明 B' }
    }
  },
  {
    id: 'demo-004', title: '帮带一份外卖', typeName: taskTypes[1],
    pickupLocation: '东门取餐点', deliveryLocation: '宿舍公共区域',
    rewardText: '2.50', deadlineText: '样例：今天17:30前', publishOrder: 4, publishTimeText: '较早发布',
    description: '演示取消后的公开任务状态。',
    ownerId: 'mock-a', runnerId: null, status: '已取消', settlementStatus: '未开始',
    privateInfo: {
      owner: { phone: '000-TEST-A', wechatId: 'mock_a', note: '仅演示的私密交接说明 A' },
      runner: null
    }, closeReason: '发布者取消（Mock样例）'
  },
  {
    id: 'demo-005', title: '帮搬一箱学习用品', typeName: taskTypes[3],
    pickupLocation: '西区服务点', deliveryLocation: '第一教学楼门厅',
    rewardText: '4.00', deadlineText: '样例：已过期', publishOrder: 5, publishTimeText: '昨日发布',
    description: '演示超时任务；不代表真实后端时间判断。',
    ownerId: 'mock-c', runnerId: null, status: '已过期', settlementStatus: '未开始',
    privateInfo: {
      owner: { phone: '000-TEST-C', wechatId: 'mock_c', note: '仅演示的私密交接说明 C' },
      runner: null
    }
  },
  {
    id: 'demo-006', title: '其他类型演示任务', typeName: taskTypes[4],
    pickupLocation: '校内公共服务点', deliveryLocation: '公共交接区域',
    rewardText: '1.00', deadlineText: '样例：已关闭', publishOrder: 6, publishTimeText: '较早发布',
    description: '演示管理关闭后的只读终态。',
    ownerId: 'mock-b', runnerId: null, status: '已关闭', settlementStatus: '未开始',
    privateInfo: {
      owner: { phone: '000-TEST-B', wechatId: 'mock_b', note: '仅演示的私密交接说明 B' },
      runner: null
    }, closeReason: '管理端关闭（Mock样例）'
  },
  {
    id: 'demo-007', title: '帮带一份午餐', typeName: taskTypes[1],
    pickupLocation: '东区食堂取餐口', deliveryLocation: '综合楼一层公共区',
    rewardText: '2.00', deadlineText: '样例：今天12:30前', publishOrder: 7, publishTimeText: '12分钟前',
    description: '固定分页样例，交接信息仅在授权详情中显示。',
    ownerId: 'mock-b', runnerId: null, status: '待接单', settlementStatus: '未开始',
    privateInfo: { owner: { phone: '000-TEST-B', wechatId: 'mock_b', note: '仅演示的私密交接说明 B' }, runner: null }
  },
  {
    id: 'demo-008', title: '帮取一套打印资料', typeName: taskTypes[2],
    pickupLocation: '北区打印店', deliveryLocation: '第四教学楼入口',
    rewardText: '1.50', deadlineText: '样例：今天15:00前', publishOrder: 8, publishTimeText: '9分钟前',
    description: '地点为自由文本，不绑定地图或地点字典。',
    ownerId: 'mock-c', runnerId: null, status: '待接单', settlementStatus: '未开始',
    privateInfo: { owner: { phone: '000-TEST-C', wechatId: 'mock_c', note: '仅演示的私密交接说明 C' }, runner: null }
  },
  {
    id: 'demo-009', title: '帮搬一袋轻便用品', typeName: taskTypes[3],
    pickupLocation: '南区服务点', deliveryLocation: '第二宿舍公共入口',
    rewardText: '3.00', deadlineText: '样例：今天16:00前', publishOrder: 9, publishTimeText: '7分钟前',
    description: '轻量物品样例。',
    ownerId: 'mock-a', runnerId: null, status: '待接单', settlementStatus: '未开始',
    privateInfo: { owner: { phone: '000-TEST-A', wechatId: 'mock_a', note: '仅演示的私密交接说明 A' }, runner: null }
  },
  {
    id: 'demo-010', title: '顺路送一件小物品', typeName: taskTypes[4],
    pickupLocation: '校内生活服务点', deliveryLocation: '西区公共交接区',
    rewardText: '2.50', deadlineText: '样例：今天17:00前', publishOrder: 10, publishTimeText: '5分钟前',
    description: '其他类型样例。',
    ownerId: 'mock-b', runnerId: null, status: '待接单', settlementStatus: '未开始',
    privateInfo: { owner: { phone: '000-TEST-B', wechatId: 'mock_b', note: '仅演示的私密交接说明 B' }, runner: null }
  },
  {
    id: 'demo-011', title: '帮取一份文件袋', typeName: taskTypes[0],
    pickupLocation: '西门快递服务点', deliveryLocation: '图书馆南门公共区',
    rewardText: '2.00', deadlineText: '样例：今天18:00前', publishOrder: 11, publishTimeText: '3分钟前',
    description: '文件袋样例；未填写地图坐标。',
    ownerId: 'mock-c', runnerId: null, status: '待接单', settlementStatus: '未开始',
    privateInfo: { owner: { phone: '000-TEST-C', wechatId: 'mock_c', note: '仅演示的私密交接说明 C' }, runner: null }
  },
  {
    id: 'demo-012', title: '帮带一份课程资料', typeName: taskTypes[2],
    pickupLocation: '校内文印点', deliveryLocation: '第五教学楼门厅',
    rewardText: '1.00', deadlineText: '样例：今天19:00前', publishOrder: 12, publishTimeText: '刚刚发布',
    description: '最新发布样例，用于检查排序和最后一页。',
    ownerId: 'mock-a', runnerId: null, status: '待接单', settlementStatus: '未开始',
    privateInfo: { owner: { phone: '000-TEST-A', wechatId: 'mock_a', note: '仅演示的私密交接说明 A' }, runner: null }
  }
]

function clone(value) { return JSON.parse(JSON.stringify(value)) }
function createInitialState() {
  const rows=clone(tasks)
  rows.push(Object.assign({},clone(tasks[1]),{id:'demo-013',title:'已取件且超时的演示任务',status:'已取件',overdue:true}))
  rows.push(Object.assign({},clone(tasks[1]),{id:'demo-014',title:'已完成的演示任务',status:'已完成',settlementStatus:'模拟结算完成'}))
  rows.push(Object.assign({},clone(tasks[1]),{id:'demo-015',title:'未知状态的演示任务',status:'待核对样例'}))
  rows.forEach(task=>{task.phaseRecords=[{status:task.status,label:'样例初始状态：'+task.status,timeText:'固定演示记录'}]})
  return {accounts:clone(accounts),tasks:rows,nextTaskNumber:1,nextPublishOrder:13}
}

module.exports = { accounts, taskTypes, createInitialState, clone }
