/* ═════════ 图表（Chart.js 封装，仅此一处接触图表；页面切换销毁） ═════════ */
window.createCharts = function(){
  var trend = null, donut = null;
  function v(name){
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  }

  /* 近 7 日发布量（真实日级数据，不伪装小时级曲线） */
  function renderTrend(days){
    var el = document.getElementById("trendChart");
    if (!el) return;
    var labels = days.map(function(d){ return (d.date || "").slice(5); });
    var data = days.map(function(d){ return d.count; });
    var accent = v("--accent") || "#6C8CFF";
    var grad = el.getContext("2d").createLinearGradient(0, 0, 0, 190);
    grad.addColorStop(0, "rgba(108,140,255,.22)");
    grad.addColorStop(1, "rgba(108,140,255,0)");
    if (trend){
      trend.data.labels = labels; trend.data.datasets[0].data = data; trend.update();
      return;
    }
    trend = new Chart(el, {
      type: "line",
      data: {labels: labels, datasets: [{
        label: "每日发布量", data: data,
        borderColor: accent, backgroundColor: grad, fill: true,
        tension: .35, borderWidth: 2, pointRadius: 3,
        pointBackgroundColor: "#11151D", pointBorderColor: accent, pointBorderWidth: 2
      }]},
      options: {
        responsive: true, maintainAspectRatio: false,
        animation: {duration: 160},
        plugins: {legend: {display: false}},
        scales: {
          x: {grid: {display: false}, ticks: {color: v("--text-muted") || "#626D7D", font: {size: 10}}},
          y: {beginAtZero: true, grid: {color: "rgba(148,163,184,.07)"}, ticks: {color: v("--text-muted") || "#626D7D", font: {size: 10}, precision: 0}}
        }
      }
    });
  }

  /* 客户端状态环形图（真实概览统计） */
  function renderDonut(clients){
    var el = document.getElementById("donutChart");
    if (!el) return;
    var online = 0, offline = 0, disabled = 0;
    clients.forEach(function(c){
      if (!c.enabled) disabled++;
      else if (c.online) online++;
      else offline++;
    });
    var data = [online, offline, disabled];
    if (donut){
      donut.data.datasets[0].data = data; donut.update();
      return;
    }
    donut = new Chart(el, {
      type: "doughnut",
      data: {labels: ["在线", "离线", "已禁用"], datasets: [{
        data: data,
        backgroundColor: [v("--success") || "#43C99A", v("--text-secondary") || "#929BAA", v("--warning") || "#F0B45A"],
        borderColor: "#11151D", borderWidth: 3
      }]},
      options: {
        responsive: true, maintainAspectRatio: false, cutout: "68%",
        animation: {duration: 160},
        plugins: {legend: {display: false}}
      }
    });
  }

  function destroyAll(){
    if (trend){ trend.destroy(); trend = null; }
    if (donut){ donut.destroy(); donut = null; }
  }

  return {renderTrend: renderTrend, renderDonut: renderDonut, destroyAll: destroyAll};
};
