package com.mr5u.glovestatistics

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import org.w3c.dom.Element
import java.io.OutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.xml.sax.InputSource
import java.io.StringReader

/** PNG 报表要包含哪一部分账。 */
enum class ReportScope(val label: String) {
    GLOVE("只导出手套账单"),
    FACTORY("只导出厂房收入"),
    ALL("导出全部劳动收入"),
}

/** 报表里的一行明细，已经按范围筛选好。 */
private sealed interface ReportRow {
    data class Home(val date: String, val name: String, val quantity: Int, val price: Double, val amount: Double) : ReportRow
    data class Factory(val date: String, val amount: Double, val note: String) : ReportRow
}

/** 单行表格的单元格文本，null 表示该列不参与本表。 */
private data class TableSpec(
    val title: String,
    val headers: List<String>,
    val weights: List<Float>,
    val rows: List<List<String>>,
    val footer: List<String>?,
    val color: Int,
)

/**
 * 把月度汇总画成适合在手机上查看 / 分享的统计图。
 *
 * 用固定宽度 1080px，高度按内容行数推算；内容过长时整体等比缩小，
 * 避免超高图片在某些相册或聊天软件里无法预览。
 */
object PngReport {

    private const val PAGE_WIDTH = 1080f
    private const val MAX_HEIGHT = 16000f

    private const val PAD = 40f
    private const val ROW_HEIGHT = 56f
    private const val HEADER_HEIGHT = 64f
    private const val TITLE_BAR = 132f
    private const val DIVIDER = 14f

    private val GREEN = Color.rgb(0x08, 0x7B, 0x51)
    private val ORANGE = Color.rgb(0x98, 0x57, 0x00)
    private val INK = Color.rgb(0x1F, 0x24, 0x22)
    private val MUTED = Color.rgb(0x6B, 0x72, 0x80)
    private val HAIRLINE = Color.rgb(0xDD, 0xE1, 0xE5)
    private val ZEBRA = Color.rgb(0xF6, 0xF8, 0xF7)

    private const val FONT = "sans-serif"
    private const val FONT_BOLD = "sans-serif-medium"

    fun render(summary: MonthSummary, scope: ReportScope, generatedAt: LocalDateTime): Bitmap {
        val layout = plan(summary, scope, generatedAt)

        val scale = if (layout.height > MAX_HEIGHT) MAX_HEIGHT / layout.height else 1f
        val width = (PAGE_WIDTH * scale).toInt().coerceAtLeast(1)
        val height = (layout.height * scale).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.save()
        canvas.scale(scale, scale)
        draw(canvas, layout, summary, scope)
        canvas.restore()
        return bitmap
    }

    // ---------------------------------------------------------------- 排版

    private class Block(val kind: Kind, val table: TableSpec? = null, val lines: List<Pair<String, String>> = emptyList()) {
        enum class Kind { TABLE, TOTALS, FOOTER }
        val height: Float
            get() = when (kind) {
                Kind.TABLE -> HEADER_HEIGHT * 2 + ROW_HEIGHT * ((table!!.rows.size + if (table.footer != null) 1 else 0).toFloat()) + 24f
                Kind.TOTALS -> 52f * lines.size + 32f
                Kind.FOOTER -> 46f * lines.size + 8f
            }
    }

    private class Layout(val blocks: List<Block>, val height: Float)

