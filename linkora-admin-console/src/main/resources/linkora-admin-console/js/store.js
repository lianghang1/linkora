/* ═════════ 全局响应式状态（单一状态源） ═════════ */
window.createStore = function(Vue){
  return Vue.reactive({
    /* 会话 */
    token: sessionStorage.getItem("linkora-admin-token") || "",
    loggedIn: false,
    loginErr: "", loginInput: "", loginBusy: false, showToken: false,

    /* 全局 */
    view: "inbox",
    tick: 10,
    backendOk: true,            /* 最近一次概览请求是否成功（顶部状态条依据） */
    clients: [],
    toasts: [],

    /* 收件箱（死信 + 长时间无推进） */
    inbox: [],
    inboxLoading: false,
    inboxError: "",

    /* 概览 */
    publishedPerDay: [],
    recent: [],
    updTime: "—",
    overviewErr: "",

    /* 消息页 */
    msgTab: "all",              /* all | pending | dead */
    msgPage: 1,
    msgClient: "",
    msgKeyword: "",   /* 主题/类型/消息 ID 关键词 */
    msgBegin: "",               /* YYYY-MM-DD（真实 API begin/end） */
    msgEnd: "",
    messages: [],
    msgsLoading: false,
    msgsError: "",
    msgHasNext: false,
    currentMsgId: "",
    detail: null,
    detailLoading: false,
    detailError: "",
    mdOpen: false,
    mdTab: "info",
    seqBlocked: null,

    /* 客户端页 */
    clTab: "all",               /* all | online | offline | disabled */
    cdId: "",                   /* 当前选中客户端 */
    opsMenuOpen: false,
    obStatus: "",
    obData: null,               /* null=未拉取 */
    obPulling: false,
    /* 远程命令六态：idle 已提交前 / submitted 已提交 / waiting 等待回执 /
       done 已完成 / failed 执行失败 / timeout 超时 / unknown 暂未取得结果 */
    obPhase: "idle",
    obPulledAt: "",


    /* 弹窗 */
    confirm: {on:false, title:"", desc:"", okLabel:"确认执行", extra:null, busy:false, reason:""},
    confirmExtra: {},
    toasts: [],

    /* ── 派生/辅助 ── */
    sumDead: function(){ return this.clients.reduce(function(s,c){ return s + (c.deadLetterCount||0); }, 0); },
    sumPending: function(){ return this.clients.reduce(function(s,c){ return s + (c.unfinishedCount||0); }, 0); },
    clientById: function(id){
      for (var i = 0; i < this.clients.length; i++) if (this.clients[i].clientId === id) return this.clients[i];
      return null;
    },
    pickedClient: function(){ return this.msgClient || ""; }
  });
};
