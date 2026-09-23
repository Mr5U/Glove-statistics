package check

import com.mr5u.glovestatistics.DaySummary
import com.mr5u.glovestatistics.FactoryMode
import com.mr5u.glovestatistics.FactoryWork
import com.mr5u.glovestatistics.HomeWork
import com.mr5u.glovestatistics.MonthSummary
import com.mr5u.glovestatistics.ReportLayout
import com.mr5u.glovestatistics.ReportScope
import com.mr5u.glovestatistics.mergeFactoryWork
import com.mr5u.glovestatistics.mergeHomeWork
import com.mr5u.glovestatistics.summarize
import com.mr5u.glovestatistics.summarizeDay
import java.time.LocalDate
import java.time.YearMonth

/*
 * 纯 Kotlin 的离线校验：只依赖 Domain.kt + ReportLayout.kt，不需要 Android 运行时。
 * 由 .tooling/domain-check.ps1 编译并运行。
 *
 * 覆盖三件容易出错、又没法靠眼睛看出来的事：
 *  1. 同一天重复记录合并后的数量 / 金额是否正确；
 *  2. 厂房两种模式（计件 / 整笔）算出来的钱对不对；
 *  3. 报表里每一列的文字会不会越过列边界（老版本「右边被截断」就是这里出的问题）。
 */
object DomainCheck {

