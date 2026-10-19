package com.survey.service;

import com.survey.common.Permission;
import com.survey.entity.Survey;
import com.survey.mapper.AnswerMapper;
import com.survey.mapper.ErrorLogMapper;
import com.survey.mapper.OperationLogMapper;
import com.survey.mapper.SurveyMapper;
import com.survey.mapper.SurveyReviewMapper;
import com.survey.mapper.SystemResourceMapper;
import com.survey.mapper.TemplateCategoryMapper;
import com.survey.mapper.UserMapper;
import com.survey.mapper.WalletMapper;
import com.survey.mapper.WalletTransactionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 系统统计（管理端人员）：按模块返回数据，前端按返回的模块渲染 Tab。
 *
 * ★ 每个模块只看自己的权限点：`survey:manage` / `user:manage` / `template:manage` /
 *   `review:handle` / `wallet:manage` / `log:view`；`system:view` **只解锁资源模块**
 *   （数据库大小 / 表行数 / 版本快照占用），不再等于全站业务数据。
 * ★ 所有计数都是单条聚合 SQL（或一次批量 GROUP BY），不按问卷/用户循环查库。
 * ★ 资源维度只读 information_schema 与 COUNT(*)，不写任何数据。
 */
@Service
public class SystemStatisticsService {

    private static final int TREND_DAYS = 7;
    private static final int TOP_LIMIT = 5;

    @Autowired
    private SurveyService surveyService;
    @Autowired
    private SurveyMapper surveyMapper;
    @Autowired
    private AnswerMapper answerMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private SurveyReviewMapper surveyReviewMapper;
    @Autowired
    private WalletMapper walletMapper;
    @Autowired
    private WalletTransactionMapper walletTransactionMapper;
    @Autowired
    private OperationLogMapper operationLogMapper;
    @Autowired
    private ErrorLogMapper errorLogMapper;
    @Autowired
    private TemplateCategoryMapper templateCategoryMapper;
    @Autowired
    private SystemResourceMapper systemResourceMapper;

    /**
     * 按权限组装模块数据。
     *
     * @param permissions 当前用户的权限点；null 视为无权限（防御性，正常调用方都会传）
     */
    public Map<String, Object> getStatistics(Set<String> permissions) {
        Set<String> perms = permissions == null ? Set.of() : permissions;

        Map<String, Object> result = new LinkedHashMap<>();
        if (perms.contains(Permission.SURVEY_MANAGE)) {
            result.put("survey", surveyStats());
        }
        if (perms.contains(Permission.USER_MANAGE)) {
            result.put("user", userStats());
        }
        if (perms.contains(Permission.TEMPLATE_MANAGE)) {
            result.put("template", templateStats());
        }
        if (perms.contains(Permission.REVIEW_HANDLE)) {
            result.put("review", reviewStats());
        }
        if (perms.contains(Permission.WALLET_MANAGE)) {
            result.put("wallet", walletStats());
        }
        if (perms.contains(Permission.LOG_VIEW)) {
            result.put("log", logStats());
        }
        // 资源维度是系统级信息：只认 system:view，给了它就只给这一块，不给业务数据
        if (perms.contains(Permission.SYSTEM_VIEW)) {
            result.put("resource", resourceStats());
        }
        return result;
    }

    // ==================== 问卷与答卷 ====================

