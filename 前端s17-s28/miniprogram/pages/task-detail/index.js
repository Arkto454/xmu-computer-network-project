const { createScaffold } = require('../../utils/scaffold')
const services = require('../../services/index')
const { session } = require('../../utils/session')
const { isTaskId } = require('../../utils/navigation')
const navigation = require('../../utils/navigation')
const { presentTask } = require('../../utils/task-state')
const EMPTY={task:null,policy:null,pageState:'loading',message:'',isLoading:false}
const ACTION_EMPTY={actionBusy:false,actionMessage:'',showClaimForm:false,claimPhone:'',claimWechat:'',claimError:'',claimStatus:'idle',claimPending:false,claimBlocked:false,claimRecoveryError:''}
Page(createScaffold({
  recover(){if(!this._actionToken&&!this.data.actionBusy)return this.reloadDetail()},
  data:Object.assign({taskId:'',showPreview:false,mockAccount:'',mockTaskIndex:0,mockTasks:[],
    mockAccounts:[{id:'',label:'未登录'},{id:'mock-a',label:'账号 A'},{id:'mock-b',label:'账号 B'},{id:'mock-c',label:'账号 C'}],
    mockScenarios:[{key:'normal',label:'正常'},{key:'delayed',label:'延迟'},{key:'failure',label:'失败'}],mockScenarioIndex:0},EMPTY,ACTION_EMPTY),
  clearAccountData(){this._detailSequence=(this._detailSequence||0)+1;this._actionToken=null;this.setData(Object.assign({},EMPTY,ACTION_EMPTY))},
  load(query){
    this._dead=false;this._visible=true;this._detailSequence=0;this._shownOnce=false
    this.setData({taskId:isTaskId(query.taskId)?query.taskId:''})
    if(this.data.isMock&&services.snapshotMock){
      const rows=services.snapshotMock().tasks.map(task=>({id:task.id,label:task.id+' · '+task.status+' · '+task.title}))
      this.setData({mockTasks:rows,mockTaskIndex:Math.max(0,rows.findIndex(task=>task.id===this.data.taskId)),mockAccount:session.snapshot().userId||''})
    }
    this._unsubscribeIdentity=session.subscribe(()=>{
      const current=++this._detailSequence
      this._actionToken=null
      this.setData(Object.assign({},EMPTY,ACTION_EMPTY,{mockAccount:session.snapshot().userId||''}))
      Promise.resolve().then(()=>{if(!this._dead&&this._visible&&current===this._detailSequence)this.reloadDetail()})
    })
    return this.reloadDetail()
  },
  unload(){this._dead=true;this._detailSequence++;if(this._unsubscribeIdentity)this._unsubscribeIdentity();if(this._previewScenarioChanged&&services.scenarios)services.scenarios.reset()},
  methods:{
    onShow(){this._visible=true;if(!this._shownOnce){this._shownOnce=true;return}this.syncClaim();return this.reloadDetail()},
    onHide(){this._visible=false;this._detailSequence++;if(!this.data.isMock)this.setData(Object.assign({},EMPTY,ACTION_EMPTY))},
    reloadDetail(){
      const requestId=++this._detailSequence
      this.setData(Object.assign({},EMPTY,{isLoading:true}))
      if(!isTaskId(this.data.taskId)){this.setData({pageState:'unavailable',message:'缺少有效任务编号，请返回大厅重新打开。',isLoading:false});return Promise.resolve()}
      const captured=session.snapshot()
      if(!this.data.isMock&&!captured.authenticated){this.setData({pageState:'auth',message:'请使用微信登录后查看任务。',isLoading:false});return Promise.resolve()}
      this.syncClaim()
      return services.tasks.getPublic(this.data.taskId,{userId:captured.authenticated?captured.userId:null}).then(detail=>{
        if(this._dead||!this._visible||requestId!==this._detailSequence||!session.isCurrent(captured))return
        if(!detail||detail.id!==this.data.taskId||typeof detail.status!=='string')throw Error('Invalid detail')
        const policy=presentTask(detail,captured.authenticated),task={}
        if(!this.data.isMock){policy.canLogin=false;if(policy.known)policy.message=policy.terminal?'任务已结束，交接信息已隐藏。':detail.deliveryPaused?'取消申请正在审核，交付暂停。':'请按实际交接进度操作；发布者扫码核对后，再单独确认完成。'}
        ;['id','title','typeName','pickupLocation','deliveryLocation','rewardText','deadlineText','description','overdue','closeReason','closedAtText','closureMessage','settlementStatus','ownerLabel','runnerLabel'].forEach(key=>{task[key]=detail[key]==null?'':detail[key]})
        task.statusText=policy.statusLabel
        task.phaseRecords=Array.isArray(detail.phaseRecords)?detail.phaseRecords.map((record,index)=>({key:String(index),label:String(record.label||''),timeText:String(record.timeText||'')})):[]
        this.setData({task,policy,pageState:'',isLoading:false,message:''})
        this.syncClaim()
        if(!this.data.isMock&&policy.terminal&&detail.status==='已关闭'&&detail.closureId){
          return services.tasks.getClosure(this.data.taskId,detail.closureId).then(closure=>{
            if(!this.actionCurrent(captured,detail.id)||requestId!==this._detailSequence)return
            this.setData({task:Object.assign({},task,closure,{closureMessage:''})})
          }).catch(()=>{
            if(!this.actionCurrent(captured,detail.id)||requestId!==this._detailSequence)return
            // 辅助查询失败不抹去已确认的终态，也不恢复旧码、私密信息或操作权限。
            this.setData({task:Object.assign({},task,{closureMessage:'关闭原因和时间暂时无法读取，请刷新详情重试。'})})
          })
        }
      }).catch(error=>{
        if(this._dead||!this._visible||requestId!==this._detailSequence||!session.isCurrent(captured))return
        const missing=error.businessCode==='MOCK_TASK_NOT_FOUND'||error.businessCode==='TASK_NOT_FOUND'
        this.setData({task:null,policy:null,pageState:missing?'unavailable':'error',message:missing?'任务不存在或暂不可访问，请返回大厅。':'暂时无法获取详情，请重试。',isLoading:false})
      })
    },
    previewAction(event){
      if(this.data.isLoading||this.data.actionBusy||this.data.claimBlocked||!this.data.policy)return
      const action=this.data.policy.actions.find(item=>item.key===event.currentTarget.dataset.action)
      if(!this.data.isMock&&action){
        if(action.key==='accept'){this.setData({showClaimForm:true,claimError:'',actionMessage:''});return}
        if(action.key==='cancel')return this.cancelTask()
        if(action.key==='pickup')return this.pickupTask()
        if(action.key==='deliver'||action.key==='code')return navigation.open('deliveryCode',this.data.taskId)
        if(action.key==='scan')return navigation.open('scanConfirm',this.data.taskId)
        return
      }
      if(action)wx.showModal({title:action.label+' · 演示预览',content:action.note+' 当前只预览操作资格，不提交或改变任务。',showCancel:false})
    },
    syncClaim(){
      if(this.data.isMock||!session.snapshot().authenticated)return
      if(this.data.policy&&this.data.policy.terminal){this.setData({claimPhone:'',claimWechat:'',showClaimForm:false,claimPending:false,claimBlocked:false,claimRecoveryError:'',actionBusy:!!this._actionToken});return}
      try{
      const state=services.claim.getState(this.data.taskId),pending=['pending','unknown','sending'].includes(state.status)
      const values={claimStatus:state.status,claimPending:pending,actionBusy:!!this._actionToken||state.inFlight,claimBlocked:false,claimRecoveryError:''}
      if(pending){const draft=services.claim.getDraft(this.data.taskId);Object.assign(values,{claimPhone:draft.phone,claimWechat:draft.wechatId,showClaimForm:true})}
      else if(this.data.policy&&!this.data.policy.actions.some(action=>action.key==='accept'))Object.assign(values,{showClaimForm:false,claimPhone:'',claimWechat:''})
      this.setData(values)
      }catch(_){this.setData({claimBlocked:true,claimPhone:'',claimWechat:'',showClaimForm:false,claimPending:false,claimRecoveryError:'待确认申请暂时无法读取，已暂停新的接单申请。请检查设备存储后点击刷新详情。'})}
    },
    onClaimInput(event){
      if(this.data.isMock||this.data.claimPending||this.data.actionBusy)return
      const field=event.currentTarget.dataset.field
      if(!['claimPhone','claimWechat'].includes(field))return
      this.setData({[field]:String(event.detail.value||'').slice(0,field==='claimPhone'?32:65),claimError:''})
    },
    hideClaimForm(){if(!this.data.actionBusy&&!this.data.claimPending)this.setData({showClaimForm:false,claimPhone:'',claimWechat:'',claimError:''})},
    actionCurrent(captured,taskId){return !this._dead&&this._visible&&this.data.taskId===taskId&&session.isCurrent(captured)},
    async submitClaim(){
      if(this.data.isMock||this.data.isLoading||this._actionToken||this.data.actionBusy||this.data.claimBlocked)return
      const captured=session.snapshot(),taskId=this.data.taskId
      if(!captured.authenticated)return
      if(this.data.claimPending)return this.runClaim(captured,taskId,true)
      if(!this.data.policy||!this.data.policy.actions.some(action=>action.key==='accept'))return
      const phone=this.data.claimPhone.trim(),wechatId=this.data.claimWechat.trim()
      if(!/^\+?[1-9][0-9]{6,14}$/.test(phone)||wechatId.length>64){this.setData({claimError:'请填写 7—15 位本次手机号，可带前导 +；微信号最多 64 字。'});return}
      const token={},sequence=this._detailSequence
      this._actionToken=token;this.setData({actionBusy:true,claimError:''})
      const confirmed=await new Promise(resolve=>wx.showModal({title:'确认申请接单',content:'提交本次联系方式。受理后仍需等待服务端确认；请实际具备完成任务的条件再申请。',success:result=>resolve(result.confirm===true),fail:()=>resolve(false)}))
      if(this._actionToken===token)this._actionToken=null
      if(!this.actionCurrent(captured,taskId)||sequence!==this._detailSequence){if(this.actionCurrent(captured,taskId))this.syncClaim();return}
      this.setData({actionBusy:false})
      if(confirmed)return this.runClaim(captured,taskId,false,{phone,wechatId})
    },
    async runClaim(captured,taskId,retry,contact){
      const token={};this._actionToken=token;this.setData({actionBusy:true,claimError:'',actionMessage:''})
      try{
        const result=await(retry?services.claim.retry(taskId):services.claim.submit(taskId,contact))
        if(!this.actionCurrent(captured,taskId))return
        if(result.outcome==='PENDING')this.setData({actionMessage:'申请已受理，接单结果待确认。约 1 秒后可手动确认原请求，请勿重新申请。'})
        else{
          this.setData({showClaimForm:false,claimPhone:'',claimWechat:'',claimError:'',actionMessage:'服务端已确认接单成功，正在更新任务详情。'})
          await this.reloadDetail()
          if(this.actionCurrent(captured,taskId))this.setData({actionMessage:this.data.pageState?'接单已成功，详情更新失败，请刷新详情。':'接单结果已确认。下方任务状态为最新查询结果。'})
          if(result.storageWarning&&this.actionCurrent(captured,taskId))this.setData({actionMessage:'服务端已确认接单成功，但本地申请记录暂未清理。可稍后再确认原请求；请勿重新申请。'})
        }
      }catch(error){
        if(this.actionCurrent(captured,taskId)){
          const message=error.outcome==='unknown'?'接单结果待确认，请使用下方按钮确认原请求，勿重新申请。':error.message||'本次接单未成功，请刷新任务。'
          this.setData({claimError:message,actionMessage:message})
          if(error.outcome==='rejected'&&error.httpStatus===409)await this.reloadDetail()
        }
      }finally{
        if(this._actionToken===token)this._actionToken=null
        if(this.actionCurrent(captured,taskId)){this.setData({actionBusy:false});this.syncClaim()}
      }
    },
    async pickupTask(){
      if(this.data.isMock||this.data.isLoading||this._actionToken||this.data.actionBusy||this.data.claimPending||!this.data.policy||!this.data.policy.actions.some(a=>a.key==='pickup'))return
      const captured=session.snapshot(),taskId=this.data.taskId,sequence=this._detailSequence,token={}
      this._actionToken=token;this.setData({actionBusy:true,actionMessage:''})
      const confirmed=await new Promise(resolve=>wx.showModal({title:'确认已经取件？',content:'请实际取得本任务物品后再确认。',success:r=>resolve(r.confirm===true),fail:()=>resolve(false)}))
      if(!this.actionCurrent(captured,taskId)||sequence!==this._detailSequence){if(this._actionToken===token)this._actionToken=null;if(this.actionCurrent(captured,taskId))this.syncClaim();return}
      if(!confirmed){this._actionToken=null;this.syncClaim();return}
      let notice=''
      try{await services.tasks.markPickedUp(taskId,captured.userId);notice='服务端已确认取件。'}
      catch(error){notice=error.outcome==='unknown'?'取件结果待确认，正在查询最新进度；请勿连续提交。':error.message||'取件未完成，请刷新确认。'}
      if(this.actionCurrent(captured,taskId))await this.reloadDetail()
      if(this._actionToken===token)this._actionToken=null
      if(this.actionCurrent(captured,taskId)){this.setData({actionMessage:notice+(this.data.pageState?' 详情更新失败，请手动刷新。':'')});this.syncClaim()}
    },
    async cancelTask(){
      if(this.data.isMock||this.data.isLoading||this._actionToken||this.data.actionBusy||this.data.claimPending||!this.data.policy||!this.data.policy.actions.some(action=>action.key==='cancel'))return
      const captured=session.snapshot(),taskId=this.data.taskId,sequence=this._detailSequence,token={}
      this._actionToken=token;this.setData({actionBusy:true,actionMessage:''})
      const confirmed=await new Promise(resolve=>wx.showModal({title:'取消这条任务？',content:'仅未接单且未过期的本人任务可以直接取消，实际结果以服务端为准。',success:result=>resolve(result.confirm===true),fail:()=>resolve(false)}))
      if(!this.actionCurrent(captured,taskId)||sequence!==this._detailSequence){if(this._actionToken===token)this._actionToken=null;if(this.actionCurrent(captured,taskId))this.syncClaim();return}
      if(!confirmed){this._actionToken=null;this.setData({actionBusy:false});return}
      let notice=''
      try{
        await services.tasks.cancel(taskId,captured.userId)
        notice='任务已取消。'
      }catch(error){notice=error.outcome==='unknown'?'取消结果待确认，请先刷新查看当前任务，勿连续提交。':error.message||'取消未成功，请刷新详情。'}
      finally{
        if(this._actionToken===token)this._actionToken=null
        if(this.actionCurrent(captured,taskId)){
          await this.reloadDetail()
          if(this.actionCurrent(captured,taskId))this.setData({actionBusy:false,actionMessage:notice+(this.data.pageState?' 详情更新失败，可手动刷新。':'')})
        }
      }
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
      this.setData(Object.assign({},ACTION_EMPTY,{taskId:task.id,mockTaskIndex:index}));return this.reloadDetail()
    },
    chooseMockScenario(event){
      if(!this.data.isMock||!services.scenarios)return
      const index=Number(event.detail.value),scenario=this.data.mockScenarios[index]
      if(!scenario)return
      services.scenarios.set(scenario.key);this._previewScenarioChanged=true;this.setData({mockScenarioIndex:index});return this.reloadDetail()
    }
  }
}))
