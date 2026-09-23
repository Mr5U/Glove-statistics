package com.mr5u.glovestatistics

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.mr5u.glovestatistics.ReportLayout.attr
import org.w3c.dom.Element
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.xml.sax.InputSource
import java.io.StringReader

/**
 * 把月度汇总画成适合在手机上查看 / 分享的统计图。
 *
 * 两张表里的记录都已经**按天合并**过（见 [mergeHomeWork] / [mergeFactoryWork]）：
 * 同一天同一种手套只会出现一行，数量与收入是当天合计。
 *
 * 排版坐标全部来自 [ReportLayout]，这里只负责画。
 * 表格内容的右边界固定在 [ReportLayout.contentRight]，比画布右边窄 [ReportLayout.PAD_RIGHT]，
 * 所以最后一列的文字不会再贴到图片边缘。
 */
object PngReport {

    private const val MAX_HEIGHT = 16000f

    private val GREEN = Color.rgb(0x08, 0x7B, 0x51)
    private val ORANGE = Color.rgb(0x98, 0x57, 0x00)
    private val INK = Color.rgb(0x1F, 0x24, 0x22)
    private val MUTED = Color.rgb(0x6B, 0x72, 0x80)
    private val HAIRLINE = Color.rgb(0xDD, 0xE1, 0xE5)
    private val ZEBRA = Color.rgb(0xF6, 0xF8, 0xF7)

    private const val FONT = "sans-serif"
    private const val FONT_BOLD = "sans-serif-medium"

    private const val PAGE_WIDTH = ReportLayout.PAGE_WIDTH

    /** 一张已经算好列边界的表。 */
    private class Prepared(
        val spec: ReportLayout.TableSpec,
        val bounds: List<ClosedFloatingPointRange<Float>>,
    )

    fun render(summary: MonthSummary, scope: ReportScope, generatedAt: LocalDateTime): Bitmap {
        val report = ReportLayout.plan(summary, scope)

        // 列边界在这里算一次，下面画表头和每一行时直接复用。
        val tables = report.blocks.mapNotNull { block ->
            block.table?.let { Prepared(it, ReportLayout.columns(it.weights)) }
        }
        val scale = if (report.height > MAX_HEIGHT) MAX_HEIGHT / report.height else 1f
        val width = (PAGE_WIDTH * scale).toInt().coerceAtLeast(1)
        val height = (report.height * scale).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.save()
        canvas.scale(scale, scale)
        draw(canvas, report, tables, summary, scope, generatedAt)
        canvas.restore()
        return bitmap
    }

    // ---------------------------------------------------------------- 绘制

