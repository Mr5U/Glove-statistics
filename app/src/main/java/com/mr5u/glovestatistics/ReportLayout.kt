package com.mr5u.glovestatistics

import org.w3c.dom.Element
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * 报表排版计算：**纯 Kotlin，不依赖 Android 的 Canvas / Paint**。
 *
 * 之所以把它从 [PngReport] 里拆出来，是因为「导出图片右边被截断」这类问题
 * 本质上是坐标算错了，而坐标计算完全可以用单元测试覆盖。
 * [PngReport] 只负责拿这里的坐标去画，不再自己算位置。
 *
 * 单位统一是像素：画布宽度固定 [PAGE_WIDTH]，左内边距 [PAD_LEFT]、右内边距 [PAD_RIGHT]，
 * 表格内容的右边界因此比画布窄 [PAD_RIGHT]，文字永远不会贴到图片边缘。
 */
object ReportLayout {

    /** 画布宽度（也是表头色条能画到的最宽处）。 */
    const val PAGE_WIDTH = 1080f

    /** 左内边距。 */
    const val PAD_LEFT = 44f

    /**
     * 右内边距。刻意比左边大：老版本这里是 40，导致最后一列的文字紧贴图片边缘，
     * 在聊天软件里预览时看起来就像被切掉了。
     */
    const val PAD_RIGHT = 64f

    /** 文字与单元格边界之间再留的呼吸空间（左右各一半）。 */
    const val CELL_INSET = 14f

    /** 表格左侧内容的起点。 */
    val contentLeft: Float get() = PAD_LEFT

    /** 表格右侧内容的终点。 */
    val contentRight: Float get() = PAGE_WIDTH - PAD_RIGHT

    const val ROW_HEIGHT = 58f
    const val HEADER_HEIGHT = 66f
    const val TITLE_BAR = 132f
    const val DIVIDER = 16f

    /** 正文与表头默认字号；放不下时会按 [MIN_FONT] 逐档缩小。 */
    const val FONT_SIZE = 26f
    const val MIN_FONT = 20f

    /** 缩字号时每次减小的步长。 */
    private const val FONT_STEP = 2f

    /** 文本落在第几列。 */
    enum class Align { LEFT, RIGHT }

    /** 合计行里的一格：可以横跨多列，并且指定落在哪一列的文字区间上。 */
    data class FooterCell(
        val text: String,
        val columns: IntRange,
        val align: Align,
    )

    /** 一张表的完整排版规格。 */
    data class TableSpec(
        val title: String,
        val headers: List<String>,
        val weights: List<Float>,
        val aligns: List<Align>,
        val rows: List<List<String>>,
        val footer: List<FooterCell>? = null,
        val emptyText: String = "本月无记录",
    ) {
        val columnCount: Int get() = headers.size
    }

    /** 一格里放得下的最终字号。 */
    data class Fitted(val text: String, val size: Float)

    /**
     * 把一行文字按可用宽度缩排：先逐档缩小字号，缩到 [MIN_FONT] 还放不下就截断加省略号。
     *
     * [measure] 只需要能测量某个字号下的文字宽度，测试里可以换成估算函数。
     */
    fun fit(
        text: String,
        maxWidth: Float,
        size: Float = FONT_SIZE,
        minSize: Float = MIN_FONT,
        measure: (String, Float) -> Float,
    ): Fitted {
        if (maxWidth <= 0f) return Fitted("", minSize)
        if (measure(text, size) <= maxWidth) return Fitted(text, size)

        val steps = ((size - minSize) / FONT_STEP).toInt()
        for (step in 1..steps) {
            val candidate = (size - FONT_STEP * step).coerceAtLeast(minSize)
            if (measure(text, candidate) <= maxWidth) return Fitted(text, candidate)
            if (candidate <= minSize) break
        }
        return Fitted(ellipsize(text, maxWidth) { candidate -> measure(candidate, minSize) }, minSize)
    }

    /** 截断到放得下为止，末尾补省略号。 */
    fun ellipsize(text: String, maxWidth: Float, measure: (String) -> Float): String {
        if (measure(text) <= maxWidth) return text
        var end = text.length
        while (end > 1) {
            val candidate = text.take(end - 1) + "…"
            if (measure(candidate) <= maxWidth) return candidate
            end--
        }
        return "…"
    }

    /**
     * 粗略估算文字宽度，只用于「没有真实字体引擎」的测试与离线校验。
     *
     * 中日韩字符按 1.0 倍字号算，其余（数字、字母、标点）按 0.55 倍算，
     * 对「会不会越界」这种判断足够保守。
     */
    fun estimateWidth(text: String, size: Float): Float {
        var width = 0f
        text.forEach { ch ->
            width += if (isWide(ch)) size else size * 0.55f
        }
        return width
    }