    private Map<String, Object> surveyStats() {
        List<Survey> normal = surveyService.listNormal();
        SurveyService.StatusCounts c = surveyService.countByStatus(normal);
        Map<Integer, Integer> counts = surveyService.countResponsesBySurveyIds(normal);

        int responses = 0;
        for (Survey s : normal) {
            responses += counts.getOrDefault(s.getId(), 0);
        }

        List<String> dates = last7Dates();

        // 新建问卷趋势：问卷已一次取回，直接在内存按日期分组，不再多查一条 SQL
        Map<String, Integer> created = new HashMap<>();
        for (Survey s : normal) {
            if (s.getCreatedTime() != null) {
                created.merge(s.getCreatedTime().toLocalDate().toString(), 1, Integer::sum);
            }
        }
        List<Integer> trendSurveys = new ArrayList<>(dates.size());
        for (String d : dates) {
            trendSurveys.add(created.getOrDefault(d, 0));
        }
        List<Integer> trendAnswers = fillDaily(answerMapper.countDailyAll(), dates);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", c.total());
        result.put("published", c.published());
        result.put("draft", c.draft());
        result.put("ended", c.ended());
        result.put("responses", responses);
        result.put("views", surveyMapper.sumViewCount());
        result.put("recycled", surveyMapper.countRecycled());
        result.put("todaySurveys", surveyMapper.countCreatedToday());
        result.put("todayAnswers", answerMapper.countToday());
        // 模板数只用于「模板 / 普通」构成图，是聚合数字，不暴露模板内容
        result.put("templates", surveyMapper.countTemplates());
        result.put("avgResponses", c.published() == 0 ? 0 : Math.round(responses * 10.0 / c.published()) / 10.0);
        result.put("responseRate", c.published() == 0 ? 0
                : Math.min(100, Math.round(responses * 100.0 / (c.published() * 10))));
        result.put("trendDates", dates);
        result.put("trendSurveys", trendSurveys);
        result.put("trendAnswers", trendAnswers);
        result.put("topSurveys", topSurveys(normal, counts));
        result.put("responseBuckets", responseBuckets(normal, counts));
        return result;
    }

