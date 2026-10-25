/* ═════════ 统一 API 请求层（规范 §7）
 * 职责：超时、AbortController 取消、错误解析、请求序号守卫
 *       （防旧响应覆盖新筛选）、页面切换取消全部在途请求。
 * 全局命名空间 Admin。
 */
window.Admin = (function(){
  var controllers = {};          /* tag -> AbortController */
  var seq = {};                  /* tag -> 请求序号 */

  function esc(s){return String(s == null ? "" : s).replace(/[&<>"]/g, function(c){
    return {"&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;"}[c];});}

  function fmtT(ms){
    if (!ms) return "—";
    var d = new Date(ms);
    function p(n){ return (n < 10 ? "0" : "") + n; }
    return d.getFullYear() + "-" + p(d.getMonth()+1) + "-" + p(d.getDate()) + " " + p(d.getHours()) + ":" + p(d.getMinutes()) + ":" + p(d.getSeconds());
  }

  /* 状态标签（颜色+文字+图标三重表达，规范 §4.4/§10） */
  var STATES = {
    CREATED:    {cls:"mut",  txt:"已创建"},
    ACCEPTED:   {cls:"acc",  txt:"已接受"},
    RECEIVED:   {cls:"acc",  txt:"客户端已接收"},
    PROCESSED:  {cls:"ok",   txt:"处理成功"},
    FAILED:     {cls:"warn", txt:"发送失败"},
    RETRYING:   {cls:"warn", txt:"重试中"},
    EXPIRED:    {cls:"mut",  txt:"已过期/跳过"},
    DEAD_LETTER:{cls:"bad",  txt:"死信"}
  };
  var ICONS = {
    ok:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>',
    warn:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M10.3 3.9L1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>',
    bad:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><circle cx="12" cy="12" r="9"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>',
    mut:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><circle cx="12" cy="12" r="9"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>',
    acc:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><circle cx="12" cy="12" r="5.5" fill="currentColor" stroke="none"/></svg>'
  };
  function stPill(state, text){
    var st = STATES[state] || {cls:"mut", txt:state || "未知"};
    var txt = text || st.txt;
    return '<span class="tag ' + st.cls + '">' + ICONS[st.cls === "acc" ? "acc" : st.cls] + esc(txt) + '</span>';
  }

  /* 统一请求。opts: {method, body, tag, timeout}
   * 返回 Promise；被取消/序号失效时 reject {silent:true}（调用方应静默忽略）。 */
  function api(store, path, opts){
    opts = opts || {};
    var tag = opts.tag || path;
    if (controllers[tag]) { try { controllers[tag].abort(); } catch(e){} }
    var ctrl = ("AbortController" in window) ? new AbortController() : null;
    controllers[tag] = ctrl;
    var mySeq = (seq[tag] = (seq[tag] || 0) + 1);
    var timedOut = false;
    var timer = setTimeout(function(){
      timedOut = true;
      if (ctrl) { try { ctrl.abort(); } catch(e){} }
    }, opts.timeout || 15000);

    function finish(){ clearTimeout(timer); if (controllers[tag] === ctrl) delete controllers[tag]; }

    return fetch(path, {
      method: opts.method || "GET",
      body: opts.body ? JSON.stringify(opts.body) : null,
      headers: {"Authorization": "Bearer " + store.token, "Content-Type": "application/json"},
      signal: ctrl ? ctrl.signal : undefined
    }).then(function(res){
      return res.text().then(function(t){
        finish();
        var d = null; try { d = t ? JSON.parse(t) : null; } catch(e){}
        if (!res.ok){
          if (res.status === 401) store.logout();
          throw {status: res.status, error: d && d.error || "请求失败（" + res.status + "）"};
        }
        return d;
      });
    }).catch(function(e){
      finish();
      if (e && e.name === "AbortError"){
        throw {error: timedOut ? "请求超时，请重试" : "请求已取消", timeout: !!timedOut, silent: !timedOut};
      }
      throw e;
    }).then(function(d){
      if (seq[tag] !== mySeq) throw {silent: true};   /* 旧响应丢弃 */
      return d;
    });
  }

  function cancelAll(){
    Object.keys(controllers).forEach(function(k){
      try { controllers[k].abort(); } catch(e){}
    });
    controllers = {};
  }
  function isSilent(e){ return !!(e && e.silent); }

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
      navigator.clipboard.writeText(text).then(function(){}, function(){});
    }
  }

  return {esc:esc, fmtT:fmtT, stPill:stPill, api:api, toast:toast, copyText:copyText,
          cancelAll:cancelAll, isSilent:isSilent, STATES:STATES};
})();