    private fun isWide(ch: Char): Boolean {
        val code = ch.code
        return code in 0x1100..0x115F ||
            code in 0x2E80..0xA4CF ||
            code in 0xAC00..0xD7A3 ||
            code in 0xF900..0xFAFF ||
            code in 0xFE30..0xFE4F ||
            code in 0xFF00..0xFF60 ||
            code in 0xFFE0..0xFFE6
    }

    // ------------------------------------------------------------------ 备份格式

    /** 备份根节点与版本。version 只作记录，解析时按字段是否存在来判断新老格式。 */
    const val BACKUP_ROOT = "gloveStatistics"
    const val BACKUP_VERSION = "2"

    /**
     * 解析单条**家里手套**记录（`日期|种类|单价|数量`，Base64 字段先解码）。
     * 纯函数，便于离线单测：字段数不对或数字非法就返回 null，跳过这一条。
     */
    fun parseHomeLine(line: String, separator: String, decode: (String) -> String?): HomeWork? {
        val parts = line.split(separator)
        if (parts.size != HOME_FIELDS) return null
        val gloveName = decode(parts[1]) ?: return null
        val price = parts[2].toDoubleOrNull() ?: return null
        val quantity = parts[3].toIntOrNull() ?: return null
        if (parts[0].isBlank()) return null
        return HomeWork(parts[0], gloveName, price, quantity)
    }

    /**
     * 解析单条**厂房记录**，兼容 v0.3.0 的 3 字段格式。
     *
     * - 3 字段：`日期|金额|备注` → 整笔模式（老版本写出来的行）
     * - 7 字段：`日期|金额|备注|种类|数量|单价|模式` → v0.4.0
     */
    fun parseFactoryLine(line: String, separator: String, decode: (String) -> String?): FactoryWork? {
        val parts = line.split(separator)
        if (parts.size < 3) return null
        val date = parts[0].takeIf { it.isNotBlank() } ?: return null
        val amount = parts[1].toDoubleOrNull() ?: return null
        val note = decode(parts[2]) ?: return null
        if (parts.size < FACTORY_FIELDS) {
            return FactoryWork(date, amount, note, "", 0, 0.0, FactoryMode.FLAT)
        }
        return FactoryWork(
            date = date,
            amount = amount,
            note = note,
            gloveName = decode(parts[3]).orEmpty(),
            quantity = parts[4].toIntOrNull() ?: 0,
            unitPrice = parts[5].toDoubleOrNull() ?: 0.0,
            mode = FactoryMode.fromKey(parts[6]),
        )
    }

    /** 备份里的日期一律用 ISO 格式（yyyy-MM-dd），非法日期直接跳过该条。 */
    fun isIsoDate(value: String): Boolean = try {
        LocalDate.parse(value)
        true
    } catch (_: DateTimeParseException) {
        false
    }

    /**
     * 存储行的**文本字段**编码：可能含分隔符 `|` 或换行的内容一律 Base64。
     *
     * Android 的 `android.util.Base64` 用的是标准字母表且不带换行，
     * 与 `java.util.Base64.getEncoder()` 完全一致，所以这里用 JDK 的实现即可，
     * 不需要为了测试引入 Android 运行时。
     */
    fun encodeField(value: String): String =
        java.util.Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    /** 与 [encodeField] 配对；解码失败返回 null，调用方据此跳过这条脏数据。 */
    fun decodeField(value: String): String? = try {
        String(java.util.Base64.getDecoder().decode(value), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }

    /** 按存储格式把一个字段拼进一行。 */
    fun joinFields(fields: List<String>, separator: String): String = fields.joinToString(separator)

    /** 厂房记录在 v0.4.0 的字段个数：日期|金额|备注|种类|数量|单价|模式。 */
    const val FACTORY_FIELDS = 7

    /** 家里手套记录的字段个数：日期|种类|单价|数量。 */
    const val HOME_FIELDS = 4

    /** 只取直接子节点，避免文件里别处的同名标签被误当成数据。 */
    fun child(element: Element, tag: String): Element? {
        val nodes = element.childNodes
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            if (node is Element && node.tagName == tag) return node
        }
        return null
    }

    fun children(element: Element?, tag: String): List<Element> {
        if (element == null) return emptyList()
        val nodes = element.childNodes
        return (0 until nodes.length).mapNotNull { index ->
            (nodes.item(index) as? Element)?.takeIf { it.tagName == tag }
        }
    }

    fun Element.attr(name: String): String = getAttribute(name).orEmpty()

    // ------------------------------------------------------------------ 表格内容

