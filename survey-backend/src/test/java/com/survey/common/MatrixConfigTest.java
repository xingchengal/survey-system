package com.survey.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 矩阵题行列标题的解析/校验（`common/MatrixConfig`）。
 *
 * ★ 为什么值得单独测：矩阵题的 row_key / content 都是**标题原文**，
 *   一旦出现空白、重复或超长标题：
 *     · 重复 → 统计把两行合并，用户看不出来但数据已经错了；
 *     · 空白 → 答题页渲染出一个点不动的行；
 *     · 超长 → 写库时被 MySQL 拒绝，用户看到 500 而不是 400。
 *   这三类都必须在校验阶段挡住。
 */
class MatrixConfigTest {

    @Test
    @DisplayName("null / 空白 → 空列表（老数据与其它题型都走这条）")
    void parse_nullOrBlank_returnsEmpty() {
        assertTrue(MatrixConfig.parse(null, "行标题").isEmpty());
        assertTrue(MatrixConfig.parse("  ", "行标题").isEmpty());
    }

    @Test
    @DisplayName("解析 JSON 数组并保留顺序")
    void parse_keepsOrder() {
        assertEquals(List.of("服务态度", "响应速度"),
                MatrixConfig.parse("[\"服务态度\",\"响应速度\"]", "行标题"));
    }

    @Test
    @DisplayName("损坏的 JSON → 400 文案，而不是让 Jackson 异常变成 500")
    void parse_invalidJson_throwsBizException() {
        BizException ex = assertThrows(BizException.class,
                () -> MatrixConfig.parse("服务态度,响应速度", "矩阵行标题"));
        assertTrue(ex.getMessage().contains("格式不正确"), "实际文案：" + ex.getMessage());
    }

    @Test
    @DisplayName("★ 校验会去掉首尾空白并返回规范化结果")
    void validate_trimsLabels() {
        assertEquals(List.of("A", "B"),
                MatrixConfig.validate("[\" A \",\"B \"]", "矩阵行标题",
                        MatrixConfig.MAX_ROWS, 1));
    }

    @Test
    @DisplayName("重复标题 → 400（统计会按标题合并，必须先拦住）")
    void validate_rejectsDuplicateLabels() {
        BizException ex = assertThrows(BizException.class,
                () -> MatrixConfig.validate("[\"满意\",\"满意\"]", "矩阵列标题",
                        MatrixConfig.MAX_COLS, 2));
        assertTrue(ex.getMessage().contains("不能重复"), "实际文案：" + ex.getMessage());
    }

    @Test
    @DisplayName("空白标题 → 400（答题页会出现点不动的行）")
    void validate_rejectsBlankLabel() {
        BizException ex = assertThrows(BizException.class,
                () -> MatrixConfig.validate("[\"满意\",\"  \"]", "矩阵列标题",
                        MatrixConfig.MAX_COLS, 2));
        assertTrue(ex.getMessage().contains("不能为空"), "实际文案：" + ex.getMessage());
    }

    @Test
    @DisplayName("条数上下限：列至少 2、行最多 20")
    void validate_enforcesCountLimits() {
        BizException tooFew = assertThrows(BizException.class,
                () -> MatrixConfig.validate("[\"只有一列\"]", "矩阵列标题",
                        MatrixConfig.MAX_COLS, 2));
        assertTrue(tooFew.getMessage().contains("至少需要 2 项"), "实际文案：" + tooFew.getMessage());

        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < MatrixConfig.MAX_ROWS + 1; i++) {
            if (i > 0) many.append(',');
            many.append('"').append("行").append(i).append('"');
        }
        many.append(']');
        BizException tooMany = assertThrows(BizException.class,
                () -> MatrixConfig.validate(many.toString(), "矩阵行标题",
                        MatrixConfig.MAX_ROWS, 1));
        assertTrue(tooMany.getMessage().contains("最多"), "实际文案：" + tooMany.getMessage());
    }

    @Test
    @DisplayName("toJson 与 parse 互为逆运算")
    void toJson_roundTrip() {
        List<String> labels = List.of("很满意", "一般");
        assertEquals(labels, MatrixConfig.parse(MatrixConfig.toJson(labels), "列标题"));
    }
}