    private fun plan(summary: MonthSummary, scope: ReportScope, generatedAt: LocalDateTime): Layout {
        val blocks = mutableListOf<Block>()

        if (scope != ReportScope.FACTORY) {
            val rows = summary.days.flatMap { day ->
                day.homeRecords.map { ReportRow.Home(it.date, it.gloveName, it.quantity, it.unitPrice, it.income) }
            }
            val spec = TableSpec(
                title = "家里手套计件明细",
                headers = listOf("日期", "手套种类", "数量", "单价", "收入"),
                weights = listOf(0.16f, 0.34f, 0.12f, 0.17f, 0.21f),
                rows = rows.map { listOf(Fmt.monthDay(it.date), it.name, "${it.quantity}", Fmt.money(it.price), Fmt.money(it.amount)) },
                footer = listOf("合计", "${rows.size} 笔", "${summary.quantity} 双", "", Fmt.money(summary.homeIncome)),
                color = GREEN,
            )
            blocks += Block(Block.Kind.TABLE, table = spec)

            val tallies = summary.byGlove()
            if (tallies.isNotEmpty()) {
                blocks += Block(
                    Block.Kind.TABLE,
                    table = TableSpec(
                        title = "各手套种类汇总",
                        headers = listOf("手套种类", "数量", "收入", "占比"),
                        weights = listOf(0.40f, 0.18f, 0.24f, 0.18f),
                        rows = tallies.map {
                            val share = if (summary.homeIncome > 0) it.income / summary.homeIncome * 100 else 0.0
                            listOf(it.name, "${it.quantity} 双", Fmt.money(it.income), "${String.format(java.util.Locale.CHINA, "%.1f", share)}%")
                        },
                        footer = null,
                        color = GREEN,
                    ),
                )
            }
        }

        if (scope != ReportScope.GLOVE) {
            val rows = summary.days.flatMap { day ->
                day.factoryRecords.map { ReportRow.Factory(it.date, it.amount, it.note) }
            }
            blocks += Block(
                Block.Kind.TABLE,
                table = TableSpec(
                    title = "厂房工作明细（不计入手套账）",
                    headers = listOf("日期", "收入", "备注"),
                    weights = listOf(0.18f, 0.22f, 0.60f),
                    rows = rows.map { listOf(Fmt.monthDay(it.date), Fmt.money(it.amount), it.note.ifBlank { "—" }) },
                    footer = listOf("合计", Fmt.money(summary.factoryIncome), "${rows.size} 笔"),
                    color = ORANGE,
                ),
            )
        }

        blocks += Block(
            Block.Kind.TOTALS,
            lines = buildList {
                if (scope != ReportScope.FACTORY) {
                    add("家里手套收入" to Fmt.yuan(summary.homeIncome))
                    add("手套总数量 / 干活天数" to "${summary.quantity} 双 / ${summary.homeDays} 天")
                }
                if (scope != ReportScope.GLOVE) {
                    add("厂房工作收入" to Fmt.yuan(summary.factoryIncome))
                    add("厂房工作天数" to "${summary.factoryDays} 天")
                }
                if (scope == ReportScope.ALL) {
                    add("全部劳动收入" to Fmt.yuan(summary.totalIncome))
                }
            },
        )

        blocks += Block(
            Block.Kind.FOOTER,
            lines = listOf(
                "统计月份：${Fmt.month(summary.month)}" to "",
                "导出时间：${generatedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}" to "",
                "本表由「缝手套记工」在本机离线生成" to "",
            ),
        )

        var height = TITLE_BAR
        blocks.forEach { height += it.height + DIVIDER }
        height += PAD
        return Layout(blocks, height)
    }

    // ---------------------------------------------------------------- 绘制