    private fun draw(
        canvas: Canvas,
        report: ReportLayout.Report,
        tables: List<Prepared>,
        summary: MonthSummary,
        scope: ReportScope,
        generatedAt: LocalDateTime,
    ) {
        var tableIndex = 0
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
        canvas.drawRect(0f, 0f, PAGE_WIDTH, ReportLayout.TITLE_BAR, Paint().apply { color = GREEN })
        val fittedTitle = ReportLayout.fit(
            "缝手套记工 · 月度统计",
            maxWidth = ReportLayout.contentRight - ReportLayout.contentLeft,
            size = 44f,
            minSize = 30f,
            measure = { text, size -> measureText(title, text, size) },
        )
        title.textSize = fittedTitle.size
        canvas.drawText(fittedTitle.text, ReportLayout.PAD_LEFT, 58f, title)
        canvas.drawText(
            "${Fmt.month(summary.month)} · ${scope.label}",
            ReportLayout.PAD_LEFT,
            100f,
            subtitle,
        )

        var y = ReportLayout.TITLE_BAR + ReportLayout.DIVIDER

        val tableTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 30f
            typeface = Typeface.create(FONT_BOLD, Typeface.BOLD)
        }
        val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            textSize = ReportLayout.FONT_SIZE
            typeface = Typeface.create(FONT, Typeface.NORMAL)
        }
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = ReportLayout.FONT_SIZE
            typeface = Typeface.create(FONT_BOLD, Typeface.BOLD)
        }
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            textSize = ReportLayout.FONT_SIZE
            typeface = Typeface.create(FONT_BOLD, Typeface.BOLD)
        }
        val zebraPaint = Paint().apply { color = ZEBRA }
        val hairlinePaint = Paint().apply { color = HAIRLINE; strokeWidth = 1.5f }

        report.blocks.forEach { block ->
            when (block.kind) {
                ReportLayout.Block.Kind.TABLE -> {
                    val prepared = tables[tableIndex++]
                    val spec = prepared.spec
                    cellPaint.typeface = Typeface.create(FONT, Typeface.NORMAL)
                    headerPaint.typeface = Typeface.create(FONT_BOLD, Typeface.BOLD)
                    footerPaint.typeface = Typeface.create(FONT_BOLD, Typeface.BOLD)

                    val fittedTitle = ReportLayout.fit(
                        spec.title,
                        maxWidth = ReportLayout.contentRight - ReportLayout.contentLeft,
                        size = 30f,
                        minSize = 22f,
                        measure = { text, size -> measureText(tableTitle, text, size) },
                    )
                    tableTitle.textSize = fittedTitle.size
                    canvas.drawText(fittedTitle.text, ReportLayout.PAD_LEFT, y + 34f, tableTitle)
                    y += 56f

                    canvas.drawRect(
                        ReportLayout.contentLeft,
                        y,
                        ReportLayout.contentRight,
                        y + ReportLayout.HEADER_HEIGHT,
                        Paint().apply { color = if (spec.title.contains("厂房")) ORANGE else GREEN },
                    )
                    spec.headers.forEachIndexed { index, header ->
                        drawCell(canvas, header, prepared.bounds[index], spec.aligns[index], headerPaint, y + ReportLayout.HEADER_HEIGHT / 2f)
                    }
                    y += ReportLayout.HEADER_HEIGHT

                    if (spec.rows.isEmpty()) {
                        canvas.drawRect(
                            ReportLayout.contentLeft,
                            y,
                            ReportLayout.contentRight,
                            y + ReportLayout.ROW_HEIGHT,
                            zebraPaint,
                        )
                        drawCell(canvas, spec.emptyText, prepared.bounds[0], ReportLayout.Align.LEFT, cellPaint, y + ReportLayout.ROW_HEIGHT / 2f)
                        y += ReportLayout.ROW_HEIGHT
                    } else {
                        spec.rows.forEachIndexed { index, row ->
                            if (index % 2 == 1) {
                                canvas.drawRect(
                                    ReportLayout.contentLeft,
                                    y,
                                    ReportLayout.contentRight,
                                    y + ReportLayout.ROW_HEIGHT,
                                    zebraPaint,
                                )
                            }
                            row.forEachIndexed { column, text ->
                                drawCell(canvas, text, prepared.bounds[column], spec.aligns[column], cellPaint, y + ReportLayout.ROW_HEIGHT / 2f)
                            }
                            canvas.drawLine(
                                ReportLayout.contentLeft,
                                y + ReportLayout.ROW_HEIGHT,
                                ReportLayout.contentRight,
                                y + ReportLayout.ROW_HEIGHT,
                                hairlinePaint,
                            )
                            y += ReportLayout.ROW_HEIGHT
                        }
                    }

                    spec.footer?.let { footer ->
                        canvas.drawLine(
                            ReportLayout.contentLeft,
                            y,
                            ReportLayout.contentRight,
                            y,
                            hairlinePaint,
                        )
                        footer.forEach { cell ->
                            // 合计行每一格都**显式指定落在哪一列**。
                            // 老版本是按顺序往窄列里塞，结果「合计 / N 笔」被挤到图片最右边被切掉。
                            val span = prepared.bounds[cell.columns.first].start..prepared.bounds[cell.columns.last].endInclusive
                            drawCell(canvas, cell.text, span, cell.align, footerPaint, y + ReportLayout.ROW_HEIGHT / 2f)
                        }
                        y += ReportLayout.ROW_HEIGHT
                    }
                    y += 26f
                }

                ReportLayout.Block.Kind.TOTALS -> {
                    canvas.drawRect(
                        ReportLayout.contentLeft,
                        y,
                        ReportLayout.contentRight,
                        y + block.height - 16f,
                        Paint().apply { color = Color.rgb(0xF1, 0xF6, 0xF3) },
                    )
                    var rowY = y + 16f
                    block.lines.forEach { (label, value) ->
                        val paint = if (label == "全部劳动收入") footerPaint else cellPaint
                        val fittedLabel = ReportLayout.fit(
                            label,
                            maxWidth = (ReportLayout.contentRight - ReportLayout.contentLeft) * 0.55f,
                            size = ReportLayout.FONT_SIZE,
                            measure = { text, size -> measureText(cellPaint, text, size) },
                        )
                        paint.textSize = fittedLabel.size
                        canvas.drawText(fittedLabel.text, ReportLayout.contentLeft + 24f, rowY + 34f, paint)

                        val valueMax = (ReportLayout.contentRight - ReportLayout.contentLeft) * 0.35f
                        val fittedValue = ReportLayout.fit(
                            value,
                            maxWidth = valueMax,
                            size = ReportLayout.FONT_SIZE,
                            measure = { text, size -> measureText(paint, text, size) },
                        )
                        val right = ReportLayout.contentRight - 24f
                        paint.textSize = fittedValue.size
                        canvas.drawText(fittedValue.text, right - paint.measureText(fittedValue.text), rowY + 34f, paint)
                        paint.textSize = ReportLayout.FONT_SIZE
                        rowY += 54f
                    }
                    y += block.height
                }

                ReportLayout.Block.Kind.FOOTER -> {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = MUTED
                        textSize = 22f
                        typeface = Typeface.create(FONT, Typeface.NORMAL)
                    }
                    var rowY = y + 26f
                    block.lines.forEach { (text, _) ->
                        canvas.drawText(text, ReportLayout.PAD_LEFT, rowY, paint)
                        rowY += 46f
                    }
                    y += block.height
                }
            }
            y += ReportLayout.DIVIDER
        }
    }

    /** 复用同一个 [Paint] 测宽，避免每个单元格都新建对象。 */
    private fun measureText(paint: Paint, text: String, size: Float): Float {
        paint.textSize = size
        return paint.measureText(text)
    }

    /** 把文字画进某列的区间里，放不下就先缩字号、再截断，绝不会越过列边界。 */
    private fun drawCell(
        canvas: Canvas,
        text: String,
        bounds: ClosedFloatingPointRange<Float>,
        align: ReportLayout.Align,
        paint: Paint,
        centerY: Float,
    ) {
        val start = bounds.start + ReportLayout.CELL_INSET
        val end = bounds.endInclusive - ReportLayout.CELL_INSET
        val available = end - start
        if (available <= 0f || text.isEmpty()) return

        val fitted = ReportLayout.fit(
            text = text,
            maxWidth = available,
            size = ReportLayout.FONT_SIZE,
            measure = { value, size ->
                paint.textSize = size
                paint.measureText(value)
            },
        )
        paint.textSize = fitted.size
        val x = when (align) {
            ReportLayout.Align.LEFT -> start
            ReportLayout.Align.RIGHT -> end - paint.measureText(fitted.text)
        }
        val baseline = centerY - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(fitted.text, x, baseline, paint)
        paint.textSize = ReportLayout.FONT_SIZE
    }
}

