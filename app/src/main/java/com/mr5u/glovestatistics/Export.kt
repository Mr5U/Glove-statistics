package com.mr5u.glovestatistics

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 导出 / 恢复入口。 */
interface FileActions {
    fun exportPng(summary: MonthSummary)
    fun exportXml(snapshot: DataSnapshot)
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
 * 图片与备份分开注册两个选择器：这样 PNG 会用 `image/png` 声明，
 * 存到相册或直接发微信时不会被当成「未知类型」。
 *
 * 「创建文件」和「打开文件」两路回调都会先读走 [Holder] 里的待办信息再清空，
 * 这样用户取消选择后不会误触发上一次的动作。
 */
@Composable
fun rememberFileActions(
    onMessage: (String) -> Unit,
    restore: (DataSnapshot) -> Unit,
): FileActions {
    val context = LocalContext.current

    // 最近一次导出请求的内容，随本次组合存活。
    val holder = remember { Holder() }

    val createPng = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri ->
        val summary = holder.summary
        holder.summary = null
        if (uri == null || summary == null) return@rememberLauncherForActivityResult

        val outcome = runCatching { writePng(context, uri, summary) }
        onMessage(
            outcome.fold(
                onSuccess = { "手套计件明细图已导出" },
                onFailure = { "导出失败：${it.message ?: "无法写入所选文件"}" },
            ),
        )
    }

    val createXml = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/xml"),
    ) { uri ->
        val snapshot = holder.snapshot
        holder.snapshot = null
        if (uri == null || snapshot == null) return@rememberLauncherForActivityResult

        val outcome = runCatching {
            context.openOutput(uri).use { output ->
                Backup.write(output, snapshot, LocalDateTime.now())
            }
        }
        onMessage(
            outcome.fold(
                onSuccess = { "备份文件已导出" },
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
        applyRestore(Backup.read(text), onMessage, restore)
    }

    return remember(createPng, createXml, openFile, holder) {
        object : FileActions {
            override fun exportPng(summary: MonthSummary) {
                holder.summary = summary
                createPng.launch("缝手套记工-${summary.month}-${stamp()}.png")
            }

            override fun exportXml(snapshot: DataSnapshot) {
                holder.snapshot = snapshot
                createXml.launch("缝手套记工-备份-${stamp()}.xml")
            }

            override fun pickRestoreFile() {
                openFile.launch(arrayOf("text/xml", "application/xml", "*/*"))
            }
        }
    }
}

/** 把解析结果交给 ViewModel 落盘，并把结果翻译成一句提示。 */
fun applyRestore(
    result: RestoreResult,
    onMessage: (String) -> Unit,
    restore: (DataSnapshot) -> Unit,
) {
    when (result) {
        is RestoreResult.Success -> {
            val parsed = Backup.takeParsed()
            if (parsed == null) {
                onMessage("恢复失败：数据未能解析")
            } else {
                restore(parsed)
                onMessage("已恢复 ${result.gloves} 种手套、${result.home} 条手套记录")
            }
        }

        is RestoreResult.Failure -> onMessage("恢复失败：${result.reason}")
    }
}

/** 一次导出请求的待办信息。用可变持有者而不是闭包变量，避免对象表达式里引用不到外层局部变量。 */
private class Holder {
    var summary: MonthSummary? = null
    var snapshot: DataSnapshot? = null
}

/** PNG 渲染 + 压缩写盘。位图用完立刻回收，避免长列表导出占着内存。 */
private fun writePng(context: Context, uri: Uri, summary: MonthSummary) {
    val bitmap = PngReport.render(summary)
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