    private fun draw(canvas: Canvas, layout: Layout, summary: MonthSummary, scope: ReportScope) {
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 44f
            typeface = Typeface.create(FONT_BOLD, Typeface.BOLD)
        }
        val subtitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
            textSize = 26f
            typeface = Typeface.create(FONT, Typeface.NORMAL)
        }
        canvas.drawRect(0f, 0f, PAGE_WIDTH, TITLE_BAR, Paint().apply { color = GREEN })
        canvas.drawText("缝手套记工 · 月度统计", PAD, 58f, title)
        canvas.drawText("${Fmt.month(summary.month)} · ${scope.label}", PAD, 100f, subtitle)

        var y = TITLE_BAR + DIVIDER

        val tableTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 30f
            typeface = Typeface.create(FONT_BOLD, Typeface.BOLD)
        }
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 26f
            typeface = Typeface.create(FONT_BOLD, Typeface.BOLD)
        }
        val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            textSize = 26f
            typeface = Typeface.create(FONT, Typeface.NORMAL)
        }
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            textSize = 27f
            typeface = Typeface.create(FONT_BOLD, Typeface.BOLD)
        }
        val zebraPaint = Paint().apply { color = ZEBRA }
        val hairlinePaint = Paint().apply { color = HAIRLINE; strokeWidth = 1.5f }

        layout.blocks.forEach { block ->
            when (block.kind) {
                Block.Kind.TABLE -> {
                    val spec = block.table!!
                    canvas.drawText(spec.title, PAD, y + 34f, tableTitle)
                    y += 56f
                    val left = PAD
                    val right = PAGE_WIDTH - PAD
                    val tableWidth = right - left
                    val columns = columnBounds(left, tableWidth, spec.weights)

                    canvas.drawRect(left, y, right, y + HEADER_HEIGHT, Paint().apply { color = spec.color })
                    spec.headers.forEachIndexed { index, header ->
                        val align = if (index == 0 || index == 1) Paint.Align.LEFT else Paint.Align.RIGHT
                        drawCell(canvas, header, columns[index], align, headerPaint, y + HEADER_HEIGHT / 2f)
                    }
                    y += HEADER_HEIGHT

                    if (spec.rows.isEmpty()) {
                        canvas.drawRect(left, y, right, y + ROW_HEIGHT, zebraPaint)
                        drawCell(canvas, "本月无记录", columns[0], Paint.Align.LEFT, cellPaint, y + ROW_HEIGHT / 2f)
                        y += ROW_HEIGHT
                    } else {
                        spec.rows.forEachIndexed { index, row ->
                            if (index % 2 == 1) canvas.drawRect(left, y, right, y + ROW_HEIGHT, zebraPaint)
                            row.forEachIndexed { column, text ->
                                val align = if (column == 0 || column == 1) Paint.Align.LEFT else Paint.Align.RIGHT
                                drawCell(canvas, text, columns[column], align, cellPaint, y + ROW_HEIGHT / 2f)
                            }
                            canvas.drawLine(left, y + ROW_HEIGHT, right, y + ROW_HEIGHT, hairlinePaint)
                            y += ROW_HEIGHT
                        }
                    }

                    spec.footer?.let { footer ->
                        canvas.drawLine(left, y, right, y, hairlinePaint)
                        footer.forEachIndexed { column, text ->
                            val align = if (column == 0 || column == 1) Paint.Align.LEFT else Paint.Align.RIGHT
                            drawCell(canvas, text, columns[column], align, footerPaint, y + ROW_HEIGHT / 2f)
                        }
                        y += ROW_HEIGHT
                    }
                    y += 24f
                }

                Block.Kind.TOTALS -> {
                    canvas.drawRect(PAD, y, PAGE_WIDTH - PAD, y + block.height - 16f, Paint().apply { color = Color.rgb(0xF1, 0xF6, 0xF3) })
                    var rowY = y + 16f
                    block.lines.forEachIndexed { index, (label, value) ->
                        val paint = if (label == "全部劳动收入") footerPaint else cellPaint
                        canvas.drawText(label, PAD + 24f, rowY + 34f, paint)
                        drawRightAligned(canvas, value, PAGE_WIDTH - PAD - 24f, paint, rowY + 34f)
                        rowY += 52f
                    }
                    y += block.height
                }

                Block.Kind.FOOTER -> {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = MUTED
                        textSize = 22f
                        typeface = Typeface.create(FONT, Typeface.NORMAL)
                    }
                    var rowY = y + 26f
                    block.lines.forEach { (text, _) ->
                        canvas.drawText(text, PAD, rowY, paint)
                        rowY += 46f
                    }
                    y += block.height
                }
            }
            y += DIVIDER
        }
    }

    /** 把权重换算成每列的左右边界。 */
    private fun columnBounds(left: Float, width: Float, weights: List<Float>): List<Pair<Float, Float>> {
        val total = weights.sum()
        var cursor = left
        return weights.map { weight ->
            val next = cursor + width * (weight / total)
            val bounds = cursor to next
            cursor = next
            bounds
        }
    }

    private fun drawCell(
        canvas: Canvas,
        text: String,
        bounds: Pair<Float, Float>,
        align: Paint.Align,
        paint: Paint,
        centerY: Float,
    ) {
        val (start, end) = bounds
        val x = when (align) {
            Paint.Align.LEFT -> start + 12f
            Paint.Align.RIGHT -> end - 12f
            else -> (start + end) / 2f
        }
        val baseline = centerY - (paint.descent() + paint.ascent()) / 2f
        val available = (end - start) - 24f
        canvas.drawText(clip(text, paint, available), x, baseline, paint)
    }

    /** 合计区用的右对齐文字：直接贴着给定的右边界画，不需要列宽。 */
    private fun drawRightAligned(
        canvas: Canvas,
        text: String,
        right: Float,
        paint: Paint,
        centerY: Float,
    ) {
        val baseline = centerY - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, right - paint.measureText(text), baseline, paint)
    }

    /** 名字过长时截断并加省略号，避免压到相邻列。 */
    private fun clip(text: String, paint: Paint, maxWidth: Float): String {
        if (maxWidth <= 0 || paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end) + "…"
    }
}