    /** 报表里的一块内容。 */
    class Block(
        val kind: Kind,
        val title: String = "",
        val table: TableSpec? = null,
        val lines: List<Pair<String, String>> = emptyList(),
    ) {
        enum class Kind { TABLE, TOTALS, FOOTER }

        val height: Float
            get() = when (kind) {
                Kind.TABLE -> {
                    val rows = table!!.rows.size.coerceAtLeast(1)
                    val footer = if (table.footer != null) 1 else 0
                    HEADER_HEIGHT * 2 + ROW_HEIGHT * (rows + footer) + 26f
                }

                Kind.TOTALS -> 54f * lines.size + 34f
                Kind.FOOTER -> 46f * lines.size + 10f
            }
    }

    /** 整张报表的内容与总高度。坐标以外的部分（放不放得下）全在这里定。 */
    class Report(val blocks: List<Block>, val height: Float)

    /**
     * 生成本月报表的内容。
     *
     * 纯计算、不画图，因此可以在不启动 Android 的情况下把「每一列够不够宽」
     * 这类问题用单元测试卡住（见 .tooling/domain-check.ps1）。
     */
    fun plan(summary: MonthSummary, scope: ReportScope): Report {
        val specs = mutableListOf<TableSpec>()
        val tallies = summary.byGlove()
        val factoryTallies = summary.byFactoryGlove()

        // ① 每日汇总：把每天的两类活并到一行，一眼看清每天干了多少。
        if (scope == ReportScope.ALL && summary.days.isNotEmpty()) {
            specs += TableSpec(
                title = "每日汇总（按天合并）",
                headers = listOf("日期", "手套数量", "手套收入", "厂房收入", "当日合计"),
                weights = listOf(0.18f, 0.18f, 0.20f, 0.20f, 0.24f),
                aligns = listOf(Align.LEFT, Align.RIGHT, Align.RIGHT, Align.RIGHT, Align.RIGHT),
                rows = summary.days.map { day ->
                    listOf(
                        Fmt.monthDay(day.date),
                        if (day.quantity > 0) "${day.quantity} 双" else "—",
                        Fmt.money(day.homeIncome),
                        Fmt.money(day.factoryIncome),
                        Fmt.money(day.totalIncome),
                    )
                },
                footer = listOf(
                    FooterCell("本月合计", 0..1, Align.LEFT),
                    FooterCell("${summary.quantity} 双", 2..2, Align.RIGHT),
                    FooterCell(Fmt.money(summary.homeIncome), 3..3, Align.RIGHT),
                    FooterCell(Fmt.money(summary.totalIncome), 4..4, Align.RIGHT),
                ),
            )
        }

        // ② 家里手套计件明细（同一天同种类已合并成一行）
        if (scope != ReportScope.FACTORY) {
            val rows = summary.days.flatMap { day -> day.homeRecords }
            // 没记录就不画空表：只在「只看手套账」时才用一行「本月无记录」占位说明。
            if (rows.isNotEmpty() || scope == ReportScope.GLOVE) {
                specs += TableSpec(
                    title = "家里手套计件明细（同一天同种类已合并）",
                    headers = listOf("日期", "手套种类", "数量", "单价", "收入"),
                    weights = listOf(0.16f, 0.30f, 0.16f, 0.16f, 0.22f),
                    aligns = listOf(Align.LEFT, Align.LEFT, Align.RIGHT, Align.RIGHT, Align.RIGHT),
                    rows = rows.map {
                        listOf(
                            Fmt.monthDay(it.date), it.gloveName, "${it.quantity}",
                            Fmt.money(it.unitPrice), Fmt.money(it.income),
                        )
                    },
                    footer = listOf(
                        FooterCell("合计 ${rows.size} 笔", 0..1, Align.LEFT),
                        FooterCell("${summary.quantity} 双", 2..2, Align.RIGHT),
                        FooterCell("", 3..3, Align.RIGHT),
                        FooterCell(Fmt.money(summary.homeIncome), 4..4, Align.RIGHT),
                    ),
                )
            }

            // ③ 各手套种类汇总
            if (tallies.isNotEmpty()) {
                specs += TableSpec(
                    title = "各手套种类汇总",
                    headers = listOf("手套种类", "数量", "收入", "占比"),
                    weights = listOf(0.40f, 0.20f, 0.22f, 0.18f),
                    aligns = listOf(Align.LEFT, Align.RIGHT, Align.RIGHT, Align.RIGHT),
                    rows = tallies.map {
                        val share = if (summary.homeIncome > 0) it.income / summary.homeIncome * 100 else 0.0
                        listOf(
                            it.name, "${it.quantity} 双", Fmt.money(it.income),
                            "${String.format(java.util.Locale.CHINA, "%.1f", share)}%",
                        )
                    },
                    footer = listOf(
                        FooterCell("合计", 0..0, Align.LEFT),
                        FooterCell("${summary.quantity} 双", 1..1, Align.RIGHT),
                        FooterCell(Fmt.money(summary.homeIncome), 2..2, Align.RIGHT),
                        FooterCell("100%", 3..3, Align.RIGHT),
                    ),
                )
            }
        }

        // ④ 厂房工作明细（只有「厂房账」范围内才画空表占位）
        if (scope != ReportScope.GLOVE) {
            val rows = summary.days.flatMap { day -> day.factoryRecords }
            if (rows.isNotEmpty() || scope == ReportScope.FACTORY) {
                specs += TableSpec(
                    title = "厂房工作明细（不计入手套账，同一天同种类已合并）",
                    headers = listOf("日期", "手套种类", "数量", "单价", "收入", "备注"),
                    weights = listOf(0.14f, 0.20f, 0.13f, 0.13f, 0.18f, 0.22f),
                    aligns = listOf(Align.LEFT, Align.LEFT, Align.RIGHT, Align.RIGHT, Align.RIGHT, Align.LEFT),
                    rows = rows.map { record ->
                        listOf(
                            Fmt.monthDay(record.date),
                            if (record.isPiece) record.gloveName else "整笔",
                            if (record.isPiece) "${record.quantity}" else "—",
                            if (record.isPiece) Fmt.money(record.unitPrice) else "—",
                            Fmt.money(record.amount),
                            record.note.ifBlank { "—" },
                        )
                    },
                    footer = listOf(
                        FooterCell("合计 ${rows.size} 笔", 0..1, Align.LEFT),
                        FooterCell(
                            if (summary.factoryQuantity > 0) "${summary.factoryQuantity} 双" else "",
                            2..2,
                            Align.RIGHT,
                        ),
                        FooterCell("", 3..3, Align.RIGHT),
                        FooterCell(Fmt.money(summary.factoryIncome), 4..4, Align.RIGHT),
                        FooterCell("", 5..5, Align.LEFT),
                    ),
                )
            }

            // ⑤ 厂房计件汇总（只有真的按件记过才显示）
            if (factoryTallies.isNotEmpty()) {
                specs += TableSpec(
                    title = "厂房计件汇总（按手套种类）",
                    headers = listOf("手套种类", "数量", "均价", "收入", "占比"),
                    weights = listOf(0.32f, 0.17f, 0.16f, 0.20f, 0.15f),
                    aligns = listOf(Align.LEFT, Align.RIGHT, Align.RIGHT, Align.RIGHT, Align.RIGHT),
                    rows = factoryTallies.map {
                        val share = if (summary.factoryIncome > 0) it.income / summary.factoryIncome * 100 else 0.0
                        listOf(
                            it.name, "${it.quantity} 双", Fmt.money(it.averagePrice),
                            Fmt.money(it.income), "${String.format(java.util.Locale.CHINA, "%.1f", share)}%",
                        )
                    },
                    footer = listOf(
                        FooterCell("合计", 0..0, Align.LEFT),
                        FooterCell("${summary.factoryQuantity} 双", 1..1, Align.RIGHT),
                        FooterCell("", 2..2, Align.RIGHT),
                        FooterCell(Fmt.money(summary.factoryIncome), 3..3, Align.RIGHT),
                        FooterCell("100%", 4..4, Align.RIGHT),
                    ),
                )
            }
        }

        val blocks = mutableListOf<Block>()
        specs.forEach { blocks += Block(Block.Kind.TABLE, table = it) }

        blocks += Block(
            Block.Kind.TOTALS,
            lines = buildList {
                if (scope != ReportScope.FACTORY) {
                    add("家里手套收入" to Fmt.yuan(summary.homeIncome))
                    add("手套总数量 / 干活天数" to "${summary.quantity} 双 / ${summary.homeDays} 天")
                }
                if (scope != ReportScope.GLOVE) {
                    add("厂房工作收入" to Fmt.yuan(summary.factoryIncome))
                    if (summary.factoryQuantity > 0) {
                        add("厂房计件数量" to "${summary.factoryQuantity} 双")
                    }
                    add("厂房工作天数" to "${summary.factoryDays} 天")
                }
                if (scope == ReportScope.ALL) {
                    add("全部劳动收入" to Fmt.yuan(summary.totalIncome))
                }
            },
        )

        blocks += Block(
            Block.Kind.FOOTER,
            lines = listOf("统计月份：${Fmt.month(summary.month)}" to "", "本表由「缝手套记工」在本机离线生成" to ""),
        )

        var height = TITLE_BAR
        blocks.forEach { height += it.height + DIVIDER }
        height += PAD_LEFT
        return Report(blocks, height)
    }

    /** 按权重把内容宽度切成每列的区间。 */
    fun columns(weights: List<Float>): List<ClosedFloatingPointRange<Float>> {
        val left = contentLeft
        val width = contentRight - left
        val total = weights.sum()
        var cursor = left
        return weights.map { weight ->
            val next = cursor + width * (weight / total)
            val range = cursor..next
            cursor = next
            range
        }
    }
}
