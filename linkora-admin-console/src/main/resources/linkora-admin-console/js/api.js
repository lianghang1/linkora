/* ═════════ 基础工具与 API 层（全局命名空间 Admin） ═════════ */
window.Admin = (function(){
  function esc(s){return String(s == null ? "" : s).replace(/[&<>"]/g, function(c){
    return {"&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;"}[c];});}

  function fmtT(ms){
    if (!ms) return "—";
    var d = new Date(ms);
    function p(n){ return (n < 10 ? "0" : "") + n; }
    return d.getFullYear() + "-" + p(d.getMonth()+1) + "-" + p(d.getDate()) + " " + p(d.getHours()) + ":" + p(d.getMinutes()) + ":" + p(d.getSeconds());
  }

  var STATE_CLS = {CREATED:"mut",ACCEPTED:"acc",RECEIVED:"acc",PROCESSED:"ok",FAILED:"warn",RETRYING:"warn",EXPIRED:"mut",DEAD_LETTER:"bad"};
  var STATE_TXT = {CREATED:"已创建",ACCEPTED:"已接受",RECEIVED:"客户端已接收",PROCESSED:"处理成功",FAILED:"发送失败",RETRYING:"重试中",EXPIRED:"已过期/跳过",DEAD_LETTER:"死信"};
  function stateCls(state){ return STATE_CLS[state] || "mut"; }
  function stateTxt(state){ return STATE_TXT[state] || state || "未知"; }

  function api(store, path, opts){
    opts = opts || {};
    var body = opts.body ? JSON.stringify(opts.body) : null;
    return fetch(path, {method: opts.method || "GET", body: body, headers: {
      "Authorization": "Bearer " + store.token,
      "Content-Type": "application/json"}}).then(function(res){
      return res.text().then(function(t){
        var d = null; try { d = t ? JSON.parse(t) : null; } catch(e){}
        if (!res.ok){
          if (res.status === 401) store.logout();
          throw {status: res.status, error: d && d.error || "请求失败（" + res.status + "）"};
        }
        return d;
      });
    });
  }

  function toast(store, msg, kind){
    var id = Date.now() + Math.random();
    store.toasts.push({id: id, msg: msg, kind: kind || ""});
    setTimeout(function(){
      var idx = store.toasts.findIndex(function(t){ return t.id === id; });
      if (idx >= 0) store.toasts.splice(idx, 1);
    }, 2900);
  }

  function copyText(text){
    if (navigator.clipboard && navigator.clipboard.writeText){
      navigator.clipboard.writeText(text).then(function(){ /* 静默成功 */ });
    }
  }

  function stPill(state, text){
    var cls = stateCls(state);
    var txt = text || stateTxt(state);
    return '<span class="pill ' + cls + '"><i></i>' + esc(txt) + '</span>';
  }

  return {esc:esc, fmtT:fmtT, stateCls:stateCls, stateTxt:stateTxt, stPill:stPill, api:api, toast:toast, copyText:copyText};
})();
