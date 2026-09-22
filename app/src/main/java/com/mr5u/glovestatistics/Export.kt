package com.mr5u.glovestatistics

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 导出时需要的数据快照：月度汇总 + 用于 XML 的全量数据。 */
data class ExportSnapshot(
    val summary: MonthSummary,
    val gloves: List<GloveType>,
    val home: List<HomeWork>,
    val factory: List<FactoryWork>,
)

/** 导出 / 恢复入口。 */
interface FileActions {
    fun exportPng(scope: ReportScope, snapshot: ExportSnapshot)
    fun exportXml(snapshot: ExportSnapshot)
    fun pickRestoreFile()
}

/** 给文件起一个带时间戳的名字，避免两次导出互相覆盖。 */
private fun stamp(): String =
    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))

private fun Context.openOutput(uri: Uri): OutputStream =
    requireNotNull(contentResolver.openOutputStream(uri)) { "无法打开所选文件" }

/**
 * 导出与恢复的文件通道。
 *
 * 全部走系统文件选择器（SAF），因此不需要申请任何存储权限，
 * 文件由用户自己决定存到手机本地还是网盘。
 *
 * 「创建文件」和「打开文件」两路回调都会先读走 [Holder] 里的待办信息再清空，
 * 这样用户取消选择后不会误触发上一次的动作。
 */
@Composable
fun rememberFileActions(
    onMessage: (String) -> Unit,
    restore: (List<GloveType>, List<HomeWork>, List<FactoryWork>) -> Unit,
): FileActions {
    val context = LocalContext.current

    // 最近一次导出请求的快照与范围，随本次组合存活。
    val holder = remember { Holder() }

    val createFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val kind = holder.creating
        val data = holder.snapshot
        val chosenScope = holder.scope
        holder.creating = null
        holder.snapshot = null
        if (uri == null || kind == null || data == null) return@rememberLauncherForActivityResult

        val outcome = runCatching {
            when (kind) {
                Kind.PNG -> writePng(context, uri, data.summary, chosenScope)
                Kind.XML -> context.openOutput(uri).use { output ->
                    Backup.write(output, data.gloves, data.home, data.factory, LocalDateTime.now())
                }
            }
        }
        onMessage(
            outcome.fold(
                onSuccess = { if (kind == Kind.PNG) "手套统计图已导出" else "备份文件已导出" },
                onFailure = { "导出失败：${it.message ?: "无法写入所选文件"}" },
            ),
        )
    }

    val openFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }.getOrNull()
        if (text == null) {
            onMessage("无法读取该文件")
            return@rememberLauncherForActivityResult
        }

        when (val result = Backup.read(text)) {
            is RestoreResult.Success -> {
                val parsed = Backup.takeParsed()
                if (parsed == null) {
                    onMessage("恢复失败：数据未能解析")
                } else {
                    restore(parsed.first, parsed.second, parsed.third)
                    onMessage("已恢复 ${result.gloves} 种手套、${result.home} 条手套记录、${result.factory} 条厂房记录")
                }
            }

            is RestoreResult.Failure -> onMessage("恢复失败：${result.reason}")
        }
    }

    return remember(createFile, openFile, holder) {
        object : FileActions {
            override fun exportPng(scope: ReportScope, snapshot: ExportSnapshot) {
                holder.scope = scope
                holder.snapshot = snapshot
                holder.creating = Kind.PNG
                createFile.launch("缝手套记工-${snapshot.summary.month}-${stamp()}.png")
            }

            override fun exportXml(snapshot: ExportSnapshot) {
                holder.scope = ReportScope.ALL
                holder.snapshot = snapshot
                holder.creating = Kind.XML
                createFile.launch("缝手套记工-备份-${stamp()}.xml")
            }

            override fun pickRestoreFile() {
                openFile.launch(arrayOf("text/xml", "application/xml", "*/*"))
            }
        }
    }
}

/** 一次导出请求的待办信息。用可变持有者而不是闭包变量，避免对象表达式里引用不到外层局部变量。 */
private class Holder {
    var snapshot: ExportSnapshot? = null
    var scope: ReportScope = ReportScope.ALL
    var creating: Kind? = null
}

/** PNG 渲染 + 压缩写盘。位图用完立刻回收，避免长列表导出占着内存。 */
private fun writePng(context: Context, uri: Uri, summary: MonthSummary, scope: ReportScope) {
    val bitmap = PngReport.render(summary, scope, LocalDateTime.now())
    try {
        context.openOutput(uri).use { output ->
            if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) {
                error("图片编码失败")
            }
        }
    } finally {
        bitmap.recycle()
    }
}

/** 当前「创建文件」选择器是为了导出哪一类文件。 */
private enum class Kind { PNG, XML }