    private var passed = 0

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError("断言失败：$message")
        passed++
    }

    private fun eq(expected: Any?, actual: Any?, message: String) {
        if (expected != actual) throw AssertionError("断言失败：$message\n  期望=$expected\n  实际=$actual")
        passed++
    }

    @JvmStatic
    fun main(args: Array<String>) {
        mergeHome()
        mergeFactory()
        summarizeFlow()
        legacyParsing()
        storageRoundTrip()
        reportFits()
        println("ALL PASS ($passed 项断言)")
    }

    /**
     * 用**真实的字段编码**（DataStore 写盘时用的就是 [ReportLayout.encodeField]）
     * 走一遍「写出来 → 读回去 → 合并 → 汇总」，模拟升级前后的读写兼容。
     */
    private fun storageRoundTrip() {
        val append = { value: String -> ReportLayout.encodeField(value) }
        val decode = { value: String -> ReportLayout.decodeField(value) }

        // 用户图里的真实数据：同一天 3 笔「22 公分绿牛」，合计 602 双
        val glove = "22 公分绿牛"
        val homeLines = listOf(
            ReportLayout.joinFields(listOf("2026-09-23", append(glove), "0.26", "600"), "|"),
            ReportLayout.joinFields(listOf("2026-09-23", append(glove), "0.26", "1"), "|"),
            ReportLayout.joinFields(listOf("2026-09-23", append(glove), "0.26", "1"), "|"),
        )
        val home = homeLines.mapNotNull { ReportLayout.parseHomeLine(it, "|", decode) }
        eq(3, home.size, "3 行旧的家里记录应全部读出")
        eq(602, mergeHomeWork(home)[0].quantity, "读回来合并后应为 602 双")
        eq(156.52, round2(mergeHomeWork(home)[0].income), "读回来合并后收入应为 156.52")

        // 名字里带分隔符和换行也不能破坏格式（这正是要 Base64 的原因）
        val tricky = "手套|带竖线\n和换行"
        val trickyLine = ReportLayout.joinFields(listOf("2026-09-23", append(tricky), "1.5", "2"), "|")
        eq(4, trickyLine.split("|").size, "经过编码后，行内分隔符数量必须仍然正确")
        val trickyParsed = ReportLayout.parseHomeLine(trickyLine, "|", decode)
        eq(tricky, trickyParsed?.gloveName, "含特殊字符的手套名应原样还原")

        // v0.3.0 的厂房行（3 字段，老版本写出来的）
        val oldFactoryLine = ReportLayout.joinFields(listOf("2026-09-23", "10.0", append("打杂")), "|")
        val oldRecord = ReportLayout.parseFactoryLine(oldFactoryLine, "|", decode)
        check(oldRecord != null, "v0.3.0 的厂房行应该能读出来")
        eq(10.0, oldRecord!!.amount, "v0.3.0 厂房行的金额应原样读出")
        eq("打杂", oldRecord.note, "v0.3.0 厂房行的备注应原样还原")
        eq(FactoryMode.FLAT, oldRecord.mode, "v0.3.0 厂房行应还原成整笔模式")

        // v0.4.0 新写的厂房计件行
        val newFactoryLine = ReportLayout.joinFields(
            listOf("2026-09-23", "${0.26 * 600}", append(""), append(glove), "600", "0.26", FactoryMode.PIECE.key),
            "|",
        )
        val newRecord = ReportLayout.parseFactoryLine(newFactoryLine, "|", decode)
        check(newRecord != null, "v0.4.0 的厂房计件行应该能读出来")
        eq(600, newRecord!!.quantity, "厂房计件行数量应为 600")
        eq(0.26, newRecord.unitPrice, "厂房计件行单价应为 0.26")
        eq(156.0, round2(newRecord.amount), "厂房计件行金额应为 156.00")
        eq(FactoryMode.PIECE, newRecord.mode, "厂房计件行模式应为计件")

        // 老版本 App 读到新格式的行时，前三个字段含义不变 → 金额不会算错
        val asOldVersionSeesIt = newFactoryLine.split("|")
        eq("2026-09-23", asOldVersionSeesIt[0], "向下兼容：日期仍在第 1 个字段")
        eq(156.0, round2(asOldVersionSeesIt[1].toDouble()), "向下兼容：金额仍在第 2 个字段")
        eq("", decode(asOldVersionSeesIt[2]), "向下兼容：备注仍在第 3 个字段")

        // Base64 编解码自身也要对得上
        eq("abc", ReportLayout.decodeField(ReportLayout.encodeField("abc")), "字段编解码应可逆")
        check(ReportLayout.decodeField("!!!not-base64!!!") == null, "非法 Base64 应返回 null 而不是抛异常")
    }

    // ---------------------------------------------------------------- 1. 同日合并

    private fun mergeHome() {
        val records = listOf(
            HomeWork("2026-09-23", "22公分绿牛", 0.26, 600),
            HomeWork("2026-09-23", "22公分绿牛", 0.26, 1),
            HomeWork("2026-09-23", "22公分绿牛", 0.26, 1),
        )
        val merged = mergeHomeWork(records)
        eq(1, merged.size, "同一天同种类同单价应合并成 1 条")
        eq(602, merged[0].quantity, "合并后数量应为 602 双")
        eq(156.52, round2(merged[0].income), "合并后收入应为 156.52")

        // 同种类但当天单价不同：不能硬合并，否则钱会算错。
        val mixed = mergeHomeWork(
            listOf(
                HomeWork("2026-09-23", "绿牛", 0.26, 100),
                HomeWork("2026-09-23", "绿牛", 0.30, 100),
            ),
        )
        eq(2, mixed.size, "同种类不同单价应保留 2 条")
        eq(56.0, round2(mixed.sumOf { it.income }), "不同单价两条的收入合计应为 56.00")

        // 不同日期不合并
        eq(
            2,
            mergeHomeWork(
                listOf(
                    HomeWork("2026-09-23", "绿牛", 0.26, 10),
                    HomeWork("2026-09-24", "绿牛", 0.26, 10),
                ),
            ).size,
            "不同日期不应合并",
        )
    }

    private fun mergeFactory() {
        // 计件：同一天同种类同单价 → 合并数量
        val pieces = mergeFactoryWork(
            listOf(
                FactoryWork("2026-09-23", 156.0, "", "22公分绿牛", 600, 0.26, FactoryMode.PIECE),
                FactoryWork("2026-09-23", 0.26, "", "22公分绿牛", 1, 0.26, FactoryMode.PIECE),
            ),
        )
        eq(1, pieces.size, "同一天同种类的厂房计件应合并成 1 条")
        eq(601, pieces[0].quantity, "厂房计件合并后数量应为 601 双")

        // 整笔：同金额同备注合并，金额相加
        val flats = mergeFactoryWork(
            listOf(
                FactoryWork("2026-09-23", 10.0, "打杂", "", 0, 0.0, FactoryMode.FLAT),
                FactoryWork("2026-09-23", 10.0, "打杂", "", 0, 0.0, FactoryMode.FLAT),
            ),
        )
        eq(1, flats.size, "同金额同备注的整笔记录应合并")
        eq(20.0, flats[0].amount, "整笔合并后金额应为 20.00")

        // 备注不同 → 不合并（通常是两件不同的活）
        eq(
            2,
            mergeFactoryWork(
                listOf(
                    FactoryWork("2026-09-23", 10.0, "打杂", "", 0, 0.0, FactoryMode.FLAT),
                    FactoryWork("2026-09-23", 10.0, "搬货", "", 0, 0.0, FactoryMode.FLAT),
                ),
            ).size,
            "备注不同的整笔记录不应合并",
        )
    }

    // ---------------------------------------------------------------- 2. 统计与金额

    private fun summarizeFlow() {
        val home = listOf(
            HomeWork("2026-09-23", "22公分绿牛", 0.26, 600),
            HomeWork("2026-09-23", "22公分绿牛", 0.26, 1),
            HomeWork("2026-09-23", "22公分绿牛", 0.26, 1),
        )
        val factory = listOf(
            FactoryWork("2026-09-23", 15.6, "", "22公分绿牛", 60, 0.26, FactoryMode.PIECE),
            FactoryWork("2026-09-23", 10.0, "打杂", "", 0, 0.0, FactoryMode.FLAT),
        )

        val day: DaySummary = summarizeDay(LocalDate.parse("2026-09-23"), home, factory)
        eq(1, day.homeRecords.size, "当天家里记录应合并为 1 条")
        eq(602, day.quantity, "当天手套数量应为 602 双")
        eq(156.52, round2(day.homeIncome), "当天手套收入应为 156.52")
        eq(25.6, round2(day.factoryIncome), "当天厂房收入应为 25.60（计件 15.60 + 整笔 10.00）")
        eq(182.12, round2(day.totalIncome), "当天全部劳动收入应为 182.12")

        val month = summarize(YearMonth.of(2026, 9), home, factory)
        eq(1, month.workedDays, "9 月只有 1 天有记录")
        eq(602, month.quantity, "9 月手套数量应为 602 双")
        eq(1, month.factoryDays, "9 月厂房天数应为 1 天")
        eq(60, month.factoryQuantity, "9 月厂房计件数量应为 60 双")
        eq(156.52, round2(month.homeIncome), "9 月手套收入应为 156.52")

        val tally = month.byGlove()
        eq(1, tally.size, "9 月只有 1 个手套种类")
        eq(602, tally[0].quantity, "种类合计数量应为 602 双")
        eq(156.52, round2(tally[0].income), "种类合计收入应为 156.52")

        // 计件模式下「收入 = 单价 × 数量」必须自洽
        val piece = FactoryWork("2026-09-23", 0.26 * 600, "", "22公分绿牛", 600, 0.26, FactoryMode.PIECE)
        eq(156.0, round2(piece.amount), "厂房计件金额应等于单价 × 数量")

        // 跨月过滤：8 月的记录不该出现在 9 月
        val august = HomeWork("2026-08-31", "绿牛", 0.26, 10)
        eq(602, summarize(YearMonth.of(2026, 9), home + august, factory).quantity, "9 月汇总不应包含 8 月记录")
        eq(10, summarize(YearMonth.of(2026, 8), home + august, factory).quantity, "8 月汇总应只包含 8 月记录")
    }

    // ------------------------------------------------- 3. 老数据 / 老备份的兼容解析

    private fun legacyParsing() {
        // v0.3.0 写出来的 3 字段厂房行：必须还能读出来，金额不能变
        val legacy = ReportLayout.parseFactoryLine("2026-09-23|10.00|5q2V5omL", "|") { raw ->
            // 这里不做真正的 Base64，只验证「解码失败就跳过」的分支不影响金额解析
            if (raw == "5q2V5omL") "打杂" else null
        }
        check(legacy != null, "老格式（3 字段）厂房行应该能解析")
        eq(10.0, legacy!!.amount, "老格式厂房行的金额应原样读出")
        eq(FactoryMode.FLAT, legacy.mode, "老格式厂房行应还原成整笔模式")
        eq("打杂", legacy.note, "老格式厂房行的备注应原样读出")
        eq(0, legacy.quantity, "老格式厂房行没有数量")

        // v0.4.0 的 7 字段行
        val current = ReportLayout.parseFactoryLine("2026-09-23|156.0|note|glove|600|0.26|piece", "|") { it }
        check(current != null, "新格式（7 字段）厂房行应该能解析")
        eq(600, current!!.quantity, "新格式厂房行数量应为 600")
        eq(0.26, current.unitPrice, "新格式厂房行单价应为 0.26")
        eq(FactoryMode.PIECE, current.mode, "新格式厂房行模式应为计件")

        // 模式字段缺失 / 非法 → 退回整笔，保证金额安全
        eq(
            FactoryMode.FLAT,
            ReportLayout.parseFactoryLine("2026-09-23|1|n||0|0|", "|") { it }!!.mode,
            "模式字段为空应退回整笔",
        )
        eq(
            FactoryMode.FLAT,
            ReportLayout.parseFactoryLine("2026-09-23|1|n||0|0|weird", "|") { it }!!.mode,
            "模式字段非法应退回整笔",
        )

        // 字段太少的垃圾行应被跳过，而不是抛异常
        check(ReportLayout.parseFactoryLine("2026-09-23|10", "|") { it } == null, "字段不足的厂房行应跳过")
        check(ReportLayout.parseFactoryLine("", "|") { it } == null, "空行应跳过")

        // 家里记录：4 字段
        val home = ReportLayout.parseHomeLine("2026-09-23|glove|0.26|602", "|") { it }
        check(home != null, "家里记录应能解析")
        eq(602, home!!.quantity, "家里记录数量应为 602")
        eq(156.52, round2(home.income), "家里记录收入应为 156.52")
        check(ReportLayout.parseHomeLine("2026-09-23|glove|0.26", "|") { it } == null, "家里记录字段不足应跳过")

        check(ReportLayout.isIsoDate("2026-09-23"), "ISO 日期应被接受")
        check(!ReportLayout.isIsoDate("2026/09/23"), "非 ISO 日期应被拒绝")
    }

    // ---------------------------------------------------------------- 4. 报表排版

    /**
     * 这一组就是「导出图片右边被切掉」的回归测试：
     * 表格的每一格都必须落在内容区内，而且文字在按字号缩排 / 截断之后，
     * 一定放得进那一列的可用宽度里。
     */
    private fun reportFits() {
        val home = listOf(
            HomeWork("2026-09-23", "22公分绿牛超长的手套名称测试用", 0.26, 602),
            HomeWork("2026-09-24", "短名", 1.0, 123456),
        )
        val factory = listOf(
            FactoryWork("2026-09-23", 156.52, "备注也写得很长很长很长很长很长", "22公分绿牛", 602, 0.26, FactoryMode.PIECE),
            FactoryWork("2026-09-23", 10.0, "打杂", "", 0, 0.0, FactoryMode.FLAT),
        )
        val summary = summarize(YearMonth.of(2026, 9), home, factory)

        ReportScope.entries.forEach { scope ->
            val report = ReportLayout.plan(summary, scope)
            check(report.blocks.isNotEmpty(), "$scope 报表应该至少有一块内容")
            check(report.height > ReportLayout.TITLE_BAR, "$scope 报表高度应该大于标题栏")

            report.blocks.forEach { block ->
                val spec = block.table ?: return@forEach
                eq(
                    spec.headers.size,
                    spec.aligns.size,
                    "$scope / ${spec.title}：对齐配置数量必须与列数一致",
                )

                val bounds = ReportLayout.columns(spec.weights)

                // 列边界必须在内容区内：这一条直接对应「右边被截断」的 bug
                eq(ReportLayout.contentLeft, bounds.first().start, "$scope / ${spec.title}：第一列应从内容区左侧开始")
                check(
                    bounds.last().endInclusive <= ReportLayout.contentRight + 0.01f,
                    "$scope / ${spec.title}：最后一列越过了内容区右边界",
                )
                check(
                    bounds.last().endInclusive < ReportLayout.PAGE_WIDTH - 20f,
                    "$scope / ${spec.title}：最后一列太贴近画布边缘，可能被显示端裁掉",
                )

                // 每一格的文字必须放得进那一列（先缩字号、再截断的兜底逻辑要真的生效）
                (spec.rows + listOf(spec.headers)).forEach { row ->
                    row.forEachIndexed { column, text ->
                        if (text.isEmpty()) return@forEachIndexed
                        val width = bounds[column].endInclusive - bounds[column].start - ReportLayout.CELL_INSET * 2
                        val fitted = ReportLayout.fit(text, width, measure = { value, size ->
                            ReportLayout.estimateWidth(value, size)
                        })
                        check(
                            fitted.text.isEmpty() || ReportLayout.estimateWidth(fitted.text, fitted.size) <= width + 0.01f,
                            "$scope / ${spec.title}：第 ${column + 1} 列文字「$text」放不下",
                        )
                    }
                }

                // 合计行每一格也必须落在它声明的列区间内
                spec.footer?.forEach { cell ->
                    check(cell.columns.first in 0 until spec.columnCount, "$scope / ${spec.title}：合计行起始列越界")
                    check(cell.columns.last in 0 until spec.columnCount, "$scope / ${spec.title}：合计行结束列越界")
                    val spanStart = bounds[cell.columns.first].start
                    val spanEnd = bounds[cell.columns.last].endInclusive
                    check(spanEnd > spanStart, "$scope / ${spec.title}：合计行区间宽度必须为正")
                    val width = spanEnd - spanStart - ReportLayout.CELL_INSET * 2
                    val fitted = ReportLayout.fit(cell.text, width, measure = { value, size ->
                        ReportLayout.estimateWidth(value, size)
                    })
                    check(
                        fitted.text.isEmpty() || ReportLayout.estimateWidth(fitted.text, fitted.size) <= width + 0.01f,
                        "$scope / ${spec.title}：合计行文字「${cell.text}」放不下",
                    )
                }
            }
        }

        // 「合计 N 笔」这类文字必须留在最后一列以外，不能顶到图片最右边
        val all = ReportLayout.plan(summary, ReportScope.ALL)
        val homeTable = all.blocks.mapNotNull { it.table }.first { it.title.startsWith("家里手套") }
        val bounds = ReportLayout.columns(homeTable.weights)
        val footerCount = homeTable.footer!!.first()
        check(footerCount.columns.last <= 1, "「合计 N 笔」应落在左侧列，不能挤到最右列")

        // 超长文字应被截断并加省略号
        val ellipsized = ReportLayout.ellipsize(
            "这是一个非常非常非常长的手套名称",
            maxWidth = 100f,
            measure = { value -> ReportLayout.estimateWidth(value, ReportLayout.FONT_SIZE) },
        )
        check(ellipsized.endsWith("…"), "超长文字应被截断并加省略号")

        // 空报表也不该崩，而且不该画空表：没记录时只有汇总与页脚
        val empty = summarize(YearMonth.of(2026, 1), emptyList(), emptyList())
        val emptyReport = ReportLayout.plan(empty, ReportScope.ALL)
        check(emptyReport.blocks.isNotEmpty(), "空月份也应有汇总与页脚")
        check(emptyReport.blocks.none { it.table != null }, "空月份不应画出空表格")
        check(
            emptyReport.blocks.any { it.kind == ReportLayout.Block.Kind.TOTALS },
            "空月份仍应有收入汇总区",
        )
    }

    private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0
}
