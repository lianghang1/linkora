/* ═════════ Vue 根应用（页面方法，全部经 store 状态驱动） ═════════ */
window.bootApp = function(){
  var Vue = window.Vue, A = window.Admin;
  var s = window.createStore(Vue);
  var charts = window.createCharts();

  var confirmHandler = null, afterHandler = null;
  var obTimer = null;            /* 本地队列回执轮询定时器（切客户端/退出时清理） */

  function errToast(e){ if (!A.isSilent(e)) A.toast(s, e && e.error || "请求失败", "bad"); }
  function api(path, opts){ return A.api(s, path, opts); }

  /* ── 会话 ── */
  function doLogin(){
    var v = (s.loginInput || "").trim();
    if (!v){ s.loginErr = "请输入访问令牌"; return; }
    s.loginBusy = true; s.loginErr = "";
    var prev = s.token; s.token = v;
    api("api/overview", {tag: "login"}).then(function(){
      sessionStorage.setItem("linkora-admin-token", v);
      s.loginBusy = false;
      boot();
    }).catch(function(e){
      s.token = prev; s.loginBusy = false;
      s.loginErr = e.error || "登录失败：请检查服务端 linkora.admin-console.token 配置";
    });
  }
  function logout(){
    sessionStorage.removeItem("linkora-admin-token");
    s.token = ""; s.loggedIn = false; s.cdId = ""; s.obData = null; s.obPhase = "idle";
    charts.destroyAll();
  }
  function boot(){
    s.loggedIn = true;
    loadClients(function(){
      go("inbox");
    });
  }

  /* ── 导航与刷新（页面切换取消在途请求） ── */
  function go(v){
    if (s.view !== v) A.cancelAll();
    s.view = v;
    stopObPolling();
    refreshCurrent();
    window.scrollTo(0, 0);
  }
  function refreshCurrent(){
    if (s.view === "inbox") loadInbox();
    else if (s.view === "messages") loadMessages();
    else if (s.view === "clients") loadClients();
  }

  /* ── 客户端列表（概览与客户端页共用数据源） ── */
  function loadClients(after){
    api("api/clients", {tag: "clients"}).then(function(clients){
      s.clients = clients || [];
      if (s.cdId && !s.clientById(s.cdId)) resetClientSelection();
      if (!s.cdId && s.clients.length) s.cdId = s.clients[0].clientId;
      if (after) after();
    }).catch(function(e){ errToast(e); if (after) after(); });
  }
  function resetClientSelection(){
    s.cdId = ""; s.obData = null; s.obPhase = "idle"; s.obPulledAt = "";
  }

  /* ── 收件箱（死信 + 长时间无推进） ── */
  function loadInbox(){
    s.inboxLoading = true; s.inboxError = "";
    api("api/inbox", {tag: "inbox"}).then(function(items){
      s.inboxLoading = false;
      s.inbox = items || [];
      s.updTime = new Date().toLocaleTimeString("zh-CN", {hour12:false});
    }).catch(function(e){
      s.inboxLoading = false;
      if (!A.isSilent(e)) s.inboxError = e.error || "加载失败";
    });
  }

  /* ── 概览 ── */
  function refreshCockpit(){
    api("api/overview", {tag: "overview"}).then(function(d){
      s.backendOk = true; s.overviewErr = "";
      s.clients = d.clients || [];
      s.publishedPerDay = d.publishedPerDay || [];
      s.updTime = new Date().toLocaleTimeString("zh-CN", {hour12:false});
      Vue.nextTick(function(){
        charts.renderTrend(s.publishedPerDay);
        charts.renderDonut(s.clients);
      });
      loadRecent();
    }).catch(function(e){
      if (A.isSilent(e)) return;
      /* 后端不可用：页面给出明确错误，不白屏不伪装数据 */
      s.backendOk = false; s.overviewErr = e.error || "无法连接服务端";
    });
  }
  function loadRecent(){
    api("api/messages?page=1&size=8", {tag: "recent"}).then(function(list){
      s.recent = list || [];
    }).catch(function(e){ if (!A.isSilent(e)) s.recent = []; });
  }
  function viewMessageFrom(id){
    go("messages");
    s.msgTab = "all"; s.msgPage = 1; s.msgClient = ""; s.msgKeyword = ""; s.msgBegin = ""; s.msgEnd = "";
    loadMessages(function(){ openMessage(id); });
  }

  /* ── 消息页 ── */
  function setMsgTab(t){ s.msgTab = t; s.msgPage = 1; loadMessages(); }
  function pageMsgs(delta){ s.msgPage = Math.max(1, s.msgPage + delta); loadMessages(); }
  function pickedClient(){ return (s.msgKeyword || "").trim() || s.msgClient || ""; }
  function timeRangeParam(){
    var p = "";
    if (s.msgBegin) p += "&begin=" + new Date(s.msgBegin + "T00:00:00").getTime();
    if (s.msgEnd)   p += "&end="   + new Date(s.msgEnd + "T23:59:59").getTime();
    return p;
  }
  function loadMessages(after){
    if (s.msgTab !== "all"){ loadTaskView(); if (after) after(); return; }
    s.msgsLoading = true; s.msgsError = "";
    var c = pickedClient();
    api("api/messages?page=" + s.msgPage + "&size=20"
        + (c ? "&clientId=" + encodeURIComponent(c) : "")
        + ((s.msgKeyword || "").trim() ? "&keyword=" + encodeURIComponent(s.msgKeyword.trim()) : "")
        + timeRangeParam(),
      {tag: "messages"}).then(function(list){
        s.msgsLoading = false;
        s.messages = list || [];
        s.msgHasNext = s.messages.length >= 20;
        if (after) after();
      }).catch(function(e){
        s.msgsLoading = false;
        if (!A.isSilent(e)) s.msgsError = e.error || "加载失败";
      });
  }
  /* 待处理/死信视图：基于客户端聚合数拉取 */
  function loadTaskView(){
    s.msgsError = "";
    var targets = s.clients.filter(function(c){
      var sel = pickedClient();
      return sel ? c.clientId === sel : true;
    }).filter(function(c){ return s.msgTab === "dead" ? c.deadLetterCount > 0 : c.unfinishedCount > 0; });
    if (!targets.length){ s.messages = []; return; }
    var jobs = targets.map(function(c){
      return s.msgTab === "dead"
        ? api("api/dead-letters?clientId=" + encodeURIComponent(c.clientId), {tag: "dead:" + c.clientId}).then(function(l){ return l || []; })
        : api("api/clients/" + encodeURIComponent(c.clientId) + "/unfinished?limit=50", {tag: "run:" + c.clientId}).then(function(l){
            return (l || []).filter(function(d){ return d.state !== "DEAD_LETTER"; });
          });
    });
    Promise.all(jobs).then(function(groups){
      s.messages = [].concat.apply([], groups);
    }).catch(function(e){ if (!A.isSilent(e)) s.msgsError = e.error || "加载失败"; });
  }
  function openMessage(id){
    s.currentMsgId = id; s.mdOpen = true;
    s.detail = null; s.detailLoading = true; s.detailError = ""; s.seqBlocked = null; s.mdTab = "info";
    api("api/messages/" + encodeURIComponent(id), {tag: "detail"}).then(function(m){
      s.detail = m; s.detailLoading = false;
      if (m.sequenceKey && m.sequenceKey !== "—"){
        api("api/seq-blocked?clientId=" + encodeURIComponent(m.clientId)
            + "&messageId=" + encodeURIComponent(m.messageId), {tag: "seqblock"})
          .then(function(r){ s.seqBlocked = r.blocked || 0; }).catch(function(){});
      }
    }).catch(function(e){
      s.detailLoading = false;
      if (!A.isSilent(e)) s.detailError = e.error || "加载失败";
    });
  }
  function closeDetail(){ s.mdOpen = false; }
  function goClientMessages(){
    var from = s.cdId;
    go("messages");
    s.msgTab = "all"; s.msgClient = from; s.msgKeyword = ""; s.msgPage = 1;
    s.msgBegin = ""; s.msgEnd = "";
    loadMessages(function(){
      /* 自动选中该客户端最新一条并直接打开详情 */
      if (s.messages.length) openMessage(s.messages[0].messageId);
    });
  }
  function detailState(){
    if (s.detail && s.detail.deliveries && s.detail.deliveries.length) return s.detail.deliveries[0].state;
    return "UPLINK";   /* 无投递记录 = 上行消息（客户端发布，服务端直接接收） */
  }
  function detailStateText(){
    if (s.detail && s.detail.deliveries && s.detail.deliveries.length){
      var d0 = s.detail.deliveries[0];
      return d0.stateText || d0.state || "未知";
    }
    return "上行消息 · 服务端已接收";
  }
  function prettyPayload(){
    if (!s.detail || !s.detail.payload) return "（无）";
    var p = s.detail.payload;
    if (p.indexOf("{") === 0){ try { return JSON.stringify(JSON.parse(p), null, 2); } catch(e){} }
    return p;
  }

  /* ── 写操作（统一确认 + reason 留痕 + 防重复提交） ── */
  function confirmOp(title, desc, okLabel, handler, after){
    s.confirm = {on:true, title:title, desc:desc || "", okLabel:"确认" + (okLabel || "执行"),
                 extra:null, busy:false, reason:""};
    confirmHandler = handler; afterHandler = after || null;
  }
  function confirmSubmit(){
    var reason = (s.confirm.reason || "").trim();
    if (!reason){ A.toast(s, "请填写操作原因（必填留痕）", "warn"); return; }
    if (!confirmHandler || s.confirm.busy) return;
    s.confirm.busy = true;
    confirmHandler(reason).then(function(res){
      s.confirm.busy = false; s.confirm.on = false;
      /* 解析真实业务结果，不凭 HTTP 200 断定成功 */
      var failed = res && res.ok === false;
      A.toast(s, failed ? "请求已提交，但目标记录不满足条件" : "操作已执行", failed ? "warn" : "");
      if (afterHandler) afterHandler(res); else refreshCurrent();
    }).catch(function(e){
      s.confirm.busy = false;
      A.toast(s, e.error || "操作失败，可安全重试", "bad");
    });
  }
  function retryTask(clientId, messageId){
    confirmOp("重试死信投递", messageId + " 将从死信转回重试队列，按退避策略重新推送。", "重试",
      function(reason){ return api("api/actions/retry-dead-letter",
        {method:"POST", body:{clientId: clientId, messageId: messageId, reason: reason}}); });
  }
  function skipTask(clientId, messageId){
    confirmOp("跳过该投递",
      messageId + " 将转为终态（不再投递），同顺序键的后续消息放行。注意：跳过不会撤回已经在途的推送。此操作不可撤销。", "跳过",
      function(reason){ return api("api/actions/skip",
        {method:"POST", body:{clientId: clientId, messageId: messageId, reason: reason}}); });
  }
  function batchSkip(){
    var c = pickedClient();
    if (!c){ A.toast(s, "请先选择具体客户端", "warn"); return; }
    confirmOp("批量跳过死信",
      "将跳过 " + c + " 名下全部死信投递（转为终态）。注意：跳过不会撤回已经在途的推送。此操作不可撤销。", "批量跳过",
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

  function clearFilters(){
    s.msgClient = ""; s.msgKeyword = ""; s.msgBegin = ""; s.msgEnd = ""; s.msgPage = 1;
    loadMessages();
  }


  /* ── 客户端页 ── */
  function setClTab(t){ s.clTab = t; }
  function selectClient(id){
    if (s.cdId === id) return;
    stopObPolling();
    s.cdId = id; s.obData = null; s.obPhase = "idle"; s.obPulledAt = ""; s.obStatus = "";
  }
  function shortId(id){
    var digits = String(id).replace(/[^0-9]/g, "");
    return digits.length >= 2 ? digits.slice(-2) : String(id).slice(0, 2).toUpperCase();
  }
  function clientPill(c){
    if (!c.enabled) return '<span class="tag warn">' + ICON_WARN + '已禁用</span>';
    return c.online ? '<span class="tag ok">' + ICON_OK + '在线</span>' : '<span class="tag mut">' + ICON_MUT + '离线</span>';
  }
  var ICON_OK = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>';
  var ICON_WARN = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M10.3 3.9L1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>';
  var ICON_MUT = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><circle cx="12" cy="12" r="9"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>';

  function toggleClient(clientId, enabled){
    var next = !enabled;
    confirmOp(next ? "启用客户端" : "禁用客户端",
      next ? clientId + " 将恢复接收新消息与接入。"
           : "禁用后 " + clientId + " 将拒收新消息并拒绝接入，存量数据保留。", next ? "启用" : "禁用",
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
      function(){ if (s.cdId === clientId) resetClientSelection(); refreshCurrent(); });
  }
  function sendCmd(clientId, command){
    confirmOp("下发指令 " + command, "指令经 SSE ctrl 通道实时下发，仅在线客户端生效。", "下发",
      function(reason){ return api("api/actions/client-command",
        {method:"POST", body:{clientId: clientId, command: command, reason: reason}}); },
      function(res){ A.toast(s, command + " 已送达 " + (res.delivered || 0) + " 个连接", res.delivered ? "" : "warn"); });
  }
  function opsMenuToggle(){ s.opsMenuOpen = !s.opsMenuOpen; }
  function cdC(){ return s.clientById(s.cdId); }
  function navClients(){
    return s.clients.filter(function(c){
      if (s.clTab === "online") return c.enabled && c.online;
      if (s.clTab === "offline") return c.enabled && !c.online;
      if (s.clTab === "disabled") return !c.enabled;
      return true;
    });
  }
  function clientCounts(){
    var r = {all: s.clients.length, online: 0, offline: 0, disabled: 0};
    s.clients.forEach(function(c){
      if (c.enabled && c.online) r.online++;
      else if (c.enabled) r.offline++;
    });
    return r;
  }

  /* ── 添加客户端 ── */
  function openRegisterModal(){
    confirmOp("添加客户端", "新客户端 ID 加入服务端白名单后即可接入（SSE 连接 / 上下行消息）。", "添加",
      function(reason){
        var id = (s.confirmExtra.regId || "").trim();
        if (!id){ A.toast(s, "请填写客户端 ID", "warn"); return Promise.reject({error:"客户端 ID 不能为空"}); }
        var name = (s.confirmExtra.regName || "").trim();
        return api("api/actions/register-client",
          {method:"POST", body:{newClientId: id, enabled: s.confirmExtra.regEnabled,
                                displayName: name || null, reason: reason}});
      },
      function(){ refreshCurrent(); });
    s.confirm.extra = "register";
    s.confirmExtra = {regId: "", regName: "", regEnabled: true};
  }
  function genId(){
    var d = new Date(), p = function(n){ return (n < 10 ? "0" : "") + n; };
    s.confirmExtra.regId = "client-" + p(d.getMonth()+1) + p(d.getDate()) + "-" + Math.floor(1000 + Math.random() * 9000);
  }

  /* ── 本地队列：远程命令六态状态机（规范 §5.3/§5.4） ──
     idle → submitted(已提交) → waiting(等待回执) → done(已完成)
                                    ↘ timeout(超时) / failed(失败) / unknown(暂未取得结果) */
  function stopObPolling(){ if (obTimer){ clearInterval(obTimer); obTimer = null; } }
  function pullOutbox(){
    var c = s.clientById(s.cdId);
    if (c && !c.online){
      A.toast(s, "客户端离线，本地队列暂不可用", "warn");
      return;
    }
    stopObPolling();
    s.obPulling = true; s.obPhase = "submitted"; s.obData = null; s.obPulledAt = "";
    api("api/actions/client-command", {method:"POST", body:{
      clientId: s.cdId, command: "QUERY_OUTBOX",
      status: s.obStatus || null, limit: 50,
      reason: "控制台拉取本地队列快照"}}).then(function(res){
      if (!res.queryId){
        s.obPulling = false; s.obPhase = "failed";
        A.toast(s, "指令未送达（客户端离线）", "bad");
        return;
      }
      s.obPhase = "waiting";
      var n = 0, MAX = 15, INTERVAL = 2000;   /* 轮询有间隔与最大时长，不会无限高频 */
      obTimer = setInterval(function(){
        api("api/command-result/" + encodeURIComponent(res.queryId), {tag: "cmdresult"}).then(function(r){
          if (!r.result){
            if (++n >= MAX){
              stopObPolling(); s.obPulling = false; s.obPhase = "unknown";
              /* getCommandResult 无结果且语义不能确定原因：显示"暂未取得结果"，可手动重查 */
            }
            return;
          }
          stopObPolling(); s.obPulling = false; s.obPhase = "done";
          s.obData = JSON.parse(r.result).rows || [];
          s.obPulledAt = new Date().toLocaleTimeString("zh-CN", {hour12:false});
          A.toast(s, "已获取本地队列快照（" + s.obData.length + " 条）");
        }).catch(function(e){
          stopObPolling(); s.obPulling = false; s.obPhase = "failed";
          if (!A.isSilent(e)) A.toast(s, e.error || "查询回执失败", "bad");
        });
      }, INTERVAL);
    }).catch(function(e){
      s.obPulling = false; s.obPhase = "failed";
      if (!A.isSilent(e)) A.toast(s, e.error || "指令下发失败", "bad");
    });
  }
  function obPhaseText(){
    switch (s.obPhase){
      case "submitted": return "已提交指令，等待客户端调度执行…";
      case "waiting":   return "客户端执行中，等待回执回传…";
      case "done":      return "已完成 · 拉取于 " + s.obPulledAt;
      case "failed":    return "执行失败（客户端可能中途离线），可重试";
      case "timeout":   return "等待回执超时";
      case "unknown":   return "暂未取得结果（客户端可能未执行或中途离线），可重新拉取";
      default:          return "";
    }
  }
  function obPhaseCls(){
    switch (s.obPhase){
      case "done": return "ok";
      case "failed": case "timeout": case "unknown": return "bad";
      default: return "";
    }
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
      clientId: s.cdId, command: command, messageId: messageId, reason: reason}});
  }
  function obStateTag(text){
    if (!text) return "";
    if (text.indexOf("死信") >= 0) return "bad";
    if (text.indexOf("失败") >= 0) return "warn";
    if (text.indexOf("已接受") >= 0) return "ok";
    return "mut";
  }

  /* ── 全局事件 ── */
  document.addEventListener("keydown", function(e){
    if (e.key === "Escape"){ s.confirm.on = false; s.mdOpen = false; s.opsMenuOpen = false; }
  });
  document.addEventListener("click", function(e){
    /* 点击菜单外关闭操作菜单 */
    if (s.opsMenuOpen && !e.target.closest(".opsmenu")) s.opsMenuOpen = false;
  });

  /* ── 自动刷新（仅当前视图；弹窗打开时暂停） ── */
  setInterval(function(){
    if (!s.loggedIn || s.confirm.on) return;
    if (--s.tick <= 0){ s.tick = 10; refreshCurrent(); }
  }, 1000);

  /* ── 暴露给模板 ── */
  function api(path, opts){ return A.api(s, path, opts); }
  Vue.createApp({
    setup: function(){
      return {
        s: s, A: A,
        doLogin: doLogin, logout: logout,
        go: go, refreshCurrent: refreshCurrent,
        onlineCount: function(){ return s.clients.filter(function(c){ return c.enabled && c.online; }).length; },
        offlineCount: function(){ return s.clients.filter(function(c){ return c.enabled && !c.online; }).length; },
        disabledCount: function(){ return s.clients.filter(function(c){ return !c.enabled; }).length; },
        todayCount: function(){
          return s.publishedPerDay.length ? (s.publishedPerDay[s.publishedPerDay.length-1].count || 0) : 0;
        },
        pct: function(n){ return s.clients.length ? Math.round(n / s.clients.length * 100) : 0; },
        clientPill: clientPill, shortId: shortId,
        viewMessageFrom: viewMessageFrom,
        setMsgTab: setMsgTab, pageMsgs: pageMsgs, pickedClient: function(){ return s.pickedClient(); },
        loadMessages: loadMessages,
        openMessage: openMessage, closeDetail: closeDetail,
        goClientMessages: goClientMessages,
        detailState: detailState, detailStateText: detailStateText, prettyPayload: prettyPayload,
        retryTask: retryTask, skipTask: skipTask, batchSkip: batchSkip,
        forceTransitionModal: forceTransitionModal, confirmSubmit: confirmSubmit,
        setClTab: setClTab, selectClient: selectClient, navClients: navClients, clientCounts: clientCounts, cdC: cdC,
        clearFilters: clearFilters, loadInbox: loadInbox,
        toggleClient: toggleClient, kick: kick, removeClientOp: removeClientOp,
        sendCmd: sendCmd, opsMenuToggle: opsMenuToggle,
        openRegisterModal: openRegisterModal, genId: genId,
        pullOutbox: pullOutbox, retryOutbox: retryOutbox, discardOutbox: discardOutbox,
        obPhaseText: obPhaseText, obPhaseCls: obPhaseCls, obStateTag: obStateTag
      };
    }
  }).mount("#app");
};