/** 恢复结果，用于向用户报告失败原因。 */
sealed interface RestoreResult {
    data class Success(val gloves: Int, val home: Int, val factory: Int) : RestoreResult
    data class Failure(val reason: String) : RestoreResult
}

/**
 * XML 备份格式与恢复。
 *
 * 结构刻意保持扁平直观，方便你以后用电脑上的编辑器直接查看或修改：
 * ```xml
 * <gloveStatistics version="1" exportedAt="...">
 *   <gloves><glove name="加绒劳保手套" unitPrice="1.20"/></gloves>
 *   <homeWorks><work date="2026-09-22" gloveName="加绒劳保手套" unitPrice="1.20" quantity="30"/></homeWorks>
 *   <factoryWorks><work date="2026-09-22" amount="180" note="装配线"/></factoryWorks>
 * </gloveStatistics>
 * ```
 * 手套种类与记录之间通过 [gloveName] 关联；记录里同时保存了当天单价快照，
 * 所以即使手套库被清理过，恢复后历史收入也不会变。
 */
object Backup {

    private const val ROOT = "gloveStatistics"
    private const val VERSION = "1"

    fun write(
        output: OutputStream,
        gloves: List<GloveType>,
        home: List<HomeWork>,
        factory: List<FactoryWork>,
        exportedAt: LocalDateTime,
    ) {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val root = document.createElement(ROOT)
        root.setAttribute("version", VERSION)
        root.setAttribute("exportedAt", exportedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
        root.setAttribute("app", "Glove-Statistics")
        document.appendChild(root)

        val glovesElement = document.createElement("gloves")
        gloves.forEach { glove ->
            glovesElement.appendChild(document.createElement("glove").apply {
                setAttribute("name", glove.name)
                setAttribute("unitPrice", number(glove.unitPrice))
            })
        }
        root.appendChild(glovesElement)

        val homeElement = document.createElement("homeWorks")
        home.forEach { work ->
            homeElement.appendChild(document.createElement("work").apply {
                setAttribute("date", work.date)
                setAttribute("gloveName", work.gloveName)
                setAttribute("unitPrice", number(work.unitPrice))
                setAttribute("quantity", work.quantity.toString())
            })
        }
        root.appendChild(homeElement)

        val factoryElement = document.createElement("factoryWorks")
        factory.forEach { work ->
            factoryElement.appendChild(document.createElement("work").apply {
                setAttribute("date", work.date)
                setAttribute("amount", number(work.amount))
                setAttribute("note", work.note)
            })
        }
        root.appendChild(factoryElement)

        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.INDENT, "yes")
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
        }
        transformer.transform(DOMSource(document), StreamResult(output))
    }

    /**
     * 解析备份内容。任何结构性问题都会返回 [RestoreResult.Failure] 而不是抛异常，
     * 避免用户选错文件时应用直接崩掉。
     */
    fun read(text: String): RestoreResult {
        val root = try {
            val parser = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                // 备份文件来自文件选择器，属于不可信输入：关掉 DTD / 外部实体。
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }.newDocumentBuilder()
            parser.parse(InputSource(StringReader(text))).documentElement
        } catch (error: Exception) {
            return RestoreResult.Failure("文件无法解析：${error.message ?: "格式不正确"}")
        } ?: return RestoreResult.Failure("文件内容为空")

        if (root.tagName != ROOT) {
            return RestoreResult.Failure("这不是「缝手套记工」的备份文件")
        }

        return try {
            val gloves = root.child("gloves").children("glove").mapNotNull { node ->
                val name = node.attr("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                GloveType(name, node.attr("unitPrice").toDoubleOrNull() ?: 0.0)
            }

            val home = root.child("homeWorks").children("work").mapNotNull { node ->
                val date = node.attr("date").takeIf { isIsoDate(it) } ?: return@mapNotNull null
                val name = node.attr("gloveName").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val quantity = node.attr("quantity").toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                HomeWork(date, name, node.attr("unitPrice").toDoubleOrNull() ?: 0.0, quantity)
            }

            val factoryWorks = root.child("factoryWorks").children("work").mapNotNull { node ->
                val date = node.attr("date").takeIf { isIsoDate(it) } ?: return@mapNotNull null
                val amount = node.attr("amount").toDoubleOrNull() ?: return@mapNotNull null
                FactoryWork(date, amount, node.attr("note"))
            }

            if (gloves.isEmpty() && home.isEmpty() && factoryWorks.isEmpty()) {
                RestoreResult.Failure("文件里没有可恢复的数据")
            } else {
                pending = Parsed(gloves, home, factoryWorks)
                RestoreResult.Success(gloves.size, home.size, factoryWorks.size)
            }
        } catch (error: Exception) {
            RestoreResult.Failure("文件内容有误：${error.message ?: "读取失败"}")
        }
    }

    private class Parsed(
        val gloves: List<GloveType>,
        val home: List<HomeWork>,
        val factory: List<FactoryWork>,
    )

    /** [read] 成功后暂存解析结果，由 [takeParsed] 交给 ViewModel 落盘。 */
    private var pending: Parsed? = null

    fun takeParsed(): Triple<List<GloveType>, List<HomeWork>, List<FactoryWork>>? {
        val parsed = pending ?: return null
        pending = null
        return Triple(parsed.gloves, parsed.home, parsed.factory)
    }

    /** 只取直接子节点，避免文件里别处的同名标签被误当成数据。 */
    private fun Element.child(tag: String): Element? {
        val nodes = childNodes
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            if (node is Element && node.tagName == tag) return node
        }
        return null
    }

    private fun Element?.children(tag: String): List<Element> {
        if (this == null) return emptyList()
        val nodes = childNodes
        return (0 until nodes.length).mapNotNull { index ->
            (nodes.item(index) as? Element)?.takeIf { it.tagName == tag }
        }
    }

    private fun Element.attr(name: String): String = getAttribute(name).orEmpty()

    /** 备份里的日期一律用 ISO 格式（yyyy-MM-dd），非法日期直接跳过该条。 */
    private fun isIsoDate(value: String): Boolean = try {
        LocalDate.parse(value)
        true
    } catch (_: DateTimeParseException) {
        false
    }

    private fun number(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}
