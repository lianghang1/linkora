/* ═════════ Vue 根应用：全部页面方法 ═════════ */
window.bootApp = function(){
  var Vue = window.Vue, A = window.Admin;
  var s = window.createStore(Vue);
  var charts = window.createCharts();

  /* ── 确认弹窗（handler 不进响应式） ── */
  var confirmHandler = null, afterHandler = null;

  /* ── 会话 ── */
  function doLogin(){
    var v = (s.loginInput || "").trim();
    if (!v){ s.loginErr = "请输入访问令牌"; return; }
    s.loginBusy = true; s.loginErr = "";
    var prev = s.token; s.token = v;
    A.api(s, "api/overview").then(function(){
      sessionStorage.setItem("linkora-admin-token", v);
      s.loginBusy = false; boot();
    }).catch(function(e){
      s.token = prev; s.loginBusy = false;
      s.loginErr = e.error || "登录失败：请检查服务端 linkora.admin-console.token 配置";
    });
  }
  function logout(){
    sessionStorage.removeItem("linkora-admin-token");
    s.token = ""; s.loggedIn = false; charts.destroyAll();
  }
  function boot(){
    s.loggedIn = true;    api("api/clients").then(function(clients){
      s.clients = clients || [];
      s.clientsLoaded = true;
      if (!s.cdCurrent && s.clients.length) s.cdCurrent = s.clients[0].clientId;
    }).catch(function(){});
    go("cockpit");
  }

  /* ── 导航与刷新 ── */
  function go(v){
    s.view = v;
    refreshCurrent();
    window.scrollTo(0, 0);
  }
  function refreshCurrent(){
    if (s.view === "cockpit") refreshCockpit();
    else if (s.view === "messages"){ loadMessages(); }
    else if (s.view === "clients"){ loadClients(); }
  }

  /* ── 概览 ── */
  function refreshCockpit(){
    api("api/overview").then(function(d){
      s.clients = d.clients || [];
      s.publishedPerDay = d.publishedPerDay || [];
      s.updTime = new Date().toLocaleTimeString("zh-CN", {hour12:false});
      Vue.nextTick(function(){
        charts.renderTrend(s.publishedPerDay);
        charts.renderDonut(s.clients);
      });
      loadRecent();
    }).catch(function(e){ A.toast(s, e.error || "请求失败", "bad"); });
  }
  function loadRecent(){
    api("api/messages?page=1&size=8").then(function(list){
      s.recent = list || [];
    }).catch(function(){ s.recent = []; });
  }
  function viewMessageFrom(id){
    s.view = "messages"; setMsgSeg("all"); s.msgPage = 1;
    loadMessages(function(){ openMessage(id); });
  }

  /* ── 消息页 ── */
  function setMsgSeg(seg){ s.msgSeg = seg; s.msgPage = 1; loadMessages(); }
  function pageMsgs(delta){ s.msgPage = Math.max(1, s.msgPage + delta); loadMessages(); }
  function pickedClient(){ return (s.msgSearch || "").trim() || s.msgClient || ""; }
  function loadMessages(after){
    if (s.msgSeg !== "all"){ loadSegView(); if (after) after(); return; }
    s.msgsLoading = true;
    var c = pickedClient();
    api("api/messages?page=" + s.msgPage + "&size=20" + (c ? "&clientId=" + encodeURIComponent(c) : ""))
      .then(function(list){
        s.messages = list || [];
        s.msgsLoading = false;
        s.msgHasNext = (s.messages || []).length >= 20;
        if (after) after();
      }).catch(function(e){ s.msgsLoading = false; A.toast(s, e.error || "请求失败", "bad"); });
  }
  /* 待处理/死信视图：由客户端聚合数驱动 */
  function loadSegView(){
    var targets = s.clients.filter(function(c){
      var sel = pickedClient();
      return sel ? c.clientId === sel : true;
    }).filter(function(c){ return s.msgSeg === "dead" ? c.deadLetterCount > 0 : c.unfinishedCount > 0; });
    if (!targets.length){ s.messages = []; return; }
    var jobs = targets.map(function(c){
      return s.msgSeg === "dead"
        ? api("api/dead-letters?clientId=" + encodeURIComponent(c.clientId)).then(function(l){ return l || []; })
        : api("api/clients/" + encodeURIComponent(c.clientId) + "/unfinished?limit=50").then(function(l){
            return (l || []).filter(function(d){ return d.state !== "DEAD_LETTER"; });
          });
    });
    Promise.all(jobs).then(function(groups){
      s.messages = [].concat.apply([], groups);   /* 行结构兼容：messageId/clientId/state/stateText/retryCount/updatedAt */
    }).catch(function(e){ A.toast(s, e.error || "请求失败", "bad"); });
  }
  function openMessage(id){
    s.currentMsgId = id; s.mdOpen = true; s.detailLoading = true;
    s.detail = null; s.seqBlocked = null; s.mdTab = "info";
    api("api/messages/" + encodeURIComponent(id)).then(function(m){
      s.detail = m; s.detailLoading = false;
      if (m.sequenceKey && m.sequenceKey !== "—"){
        api("api/seq-blocked?clientId=" + encodeURIComponent(m.clientId)
            + "&messageId=" + encodeURIComponent(m.messageId))
          .then(function(r){ s.seqBlocked = r.blocked || 0; }).catch(function(){});
      }
    }).catch(function(e){ s.detailLoading = false; A.toast(s, e.error || "加载失败", "bad"); });
  }
  function closeDetail(){ s.mdOpen = false; }
  function goClientMessages(){
    var from = s.cdCurrent;
    go("messages");
    s.msgSeg = "all"; s.msgClient = from; s.msgSearch = ""; s.msgPage = 1;
    loadMessages(function(){
      /* 自动选中该客户端最新一条，直接出详情 */
      if (s.messages.length) openMessage(s.messages[0].messageId);
    });
  }

  /* ── 写操作（统一确认 + reason 留痕） ── */
  function confirmOp(title, desc, okLabel, handler, after){
    s.confirm = {on:true, title:title, desc:desc || "", okLabel:"确认" + (okLabel || "执行"),
                 extra:null, busy:false, reason:""};
    confirmHandler = handler; afterHandler = after || null;
  }
  function confirmSubmit(){
    var reason = (s.confirm.reason || "").trim();
    if (!reason){ A.toast(s, "请填写操作原因（必填留痕）", "warn"); return; }
    if (!confirmHandler){ s.confirm.on = false; return; }
    s.confirm.busy = true;
    confirmHandler(reason).then(function(res){
      s.confirm.busy = false; s.confirm.on = false;
      A.toast(s, "操作已执行" + (res && res.ok === false ? "（目标记录不满足条件）" : ""));
      if (afterHandler) afterHandler(res); else refreshCurrent();
    }).catch(function(e){ s.confirm.busy = false; A.toast(s, e.error || "操作失败", "bad"); });
  }
  function retryTask(clientId, messageId){
    confirmOp("重试死信投递", messageId + " 将从死信转回重试队列，按退避策略重新推送。", "重试",
      function(reason){ return api("api/actions/retry-dead-letter",
        {method:"POST", body:{clientId: clientId, messageId: messageId, reason: reason}}); });
  }
  function skipTask(clientId, messageId){
    confirmOp("跳过该投递", messageId + " 将转为终态（不再投递），同顺序键的后续消息放行。此操作不可撤销。", "跳过",
      function(reason){ return api("api/actions/skip",
        {method:"POST", body:{clientId: clientId, messageId: messageId, reason: reason}}); });
  }
  function batchSkip(){
    var c = pickedClient();
    if (!c){ A.toast(s, "请先选择具体客户端", "warn"); return; }
    confirmOp("批量跳过死信", "将跳过 " + c + " 名下全部死信投递（转为终态）。此操作不可撤销。", "批量跳过",
      function(reason){ return api("api/actions/batch-skip",
        {method:"POST", body:{clientId: c, reason: reason}}); });
  }
  function forceTransitionModal(){
    var c = pickedClient();
    if (!c){ A.toast(s, "请先选择具体客户端", "warn"); return; }
    confirmOp("通用状态变更", "将指定消息强制变更为目标状态（仅限运维兜底场景，需明确知道影响）。", "变更",
      function(reason){
        var id = (s.confirmExtra.ftMsgId || "").trim();
        if (!id){ A.toast(s, "请填写消息 ID", "warn"); return Promise.reject({error:"消息 ID 不能为空"}); }
        return api("api/actions/force-transition",
          {method:"POST", body:{clientId: c, messageId: id, target: s.confirmExtra.ftTarget, reason: reason}});
      });
    s.confirm.extra = "ft";
    s.confirmExtra = {ftMsgId: "", ftTarget: "RETRYING"};
  }

  /* ── 客户端页 ── */
  function setClSeg(seg){ s.clSeg = seg; loadClients(); }
  function loadClients(){

    /* 若当前选中客户端已被注销，自动回落到第一个 */
    if (s.cdCurrent && !s.clientById(s.cdCurrent)){
      s.cdCurrent = s.clients.length ? s.clients[0].clientId : "";
      s.obData = []; s.obPulledAt = "";
    }
  }
  function selectClient(id){ s.cdCurrent = id; s.obData = []; s.obPulledAt = ""; }
  function shortId(id){
    var digits = String(id).replace(/[^0-9]/g, "");
    return digits.length >= 2 ? digits.slice(-2) : String(id).slice(0, 2).toUpperCase();
  }
  function toggleClient(clientId, enabled){
    var next = !enabled;
    confirmOp(next ? "启用客户端" : "停用客户端",
      next ? clientId + " 将恢复接收新消息与接入。" : "停用后 " + clientId + " 将拒收新消息并拒绝接入，存量数据保留。", next ? "启用" : "停用",
      function(reason){ return api("api/actions/" + (next ? "enable" : "disable"),
        {method:"POST", body:{clientId: clientId, reason: reason}}); });
  }
  function kick(clientId){
    confirmOp("断开连接", "将关闭 " + clientId + " 的全部 SSE 连接，客户端会按退避策略自动重连补发。", "断开",
      function(reason){ return api("api/actions/disconnect",
        {method:"POST", body:{clientId: clientId, reason: reason}}); });
  }
  function removeClientOp(clientId){
    confirmOp("注销客户端 " + clientId,
      "将把 " + clientId + " 移出白名单：立即失去接入资格，历史数据保留。建议先断开其连接。此操作不可撤销。", "注销",
      function(reason){ return api("api/actions/remove-client",
        {method:"POST", body:{clientId: clientId, reason: reason}}); },
      function(){ refreshCurrent(); });
  }
  function sendCmd(clientId, command){
    confirmOp("下发指令 " + command, "指令经 SSE ctrl 通道实时下发，仅在线客户端生效。", "下发",
      function(reason){ return api("api/actions/client-command",
        {method:"POST", body:{clientId: clientId, command: command, reason: reason}}); },
      function(res){ A.toast(s, command + " 已送达 " + (res.delivered || 0) + " 个连接", res.delivered ? "" : "warn"); });
  }

  /* ── 添加客户端 ── */
  function openRegisterModal(){
    confirmOp("添加客户端", "新客户端 ID 加入服务端白名单后即可接入（SSE 连接 / 上下行消息）。", "添加",
      function(reason){
        var id = (s.confirmExtra.regId || "").trim();
        if (!id){ A.toast(s, "请填写客户端 ID", "warn"); return Promise.reject({error:"客户端 ID 不能为空"}); }
        return api("api/actions/register-client",
          {method:"POST", body:{newClientId: id, enabled: s.confirmExtra.regEnabled, reason: reason}});
      },
      function(){ refreshCurrent(); });
    s.confirm.extra = "register";
    s.confirmExtra = {regId: "", regEnabled: true};
  }
  function genId(){
    var d = new Date(), p = function(n){ return (n < 10 ? "0" : "") + n; };
    s.confirmExtra.regId = "client-" + p(d.getMonth()+1) + p(d.getDate()) + "-" + Math.floor(1000 + Math.random() * 9000);
  }

  /* ── 本地队列（快照） ── */
  function pullOutbox(){
    var c = s.clientById(s.cdCurrent);
    if (c && !c.online){ A.toast(s, "客户端离线，上线后才能拉取", "warn"); return; }
    s.obPulling = true;
    api("api/actions/client-command", {method:"POST", body:{
      clientId: s.cdCurrent, command: "QUERY_OUTBOX",
      status: s.obStatus || null, limit: 50,
      reason: "控制台拉取本地队列快照"}}).then(function(res){
      if (!res.queryId){ s.obPulling = false; A.toast(s, "指令未送达（客户端离线）", "bad"); return; }
      var n = 0;
      var timer = setInterval(function(){
        api("api/command-result/" + encodeURIComponent(res.queryId)).then(function(r){
          if (!r.result){
            if (++n >= 15){ clearInterval(timer); s.obPulling = false;
              A.toast(s, "等待回执超时（客户端可能中途离线或未执行）", "bad"); }
            return;
          }
          clearInterval(timer); s.obPulling = false;
          s.obData = JSON.parse(r.result).rows || [];
          s.obPulledAt = new Date().toLocaleTimeString("zh-CN", {hour12:false});
          A.toast(s, "已获取本地队列快照（" + s.obData.length + " 条）");
        });
      }, 2000);
    }).catch(function(e){ s.obPulling = false; A.toast(s, e.error || "拉取失败", "bad"); });
  }
  function retryOutbox(idx){
    var r = s.obData[idx];
    confirmOp("重发上行死信", r.messageId + " 将由客户端本地重新发送到服务端。", "重发",
      function(reason){ return outboxOp("RETRY_OUTBOX_DEAD_LETTER", r.messageId, reason); });
  }
  function discardOutbox(idx){
    var r = s.obData[idx];
    confirmOp("放弃上行死信", r.messageId + " 将从客户端本地队列丢弃。此操作不可撤销。", "放弃",
      function(reason){ return outboxOp("DISCARD_OUTBOX_DEAD_LETTER", r.messageId, reason); });
  }
  function outboxOp(command, messageId, reason){
    return api("api/actions/client-command", {method:"POST", body:{
      clientId: s.cdCurrent, command: command, messageId: messageId, reason: reason}});
  }
  function obStateCls(text){
    if (text && text.indexOf("死信") >= 0) return "bad";
    if (text && text.indexOf("失败") >= 0) return "warn";
    if (text && text.indexOf("已接受") >= 0) return "ok";
    return "mut";
  }

  /* ── 模板辅助 ── */
  function countBy(fn){
    return s.clients.filter(fn).length;
  }
  function onlineCount(){ return countBy(function(c){ return c.enabled && c.online; }); }
  function offlineCount(){ return countBy(function(c){ return c.enabled && !c.online; }); }
  function disabledCount(){ return countBy(function(c){ return !c.enabled; }); }
  function todayCount(){
    if (!s.publishedPerDay.length) return 0;
    return s.publishedPerDay[s.publishedPerDay.length - 1].count || 0;
  }
  function pct(n){
    return s.clients.length ? Math.round(n / s.clients.length * 100) : 0;
  }
  function clientPill(c){
    if (!c.enabled) return '<span class="pill warn"><i></i>已禁用</span>';
    return c.online ? '<span class="pill ok"><i></i>在线</span>' : '<span class="pill mut"><i></i>离线</span>';
  }
  function navClients(){
    return s.clients.filter(function(c){
      if (s.clSeg === "online") return c.enabled && c.online;
      if (s.clSeg === "offline") return c.enabled && !c.online;
      if (s.clSeg === "dead") return (c.deadLetterCount || 0) > 0 || (!c.enabled);
      return true;
    });
  }
  function cdC(){ return s.clientById(s.cdCurrent); }
  function prettyPayload(){
    if (!s.detail || !s.detail.payload) return "（无）";
    var p = s.detail.payload;
    if (p.indexOf("{") === 0){ try { return JSON.stringify(JSON.parse(p), null, 2); } catch(e){} }
    return p;
  }
  function obRaw(){
    return JSON.stringify({rows: s.obData}, null, 2);
  }
  /* 详情视图无顶层 state，从首条投递记录推导 */
  function detailState(){
    if (!s.detail || !s.detail.deliveries || !s.detail.deliveries.length) return null;
    return s.detail.deliveries[0].state;
  }
  function detailStateText(){
    if (!s.detail || !s.detail.deliveries || !s.detail.deliveries.length) return "未知";
    return s.detail.deliveries[0].stateText || s.detail.deliveries[0].state;
  }
  function globalSearch(){
    go("messages");
    s.msgClient = "";
    setMsgSeg("all");
  }

  /* ── 全局键盘 ── */
  document.addEventListener("keydown", function(e){
    if (e.key === "Escape"){ s.confirm.on = false; s.mdOpen = false; }
  });

  /* ── 自动刷新（弹窗打开时暂停） ── */
  setInterval(function(){
    if (!s.loggedIn || s.confirm.on) return;
    if (--s.tick <= 0){ s.tick = 10; refreshCurrent(); }
  }, 1000);
  /* 粘贴自动去 Bearer 前缀 */
  document.addEventListener("paste", function(e){
    var inp = e.target;
    if (!inp || inp.id !== "loginToken") return;
    var text = (e.clipboardData || window.clipboardData).getData("text") || "";
    if (/^Bearer\s+/i.test(text.trim())){
      e.preventDefault();
      s.loginInput = text.replace(/^Bearer\s+/i, "").trim();
      A.toast(s, "已自动去除 Bearer 前缀");
    }
  });


  /* ── 暴露给模板 ── */
  function api(path, opts){ return A.api(s, path, opts); }
  Vue.createApp({
    setup: function(){
      return {
        s: s, A: A,
        doLogin: doLogin, logout: logout, go: go, refreshCurrent: refreshCurrent,
        viewMessageFrom: viewMessageFrom,
        setMsgSeg: setMsgSeg, pageMsgs: pageMsgs, openMessage: openMessage,
        closeDetail: closeDetail, goClientMessages: goClientMessages,
        retryTask: retryTask, skipTask: skipTask, batchSkip: batchSkip,
        forceTransitionModal: forceTransitionModal, confirmSubmit: confirmSubmit,
        setClSeg: setClSeg, selectClient: selectClient,
        toggleClient: toggleClient, kick: kick, removeClientOp: removeClientOp, sendCmd: sendCmd,
        openRegisterModal: openRegisterModal, genId: genId,
        pullOutbox: pullOutbox, retryOutbox: retryOutbox, discardOutbox: discardOutbox,
        obStateCls: obStateCls, shortId: shortId,
        onlineCount: onlineCount, offlineCount: offlineCount, disabledCount: disabledCount,
        todayCount: todayCount, pct: pct, clientPill: clientPill,
        navClients: navClients, cdC: cdC, prettyPayload: prettyPayload,
        obRaw: obRaw, globalSearch: globalSearch,
        pickedClient: function(){ return s.pickedClient(); },
        detailState: detailState, detailStateText: detailStateText
      };
    }
  }).mount("#app");
};
