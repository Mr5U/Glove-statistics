package check

import com.mr5u.glovestatistics.DaySummary
import com.mr5u.glovestatistics.HomeWork
import com.mr5u.glovestatistics.MonthSummary
import com.mr5u.glovestatistics.ReportLayout
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
 *  2. 月度统计（数量 / 收入 / 干活天数）是否与记录一致；
 *  3. 报表里每一列的文字会不会越过列边界（老版本「右边被截断」就是这里出的问题），
 *     以及导出的图是不是「只有家里手套计件明细这一张表」。
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
        summarizeFlow()
        parsing()
        storageRoundTrip()
        reportFits()
        reportHasOnlyDetailTable()
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

        // v0.4.0 及更早版本写下的 4 字段家里记录：字段含义没变，一行都不用改就能读出来
        eq(602, ReportLayout.parseHomeLine("2026-09-23|Z2xvdmU=|0.26|602", "|", decode)?.quantity, "老版本家里记录应原样读出")

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

    // ---------------------------------------------------------------- 2. 统计与金额

    private fun summarizeFlow() {
        val home = listOf(
            HomeWork("2026-09-23", "22公分绿牛", 0.26, 600),
            HomeWork("2026-09-23", "22公分绿牛", 0.26, 1),
            HomeWork("2026-09-23", "22公分绿牛", 0.26, 1),
        )

        val day: DaySummary = summarizeDay(LocalDate.parse("2026-09-23"), home)
        eq(1, day.homeRecords.size, "当天家里记录应合并为 1 条")
        eq(602, day.quantity, "当天手套数量应为 602 双")
        eq(156.52, round2(day.homeIncome), "当天手套收入应为 156.52")
        check(day.worked, "当天有记录就算干活")
        check(!summarizeDay(LocalDate.parse("2026-09-24"), home).worked, "没有记录的日期不算干活")

        val month = summarize(YearMonth.of(2026, 9), home)
        eq(1, month.workedDays, "9 月只有 1 天有记录")
        eq(602, month.quantity, "9 月手套数量应为 602 双")
        eq(156.52, round2(month.homeIncome), "9 月手套收入应为 156.52")

        val tally = month.byGlove()
        eq(1, tally.size, "9 月只有 1 个手套种类")
        eq(602, tally[0].quantity, "种类合计数量应为 602 双")
        eq(156.52, round2(tally[0].income), "种类合计收入应为 156.52")

        // 跨月过滤：8 月的记录不该出现在 9 月
        val august = HomeWork("2026-08-31", "绿牛", 0.26, 10)
        eq(602, summarize(YearMonth.of(2026, 9), home + august).quantity, "9 月汇总不应包含 8 月记录")
        eq(10, summarize(YearMonth.of(2026, 8), home + august).quantity, "8 月汇总应只包含 8 月记录")
        eq(1, summarize(YearMonth.of(2026, 8), home + august).workedDays, "8 月应只有 1 天有记录")
    }

    // ------------------------------------------------- 3. 老数据 / 脏数据的兼容解析

    private fun parsing() {
        // 家里记录：4 字段
        val home = ReportLayout.parseHomeLine("2026-09-23|glove|0.26|602", "|") { it }
        check(home != null, "家里记录应能解析")
        eq(602, home!!.quantity, "家里记录数量应为 602")
        eq(156.52, round2(home.income), "家里记录收入应为 156.52")

        // 字段不足 / 数字非法 / 空日期 → 跳过，而不是抛异常
        check(ReportLayout.parseHomeLine("2026-09-23|glove|0.26", "|") { it } == null, "家里记录字段不足应跳过")
        check(ReportLayout.parseHomeLine("2026-09-23|glove|abc|2", "|") { it } == null, "单价非法应跳过")
        check(ReportLayout.parseHomeLine("2026-09-23|glove|0.26|x", "|") { it } == null, "数量非法应跳过")
        check(ReportLayout.parseHomeLine("|glove|0.26|2", "|") { it } == null, "空日期应跳过")
        check(ReportLayout.parseHomeLine("2026-09-23|glove|0.26|2", "|") { null } == null, "Base64 解码失败应跳过")

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
        val summary = summarize(YearMonth.of(2026, 9), home)
        val report = ReportLayout.plan(summary)

        check(report.blocks.isNotEmpty(), "报表应该至少有一块内容")
        check(report.height > ReportLayout.TITLE_BAR, "报表高度应该大于标题栏")

        report.blocks.forEach { block ->
            val spec = block.table ?: return@forEach
            eq(spec.headers.size, spec.aligns.size, "${spec.title}：对齐配置数量必须与列数一致")

            val bounds = ReportLayout.columns(spec.weights)

            // 列边界必须在内容区内：这一条直接对应「右边被截断」的 bug
            eq(ReportLayout.contentLeft, bounds.first().start, "${spec.title}：第一列应从内容区左侧开始")
            check(
                bounds.last().endInclusive <= ReportLayout.contentRight + 0.01f,
                "${spec.title}：最后一列越过了内容区右边界",
            )
            check(
                bounds.last().endInclusive < ReportLayout.PAGE_WIDTH - 20f,
                "${spec.title}：最后一列太贴近画布边缘，可能被显示端裁掉",
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
                        "${spec.title}：第 ${column + 1} 列文字「$text」放不下",
                    )
                }
            }

            // 合计行每一格也必须落在它声明的列区间内
            spec.footer?.forEach { cell ->
                check(cell.columns.first in 0 until spec.columnCount, "${spec.title}：合计行起始列越界")
                check(cell.columns.last in 0 until spec.columnCount, "${spec.title}：合计行结束列越界")
                val spanStart = bounds[cell.columns.first].start
                val spanEnd = bounds[cell.columns.last].endInclusive
                check(spanEnd > spanStart, "${spec.title}：合计行区间宽度必须为正")
                val width = spanEnd - spanStart - ReportLayout.CELL_INSET * 2
                val fitted = ReportLayout.fit(cell.text, width, measure = { value, size ->
                    ReportLayout.estimateWidth(value, size)
                })
                check(
                    fitted.text.isEmpty() || ReportLayout.estimateWidth(fitted.text, fitted.size) <= width + 0.01f,
                    "${spec.title}：合计行文字「${cell.text}」放不下",
                )
            }
        }

        // 「合计 N 笔」这类文字必须留在最后两列以外，不能顶到图片最右边
        val table = report.blocks.mapNotNull { it.table }.first()
        val footerCount = table.footer!!.first()
        check(footerCount.columns.last <= 1, "「合计 N 笔」应落在左侧列，不能挤到最右列")

        // 超长文字应被截断并加省略号
        val ellipsized = ReportLayout.ellipsize(
            "这是一个非常非常非常长的手套名称",
            maxWidth = 100f,
            measure = { value -> ReportLayout.estimateWidth(value, ReportLayout.FONT_SIZE) },
        )
        check(ellipsized.endsWith("…"), "超长文字应被截断并加省略号")
    }

    /**
     * 图里**只能有「家里手套计件明细」这一张表**：
     * 既没有「各手套种类汇总」，也没有按天的汇总表；空月份也不崩。
     */
    private fun reportHasOnlyDetailTable() {
        val home = listOf(
            HomeWork("2026-09-23", "22公分绿牛", 0.26, 602),
            HomeWork("2026-09-24", "加绒劳保", 1.2, 30),
        )
        val summary = summarize(YearMonth.of(2026, 9), home)
        val blocks = ReportLayout.plan(summary).blocks
        val tables = blocks.mapNotNull { it.table }

        eq(1, tables.size, "导出的图里应该只有一张表")
        check(tables[0].title.startsWith("家里手套计件明细"), "那张表必须是「家里手套计件明细」")
        check(
            tables.none { it.title.contains("各手套种类") },
            "「各手套种类汇总」不该再出现在图里",
        )
        check(
            tables.none { it.title.contains("每日汇总") },
            "「每日汇总」不该再出现在图里",
        )
        eq(2, tables[0].rows.size, "两个不同日期的手套种类应各占一行")

        // 合计行给出笔数、双数与金额
        val footer = tables[0].footer!!
        eq("合计 2 笔", footer[0].text, "合计行第一格应是笔数")
        eq("632 双", footer[1].text, "合计行应给出总双数")
        eq("192.52", footer[3].text, "合计行应给出总金额")

        // 表头不能被误当成「各手套种类」
        check(tables[0].headers.contains("手套种类"), "明细表仍应有「手套种类」列")

        // 空月份：仍然画这张表（写一行说明），不会崩也不会多出别的表
        val empty = summarize(YearMonth.of(2026, 1), emptyList())
        val emptyBlocks = ReportLayout.plan(empty).blocks
        val emptyTables = emptyBlocks.mapNotNull { it.table }
        eq(1, emptyTables.size, "空月份也应只有这一张表")
        check(emptyTables[0].rows.isEmpty(), "空月份的表没有数据行")
        eq("本月没有手套记录", emptyTables[0].emptyText, "空月份应写一行说明")
        check(
            emptyBlocks.any { it.kind == ReportLayout.Block.Kind.FOOTER },
            "空月份仍应有页脚",
        )
    }

    private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0
}
