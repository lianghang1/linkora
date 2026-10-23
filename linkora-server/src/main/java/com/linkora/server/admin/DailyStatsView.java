package com.linkora.server.admin;

import java.util.List;
import java.util.Map;

/**
 * 运营统计的管理视图（人类可读）：每日发布量 + 投递状态分布。
 *
 * @author lianghang
 */
public final class DailyStatsView {

    /** 单日发布量。 */
    public static final class DayPublish {
        private final String date;
        private final long count;

        public DayPublish(String date, long count) {
            this.date = date;
            this.count = count;
        }

        /** 本地日期 yyyy-MM-dd。 */
        public String getDate() {
            return date;
        }

        public long getCount() {
            return count;
        }
    }

    private final List<DayPublish> publishedPerDay;
    /** 全部投递记录按状态的分布（key = 状态中文名）。 */
    private final Map<String, Long> deliveryStateDistribution;

    public DailyStatsView(List<DayPublish> publishedPerDay, Map<String, Long> deliveryStateDistribution) {
        this.publishedPerDay = publishedPerDay;
        this.deliveryStateDistribution = deliveryStateDistribution;
    }

    /** 最近 N 天每日发布量（升序）。 */
    public List<DayPublish> getPublishedPerDay() {
        return publishedPerDay;
    }

    /** 全部投递记录按状态的分布（中文状态名 → 数量）。 */
    public Map<String, Long> getDeliveryStateDistribution() {
        return deliveryStateDistribution;
    }

    @Override
    public String toString() {
        return "DailyStatsView{publishedPerDay=" + publishedPerDay
                + ", stateDistribution=" + deliveryStateDistribution + "}";
    }
}
