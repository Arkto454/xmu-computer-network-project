const { createScaffold } = require('../../utils/scaffold')
const services = require('../../services/index')
const { session } = require('../../utils/session')
const { isTaskId } = require('../../utils/navigation')
const { presentTask } = require('../../utils/task-state')
const EMPTY={task:null,policy:null,pageState:'loading',message:'',isLoading:false}
Page(createScaffold({
  data:Object.assign({taskId:'',showPreview:false,mockAccount:'',mockTaskIndex:0,mockTasks:[],
    mockAccounts:[{id:'',label:'未登录'},{id:'mock-a',label:'账号 A'},{id:'mock-b',label:'账号 B'},{id:'mock-c',label:'账号 C'}],
    mockScenarios:[{key:'normal',label:'正常'},{key:'delayed',label:'延迟'},{key:'failure',label:'失败'}],mockScenarioIndex:0},EMPTY),
  clearAccountData(){this._detailSequence=(this._detailSequence||0)+1;this.setData(Object.assign({},EMPTY))},
  load(query){
    this._dead=false;this._visible=true;this._detailSequence=0;this._shownOnce=false
    this.setData({taskId:isTaskId(query.taskId)?query.taskId:''})
    if(this.data.isMock&&services.snapshotMock){
      const rows=services.snapshotMock().tasks.map(task=>({id:task.id,label:task.id+' · '+task.status+' · '+task.title}))
      this.setData({mockTasks:rows,mockTaskIndex:Math.max(0,rows.findIndex(task=>task.id===this.data.taskId)),mockAccount:session.snapshot().userId||''})
    }
    this._unsubscribeIdentity=session.subscribe(()=>{
      const current=++this._detailSequence
      this.setData(Object.assign({},EMPTY,{mockAccount:session.snapshot().userId||''}))
      Promise.resolve().then(()=>{if(!this._dead&&this._visible&&current===this._detailSequence)this.reloadDetail()})
    })
    return this.reloadDetail()
  },
  unload(){this._dead=true;this._detailSequence++;if(this._unsubscribeIdentity)this._unsubscribeIdentity();if(this._previewScenarioChanged&&services.scenarios)services.scenarios.reset()},
  methods:{
    onShow(){this._visible=true;if(!this._shownOnce){this._shownOnce=true;return}return this.reloadDetail()},
    onHide(){this._visible=false;this._detailSequence++;if(!this.data.isMock)this.setData(Object.assign({},EMPTY))},
    reloadDetail(){
      const requestId=++this._detailSequence
      this.setData(Object.assign({},EMPTY,{isLoading:true}))
      if(!isTaskId(this.data.taskId)){this.setData({pageState:'unavailable',message:'缺少有效任务编号，请返回大厅重新打开。',isLoading:false});return Promise.resolve()}
      const captured=session.snapshot()
      if(!this.data.isMock&&!captured.authenticated){this.setData({pageState:'auth',message:'请使用微信登录后查看任务。',isLoading:false});return Promise.resolve()}
      return services.tasks.getPublic(this.data.taskId,{userId:captured.authenticated?captured.userId:null}).then(detail=>{
        if(this._dead||!this._visible||requestId!==this._detailSequence||!session.isCurrent(captured))return
        if(!detail||detail.id!==this.data.taskId||typeof detail.status!=='string')throw Error('Invalid detail')
        const policy=presentTask(detail,captured.authenticated),task={}
        if(!this.data.isMock){policy.canLogin=false;policy.actions=[];policy.message=policy.terminal?'任务已结束，交接信息已隐藏。':'当前可查询任务；接单、取消和交付操作尚未开放。'}
        ;['id','title','typeName','pickupLocation','deliveryLocation','rewardText','deadlineText','description','overdue','closeReason','settlementStatus','ownerLabel','runnerLabel'].forEach(key=>{task[key]=detail[key]==null?'':detail[key]})
        task.statusText=policy.statusLabel
        task.phaseRecords=Array.isArray(detail.phaseRecords)?detail.phaseRecords.map((record,index)=>({key:String(index),label:String(record.label||''),timeText:String(record.timeText||'')})):[]
        this.setData({task,policy,pageState:'',isLoading:false,message:''})
      }).catch(error=>{
        if(this._dead||!this._visible||requestId!==this._detailSequence||!session.isCurrent(captured))return
        const missing=error.businessCode==='MOCK_TASK_NOT_FOUND'||error.businessCode==='TASK_NOT_FOUND'
        this.setData({task:null,policy:null,pageState:missing?'unavailable':'error',message:missing?'任务不存在或暂不可访问，请返回大厅。':'暂时无法获取详情，请重试。',isLoading:false})
      })
    },
    previewAction(event){
      if(this.data.isLoading||!this.data.policy)return
      const action=this.data.policy.actions.find(item=>item.key===event.currentTarget.dataset.action)
      if(action)wx.showModal({title:action.label+' · 演示预览',content:action.note+' 当前只预览操作资格，不提交或改变任务。',showCancel:false})
    },
    togglePreview(){if(this.data.isMock)this.setData({showPreview:!this.data.showPreview})},
    switchMockAccount(event){
      if(!this.data.isMock)return
      const id=event.currentTarget.dataset.userId||''
      if(!this.data.mockAccounts.some(account=>account.id===id))return
      if(id)session.completeLogin(session.beginLogin(),{mode:'mock',userId:id,token:'MOCK-ONLY-'+id});else session.logout()
    },
    chooseMockTask(event){
      if(!this.data.isMock)return
      const index=Number(event.detail.value),task=this.data.mockTasks[index]
      if(!task)return
      this.setData({taskId:task.id,mockTaskIndex:index});return this.reloadDetail()
    },
    chooseMockScenario(event){
      if(!this.data.isMock||!services.scenarios)return
      const index=Number(event.detail.value),scenario=this.data.mockScenarios[index]
      if(!scenario)return
      services.scenarios.set(scenario.key);this._previewScenarioChanged=true;this.setData({mockScenarioIndex:index});return this.reloadDetail()
    }
  }
}))
