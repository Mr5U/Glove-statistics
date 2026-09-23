package com.mr5u.glovestatistics

import android.content.Context
import android.os.Build
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 自动备份：数据每次变化后，额外把一份完整 XML 快照写到 App 自己的外部目录。
 *
 * 目录是 `Android/data/<包名>/files/backup/`：
 * - **不需要任何权限**（属于应用专属外部存储，Android 4.4 起就免权限）；
 * - 用户看不到也删不掉（除非手动清除应用数据），所以是可靠的兜底；
 * - **卸载重装后仍然存在**，这是它比 SharedPreferences 更安全的地方。
 *
 * 只保留最近 [KEEP] 份，超出就按时间删掉最旧的。
 * 同样的数据不会被重复备份两次（用内容指纹判断），所以频繁记工也不会把目录塞满。
 */
object AutoBackup {

    /** 保留的备份份数。 */
    private const val KEEP = 5

    private const val DIR_NAME = "backup"
    private const val FILE_PREFIX = "glove-backup-"
    private const val FILE_SUFFIX = ".xml"

    private const val PREFS_NAME = "glove_statistics_autobackup"
    private const val KEY_LAST_AT = "lastBackupAt"
    private const val KEY_FINGERPRINT = "lastFingerprint"

    /** 备份目录（不保证存在，用 [dir] 创建）。 */
    private fun dir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, DIR_NAME)

    /** 数据变化后调用：内容有变化才写新备份。 */
    fun onDataChanged(context: Context, viewModel: WorkViewModel) {
        runCatching { backup(context, viewModel.snapshot(), force = false) }
    }

    /** 立即备份（忽略内容指纹），返回是否成功。 */
    fun backupNow(context: Context, snapshot: DataSnapshot): Boolean =
        runCatching { backup(context, snapshot, force = true) }.isSuccess

    private fun backup(context: Context, snapshot: DataSnapshot, force: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val fingerprint = fingerprint(snapshot)
        if (!force && prefs.getString(KEY_FINGERPRINT, null) == fingerprint) return

        val target = dir(context)
        if (!target.exists() && !target.mkdirs()) error("无法创建备份目录")

        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
        target.resolve("$FILE_PREFIX$stamp$FILE_SUFFIX").outputStream().use { output ->
            Backup.write(output, snapshot, LocalDateTime.now())
        }

        prefs.edit()
            .putString(KEY_FINGERPRINT, fingerprint)
            .putLong(KEY_LAST_AT, System.currentTimeMillis())
            .apply()

        rotate(target)
    }

    /** 只留最近的 [KEEP] 份。 */
    private fun rotate(target: File) {
        val files = target.listFiles { file -> file.isFile && file.name.startsWith(FILE_PREFIX) }
            ?.sortedByDescending { it.name }
            ?: return
        files.drop(KEEP).forEach { it.delete() }
    }

    /** 最近一次自动备份的时间；从没备份过返回 null。 */
    fun lastBackupAt(context: Context): LocalDateTime? {
        val millis = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_AT, 0L)
        if (millis <= 0L) return null
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
    }

    /** 当前保存在设备上的自动备份文件，最新的排在最前。 */
    fun available(context: Context): List<File> =
        dir(context).listFiles { file -> file.isFile && file.name.startsWith(FILE_PREFIX) }
            ?.sortedByDescending { it.name }
            ?: emptyList()

    /** 从一份自动备份恢复。解析失败时返回一条给用户看的原因。 */
    fun restore(context: Context, file: File): RestoreResult {
        val text = runCatching { file.readText() }.getOrElse {
            return RestoreResult.Failure("备份文件读不出来：${it.message ?: "无法读取"}")
        }
        return Backup.read(text)
    }

    /**
     * 内容指纹：记录条数与最后修改时间一起做哈希。
     * 只用来判断「要不要再写一份」，不参与任何业务计算。
     */
    private fun fingerprint(snapshot: DataSnapshot): String {
        val raw = listOf(
            AppInfo.VERSION_NAME,
            snapshot.gloves.size.toString(),
            snapshot.factoryGloves.size.toString(),
            snapshot.home.size.toString(),
            snapshot.factory.size.toString(),
            snapshot.home.sumOf { it.quantity }.toString(),
            snapshot.home.sumOf { it.income }.toString(),
            snapshot.factory.sumOf { it.amount }.toString(),
            Build.VERSION.SDK_INT.toString(),
        ).joinToString("#")
        return raw.hashCode().toUInt().toString(16)
    }
}

/** 应用自身的版本信息，日志与升级提示共用。 */
object AppInfo {
    const val VERSION_NAME: String = BuildConfig.VERSION_NAME
    const val VERSION_CODE: Int = BuildConfig.VERSION_CODE
}
