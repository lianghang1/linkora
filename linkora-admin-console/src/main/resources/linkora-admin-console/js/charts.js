/* ═════════ 图表（Chart.js 封装，仅此一处接触图表） ═════════ */
window.createCharts = function(){
  var trend = null, donut = null;
  var css = getComputedStyle(document.documentElement);
  function v(name){ return css.getPropertyValue(name).trim(); }

  /* 近 7 日发布量：面积折线图 */
  function renderTrend(days){
    var el = document.getElementById("trendChart");
    if (!el) return;
    var labels = days.map(function(d){ return (d.date || "").slice(5); });
    var data = days.map(function(d){ return d.count; });
    var accent = v("--accent") || "#6C7CF0";
    var grad = el.getContext("2d").createLinearGradient(0, 0, 0, 190);
    grad.addColorStop(0, "rgba(108,124,240,.26)");
    grad.addColorStop(1, "rgba(108,124,240,0)");
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
        pointBackgroundColor: "#101828", pointBorderColor: accent, pointBorderWidth: 2
      }]},
      options: {
        responsive: true, maintainAspectRatio: false,
        plugins: {legend: {display: false}},
        scales: {
          x: {grid: {display: false}, ticks: {color: v("--ink-3") || "#5F6B82", font: {size: 10}}},
          y: {beginAtZero: true, grid: {color: "rgba(148,163,184,.07)"}, ticks: {color: v("--ink-3") || "#5F6B82", font: {size: 10}, precision: 0}}
        }
      }
    });
  }

  /* 客户端状态：环形图 */
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
        backgroundColor: [v("--ok") || "#3ECF8E", v("--mut") || "#8B94A8", v("--warn") || "#F0A84C"],
        borderColor: "#101828", borderWidth: 3
      }]},
      options: {
        responsive: true, maintainAspectRatio: false, cutout: "68%",
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