/** 恢复结果，用于向用户报告失败原因。 */
sealed interface RestoreResult {
    data class Success(val gloves: Int, val factoryGloves: Int, val home: Int, val factory: Int) : RestoreResult
    data class Failure(val reason: String) : RestoreResult
}

/**
 * XML 备份格式与恢复。
 *
 * 结构刻意保持扁平直观，方便你以后用电脑上的编辑器直接查看或修改：
 * ```xml
 * <gloveStatistics version="2" exportedAt="...">
 *   <gloves><glove name="加绒劳保手套" unitPrice="1.20"/></gloves>
 *   <factoryGloves><glove name="22 公分绿牛" unitPrice="0.26"/></factoryGloves>
 *   <homeWorks><work date="2026-09-23" gloveName="22 公分绿牛" unitPrice="0.26" quantity="602"/></homeWorks>
 *   <factoryWorks>
 *     <work date="2026-09-23" mode="piece" gloveName="22 公分绿牛" quantity="600" unitPrice="0.26" amount="156.00" note=""/>
 *     <work date="2026-09-23" mode="flat" amount="10" note="打杂"/>
 *   </factoryWorks>
 * </gloveStatistics>
 * ```
 *
 * 手套种类与记录之间通过 [gloveName] 关联；记录里同时保存了当天单价快照，
 * 所以即使手套库被清理过，恢复后历史收入也不会变。
 *
 * **v1 老备份文件照样能恢复**：`<factoryGloves>` 段缺失就当作空列表，
 * `<factoryWorks>` 里没有 `mode` / `quantity` 的记录按「整笔收入」还原，金额不会算错。
 */
object Backup {

