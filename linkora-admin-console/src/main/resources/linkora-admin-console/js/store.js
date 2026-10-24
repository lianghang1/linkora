/* ═════════ 全局响应式状态（Vue store） ═════════ */
window.createStore = function(Vue){
  return Vue.reactive({
    /* 会话 */
    token: sessionStorage.getItem("linkora-admin-token") || "",
    loggedIn: false,
    loginErr: "",
    loginInput: "",
    loginBusy: false,
    showToken: false,

    /* 全局 */
    view: "cockpit",
    tick: 10,
    clients: [],
    clientsLoaded: false,
    toasts: [],

    /* 概览 */
    publishedPerDay: [],
    recent: [],
    updTime: "—",

    /* 消息页 */
    msgSeg: "all",
    msgPage: 1,
    msgClient: "",
    msgSearch: "",
    messages: [],
    msgsLoading: false,
    msgHasNext: false,
    currentMsgId: "",
    detail: null,
    detailLoading: false,
    mdOpen: false,          /* 窄屏覆盖层开关（桌面由 CSS 常显，不受影响） */
    mdTab: "info",
    seqBlocked: null,

    /* 客户端页 */
    clSeg: "all",
    cdCurrent: "",
    obData: [],
    obStatus: "",
    obPulling: false,
    obPulledAt: "",

    /* 弹窗 */
    confirm: {on:false, title:"", desc:"", okLabel:"确认执行", extra:null, busy:false, reason:""},
    confirmExtra: {},       /* 注册/状态变更弹窗的额外字段 */

    /* 派生 */
    sumDead: function(){ return this.clients.reduce(function(s,c){ return s + (c.deadLetterCount||0); }, 0); },
    sumPending: function(){ return this.clients.reduce(function(s,c){ return s + (c.unfinishedCount||0); }, 0); },
    clientById: function(id){
      for (var i = 0; i < this.clients.length; i++) if (this.clients[i].clientId === id) return this.clients[i];
      return null;
    },
    pickedClient: function(){ return (this.msgSearch || "").trim() || this.msgClient || ""; }
  });
};
