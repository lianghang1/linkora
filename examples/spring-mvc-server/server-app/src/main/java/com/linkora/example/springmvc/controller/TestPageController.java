package com.linkora.example.springmvc.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 消息触发测试页（仅示例工程，演示期辅助手工测试）。
 * 页面为纯静态 HTML + fetch，调用既有业务接口触发下行消息。
 *
 * @author lianghang
 */
@Controller
public class TestPageController {

    private static final String PAGE = "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">"
            + "<title>消息触发测试页</title><style>"
            + "body{font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;background:#0B0C12;color:#F2F3F7;"
            + "max-width:640px;margin:40px auto;padding:0 20px}"
            + "h1{font-size:20px}label{display:block;margin:12px 0 4px;font-size:13px;color:#A6ACBB}"
            + "input,textarea,select{width:100%;padding:9px 12px;border-radius:8px;border:1px solid rgba(255,255,255,.13);"
            + "background:rgba(255,255,255,.03);color:#F2F3F7;font-size:13px;box-sizing:border-box}"
            + "button{margin-top:16px;padding:10px 18px;border-radius:8px;border:none;cursor:pointer;"
            + "background:linear-gradient(135deg,#8B7CF8,#4CC3F7);color:#14102E;font-weight:600}"
            + ".res{margin-top:18px;padding:12px;border-radius:8px;background:#07070B;border:1px solid rgba(255,255,255,.07);"
            + "font-family:monospace;font-size:12px;white-space:pre-wrap;word-break:break-all;min-height:40px;color:#A9B6FF}"
            + "</style></head><body>"
            + "<h1>消息触发测试页</h1>"
            + "<label>目标客户端 clientId</label><input id='clientId' value='demo-client'>"
            + "<label>消息形态</label><select id='kind'>"
            + "<option value='order'>订单（带顺序键）</option>"
            + "<option value='nokey'>通知（无顺序键）</option>"
            + "<option value='review'>评论（带顺序键）</option></select>"
            + "<div id='orderFields'><label>订单号 orderNo</label><input id='orderNo' value='SMOKE-1'>"
            + "<label>商品 item</label><input id='item' value='test-item'></div>"
            + "<div id='reviewFields' style='display:none'><label>评论 ID reviewId</label><input id='reviewId' value='R-1'>"
            + "<label>内容 content</label><input id='content' value='测试评论'></div>"
            + "<button onclick='send()'>发送消息</button>"
            + "<div class='res' id='res'>等待发送…</div>"
            + "<p style='margin-top:22px;font-size:12px;color:#5D6370'>发送后到 /linkora-admin/ 控制台的「消息」页查看投递状态与时间线；"
            + "客户端（client-app）在线时监听器会直接收到。</p>"
            + "<script>"
            + "document.getElementById('kind').onchange=function(){var k=this.value;"
            + "document.getElementById('orderFields').style.display=(k==='review'?'none':'block');"
            + "document.getElementById('reviewFields').style.display=(k==='review'?'block':'none')};"
            + "function send(){var c=document.getElementById('clientId').value;var k=document.getElementById('kind').value;"
            + "var url,sep='?';"
            + "if(k==='order'){url='/api/orders'+sep+'clientId='+encodeURIComponent(c)+'&orderNo='+encodeURIComponent(document.getElementById('orderNo').value)+'&item='+encodeURIComponent(document.getElementById('item').value);}"
            + "else if(k==='nokey'){url='/api/notify-nokey'+sep+'clientId='+encodeURIComponent(c)+'&orderNo='+encodeURIComponent(document.getElementById('orderNo').value)+'&item='+encodeURIComponent(document.getElementById('item').value);}"
            + "else{url='/api/reviews'+sep+'clientId='+encodeURIComponent(c)+'&reviewId='+encodeURIComponent(document.getElementById('reviewId').value)+'&content='+encodeURIComponent(document.getElementById('content').value);}"
            + "var r=document.getElementById('res');r.textContent='发送中…';"
            + "fetch(url,{method:'POST'}).then(function(x){return x.text()}).then(function(t){"
            + "r.textContent='HTTP '+x.status+'\\n'+t}).catch(function(e){r.textContent='失败: '+e});}"
            + "</script></body></html>";

    @GetMapping(value = "/test-page", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> testPage() {
        return ResponseEntity.ok().body(PAGE);
    }
}