    fun write(
        output: OutputStream,
        snapshot: DataSnapshot,
        exportedAt: LocalDateTime,
    ) {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val root = document.createElement(ReportLayout.BACKUP_ROOT)
        root.setAttribute("version", ReportLayout.BACKUP_VERSION)
        root.setAttribute("exportedAt", exportedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
        root.setAttribute("app", "Glove-Statistics")
        document.appendChild(root)

        root.appendChild(glovesElement(document, "gloves", snapshot.gloves))
        root.appendChild(glovesElement(document, "factoryGloves", snapshot.factoryGloves))

        val homeElement = document.createElement("homeWorks")
        snapshot.home.forEach { work ->
            homeElement.appendChild(document.createElement("work").apply {
                setAttribute("date", work.date)
                setAttribute("gloveName", work.gloveName)
                setAttribute("unitPrice", number(work.unitPrice))
                setAttribute("quantity", work.quantity.toString())
            })
        }
        root.appendChild(homeElement)

        val factoryElement = document.createElement("factoryWorks")
        snapshot.factory.forEach { work ->
            factoryElement.appendChild(document.createElement("work").apply {
                setAttribute("date", work.date)
                setAttribute("mode", work.mode.key)
                setAttribute("amount", number(work.amount))
                setAttribute("note", work.note)
                if (work.gloveName.isNotBlank()) setAttribute("gloveName", work.gloveName)
                if (work.quantity > 0) setAttribute("quantity", work.quantity.toString())
                if (work.unitPrice > 0) setAttribute("unitPrice", number(work.unitPrice))
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

    private fun glovesElement(
        document: org.w3c.dom.Document,
        tag: String,
        gloves: List<GloveType>,
    ): Element = document.createElement(tag).apply {
        gloves.forEach { glove ->
            appendChild(document.createElement("glove").apply {
                setAttribute("name", glove.name)
                setAttribute("unitPrice", number(glove.unitPrice))
            })
        }
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

        if (root.tagName != ReportLayout.BACKUP_ROOT) {
            return RestoreResult.Failure("这不是「缝手套记工」的备份文件")
        }

        return try {
            val gloves = parseGloves(root, "gloves")
            val factoryGloves = parseGloves(root, "factoryGloves")

            val home = ReportLayout.children(ReportLayout.child(root, "homeWorks"), "work").mapNotNull { node ->
                val date = node.attr("date").takeIf { ReportLayout.isIsoDate(it) } ?: return@mapNotNull null
                val name = node.attr("gloveName").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val quantity = node.attr("quantity").toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                HomeWork(date, name, node.attr("unitPrice").toDoubleOrNull() ?: 0.0, quantity)
            }

            val factoryWorks = ReportLayout.children(ReportLayout.child(root, "factoryWorks"), "work").mapNotNull { node ->
                val date = node.attr("date").takeIf { ReportLayout.isIsoDate(it) } ?: return@mapNotNull null
                val amount = node.attr("amount").toDoubleOrNull() ?: return@mapNotNull null
                val quantity = node.attr("quantity").toIntOrNull() ?: 0
                val gloveName = node.attr("gloveName")
                // v1 备份没有 mode 属性：有数量和种类就按计件还原，否则按整笔还原。
                val mode = when {
                    node.hasAttribute("mode") -> FactoryMode.fromKey(node.attr("mode"))
                    gloveName.isNotBlank() && quantity > 0 -> FactoryMode.PIECE
                    else -> FactoryMode.FLAT
                }
                FactoryWork(
                    date = date,
                    amount = amount,
                    note = node.attr("note"),
                    gloveName = gloveName,
                    quantity = quantity,
                    unitPrice = node.attr("unitPrice").toDoubleOrNull() ?: 0.0,
                    mode = mode,
                )
            }

            if (gloves.isEmpty() && factoryGloves.isEmpty() && home.isEmpty() && factoryWorks.isEmpty()) {
                RestoreResult.Failure("文件里没有可恢复的数据")
            } else {
                pending = DataSnapshot(gloves, factoryGloves, home, factoryWorks)
                RestoreResult.Success(gloves.size, factoryGloves.size, home.size, factoryWorks.size)
            }
        } catch (error: Exception) {
            RestoreResult.Failure("文件内容有误：${error.message ?: "读取失败"}")
        }
    }

    private fun parseGloves(root: Element, tag: String): List<GloveType> =
        ReportLayout.children(ReportLayout.child(root, tag), "glove").mapNotNull { node ->
            val name = node.attr("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            GloveType(name, node.attr("unitPrice").toDoubleOrNull() ?: 0.0)
        }

    /** [read] 成功后暂存解析结果，由 [takeParsed] 交给 ViewModel 落盘。 */
    private var pending: DataSnapshot? = null

    fun takeParsed(): DataSnapshot? {
        val parsed = pending ?: return null
        pending = null
        return parsed
    }

    private fun number(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}
