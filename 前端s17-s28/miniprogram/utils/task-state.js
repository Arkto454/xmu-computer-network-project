// 中文Mock协议；真实状态与权限在后续接口适配层转换。
const states = ['待接单','已接单','已取件','待确认','已完成','已取消','已过期','已关闭']
const terminalStates = ['已完成','已取消','已过期','已关闭']
const actions = {
  accept:{label:'接单',note:'接单前需要填写本次手机号，可选填微信号和交接说明。'},
  cancel:{label:'取消任务',note:'仅未被承接的任务可由发布者取消，提交前需要再次确认。'},
  pickup:{label:'确认已取件',note:'请实际取到物品后再确认。'},
  deliver:{label:'申请交付',note:'到达约定地点后申请交付，随后由发布者扫码确认。'},
  scan:{label:'扫码确认',note:'由本任务发布者扫码核对后确认完成。'},
  code:{label:'展示交付码',note:'交付码仅供本任务交接使用，需要保持有效。'}
}
function isKnown(status){return states.indexOf(status)!==-1}
function isTerminal(status){return terminalStates.indexOf(status)!==-1}
function actionsFor(status,relation,overdue){
  if(!isKnown(status)||isTerminal(status))return []
  if(status==='待接单')return relation==='owner'?['cancel']:relation==='guest'&&!overdue?['accept']:[]
  if(relation==='runner')return status==='已接单'?['pickup']:status==='已取件'?['deliver']:['code']
  return status==='待确认'&&relation==='owner'?['scan']:[]
}
function presentTask(detail,authenticated){
  const known=isKnown(detail.status),terminal=isTerminal(detail.status)
  const role=['owner','runner'].indexOf(detail.relation)!==-1?detail.relation:'guest'
  const granted=Array.isArray(detail.allowedActions)?detail.allowedActions:[]
  const available=authenticated?actionsFor(detail.status,role,!!detail.overdue).filter(key=>granted.indexOf(key)!==-1):[]
  let message=terminal?'任务已结束，仅可查看记录。':role==='guest'?'你不是当前任务的参与者，仅展示公开信息。':'请按当前进度交接。'
  if(!known)message='当前状态暂时无法识别，请刷新确认，暂不开放操作。'
  else if(detail.status==='待接单'&&detail.overdue)message='已超过最晚送达时间，暂不可接单，请刷新确认任务状态。'
  else if(!authenticated)message='当前未登录，仅展示公开信息。'
  return {
    known,terminal,role,roleLabel:role==='owner'?'我是发布者':role==='runner'?'我是接单者':authenticated?'其他浏览者':'未登录',
    statusLabel:known?detail.status:'状态待确认',message,
    canLogin:!authenticated&&known&&detail.status==='待接单'&&!detail.overdue,
    actions:available.map(key=>({key,label:actions[key].label,note:actions[key].note})),
    contacts:authenticated&&known&&!terminal&&role!=='guest'&&detail.privateInfo
      ?['owner','runner'].filter(key=>detail.privateInfo[key]).map(key=>({key,label:key==='owner'?'发布者本次交接方式':'接单者本次交接方式',phone:String(detail.privateInfo[key].phone||''),wechatId:String(detail.privateInfo[key].wechatId||''),note:String(detail.privateInfo[key].note||'')})):[]
  }
}
module.exports={states,actions,isKnown,isTerminal,actionsFor,presentTask}
