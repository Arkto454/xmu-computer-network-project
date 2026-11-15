const accounts = [
  { id: 'mock-a', label: '演示账号 A' },
  { id: 'mock-b', label: '演示账号 B' },
  { id: 'mock-c', label: '演示账号 C' }
]

const taskTypes = ['快递帮取', '外卖帮取', '资料帮取', '轻量搬运', '其他']

const tasks = [
  {
    id: 'demo-001', title: '帮取校园快递站包裹', typeName: taskTypes[0],
    pickupLocation: '北门快递站', deliveryLocation: '第二教学楼门口',
    rewardText: '3.00', deadlineText: '样例：今天18:30前',
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
    rewardText: '2.50', deadlineText: '样例：今天17:30前',
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
    rewardText: '4.00', deadlineText: '样例：已过期',
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
    rewardText: '1.00', deadlineText: '样例：已关闭',
    description: '演示管理关闭后的只读终态。',
    ownerId: 'mock-b', runnerId: null, status: '已关闭', settlementStatus: '未开始',
    privateInfo: {
      owner: { phone: '000-TEST-B', wechatId: 'mock_b', note: '仅演示的私密交接说明 B' },
      runner: null
    }, closeReason: '管理端关闭（Mock样例）'
  }
]

function clone(value) { return JSON.parse(JSON.stringify(value)) }
function createInitialState() {
  return { accounts: clone(accounts), tasks: clone(tasks), nextTaskNumber: 1 }
}

module.exports = { accounts, taskTypes, createInitialState, clone }