    private List<Map<String, Object>> topSurveys(List<Survey> normal, Map<Integer, Integer> counts) {
        List<Survey> ranked = new ArrayList<>(normal);
        ranked.sort((a, b) -> {
            int diff = counts.getOrDefault(b.getId(), 0) - counts.getOrDefault(a.getId(), 0);
            return diff != 0 ? diff : Integer.compare(a.getId(), b.getId());
        });
        if (ranked.size() > TOP_LIMIT) {
            ranked = ranked.subList(0, TOP_LIMIT);
        }
        List<Map<String, Object>> rows = new ArrayList<>(ranked.size());
        for (Survey s : ranked) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", s.getId());
            item.put("title", s.getTitle());
            item.put("status", s.getStatus());
            item.put("responses", counts.getOrDefault(s.getId(), 0));
            rows.add(item);
        }
        return rows;
    }

    /** 答卷量分桶：口径与「我的问卷」同一条（0 / 1-5 / 6-20 / 21-50 / 50+）。 */
    private List<Map<String, Object>> responseBuckets(List<Survey> normal, Map<Integer, Integer> counts) {
        String[] labels = {"0 份", "1-5 份", "6-20 份", "21-50 份", "50+ 份"};
        int[] buckets = new int[labels.length];
        for (Survey s : normal) {
            int n = counts.getOrDefault(s.getId(), 0);
            int idx = n == 0 ? 0 : n <= 5 ? 1 : n <= 20 ? 2 : n <= 50 ? 3 : 4;
            buckets[idx]++;
        }
        List<Map<String, Object>> rows = new ArrayList<>(labels.length);
        for (int i = 0; i < labels.length; i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("label", labels[i]);
            item.put("count", buckets[i]);
            rows.add(item);
        }
        return rows;
    }

    // ==================== 用户 ====================

    private Map<String, Object> userStats() {
        int normal = userMapper.countNormalUsers();
        int admins = userMapper.countAdmins();
        List<String> dates = last7Dates();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", normal + admins);
        result.put("normal", normal);
        result.put("admins", admins);
        result.put("activeAdmins", userMapper.countActiveAdmins());
        result.put("disabled", userMapper.countDisabled());
        result.put("todayNew", userMapper.countCreatedToday());
        result.put("trendDates", dates);
        result.put("trendNew", fillDaily(userMapper.countDailyCreated(), dates));
        return result;
    }

    // ==================== 模板 ====================

    private Map<String, Object> templateStats() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", surveyMapper.countTemplates());
        result.put("categories", templateCategoryMapper.countAll());
        result.put("topTemplates", surveyMapper.findTopTemplates(TOP_LIMIT));
        return result;
    }

    // ==================== 举报 ====================

    private Map<String, Object> reviewStats() {
        int pending = 0;
        int approved = 0;
        int rejected = 0;
        for (Map<String, Object> row : surveyReviewMapper.countByStatus()) {
            Object status = row.get("status");
            Object cnt = row.get("cnt");
            if (status == null || !(cnt instanceof Number n)) {
                continue;
            }
            switch (status.toString()) {
                case "PENDING" -> pending = n.intValue();
                case "APPROVED" -> approved = n.intValue();
                case "REJECTED" -> rejected = n.intValue();
                default -> {
                    // 未知状态不计入任何一类，避免把脏数据算成"已处理"
                }
            }
        }
        int total = pending + approved + rejected;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pending", pending);
        result.put("approved", approved);
        result.put("rejected", rejected);
        result.put("total", total);
        result.put("handledRate", total == 0 ? 0 : Math.round((approved + rejected) * 100.0 / total));
        return result;
    }

    // ==================== 钱包 ====================

    private Map<String, Object> walletStats() {
        Map<String, Object> today = walletTransactionMapper.sumToday();
        List<Map<String, Object>> byType = walletTransactionMapper.sumByType();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("balance", walletMapper.sumBalance());
        result.put("wallets", walletMapper.countWallets());
        result.put("mismatches", walletMapper.countMismatches());
        result.put("todayIncome", today == null ? 0 : toLong(today.get("income")));
        result.put("todayExpense", today == null ? 0 : toLong(today.get("expense")));
        result.put("todayCount", today == null ? 0 : toLong(today.get("cnt")));
        result.put("byType", byType);
        return result;
    }

    // ==================== 日志 ====================

    private Map<String, Object> logStats() {
        List<String> dates = last7Dates();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operations", operationLogMapper.count());
        result.put("todayOperations", operationLogMapper.countToday());
        result.put("errors", errorLogMapper.count());
        result.put("todayErrors", errorLogMapper.countToday());
        result.put("trendDates", dates);
        result.put("trendOperations", fillDaily(operationLogMapper.countDaily(), dates));
        return result;
    }

    // ==================== 资源 ====================

    private Map<String, Object> resourceStats() {
        List<Map<String, Object>> tables = systemResourceMapper.countTableRows();
        long totalRows = 0;
        for (Map<String, Object> row : tables) {
            totalRows += toLong(row.get("rowCount"));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dbBytes", systemResourceMapper.sumDatabaseBytes());
        result.put("tableCount", systemResourceMapper.countTables());
        result.put("snapshotBytes", systemResourceMapper.sumSnapshotBytes());
        result.put("totalRows", totalRows);
        result.put("tables", tables);
        return result;
    }

    // ==================== 工具 ====================

    /** 最近 7 天（含今天）的 yyyy-MM-dd，按时间正序。 */
    private static List<String> last7Dates() {
        List<String> dates = new ArrayList<>(TREND_DAYS);
        LocalDate today = LocalDate.now();
        for (int i = TREND_DAYS - 1; i >= 0; i--) {
            dates.add(today.minusDays(i).toString());
        }
        return dates;
    }

    /** 把 { d, cnt } 行补齐成与 dates 等长的计数列表；没有数据的日子补 0。 */
    private static List<Integer> fillDaily(List<Map<String, Object>> rows, List<String> dates) {
        Map<String, Integer> map = new HashMap<>();
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                Object d = row.get("d");
                Object cnt = row.get("cnt");
                if (d != null && cnt instanceof Number n) {
                    map.put(d.toString(), n.intValue());
                }
            }
        }
        List<Integer> counts = new ArrayList<>(dates.size());
        for (String d : dates) {
            counts.add(map.getOrDefault(d, 0));
        }
        return counts;
    }

    private static long toLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
