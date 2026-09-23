package com.mr5u.glovestatistics

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import java.time.LocalDate
import java.time.YearMonth

/*
 * 这个文件只放**依赖 Android / Compose** 的部分：本地存储的 ViewModel。
 *
 * 数据模型、合并规则、统计口径都在 Domain.kt（纯 Kotlin，可离线单测），
 * SharedPreferences 的读写格式在 DataStore.kt。
 */

/** 记工与统计数据。所有写操作立即落盘，并顺手刷新自动备份。 */
class WorkViewModel(application: Application) : AndroidViewModel(application) {

    private val store = LocalStore(application)

    var gloves by mutableStateOf(store.gloves())
        private set
    var homeWork by mutableStateOf(store.homeWork())
        private set

    /**
     * 覆盖升级后要提示给用户的信息：老版本号 + 继承下来的记录条数。
     * 只在「安装包版本号变了」且确实读到了数据时才有值，第二次打开就不再提示。
     */
    var upgradeReport by mutableStateOf(readUpgradeReport())
        private set

    private fun persist() = store.save(gloves, homeWork)

    /** 更新数据后落盘，并顺手刷新自动备份。 */
    private fun persistAndBackup() {
        persist()
        AutoBackup.onDataChanged(getApplication(), this)
    }

    fun gloveByName(name: String): GloveType? = gloves.firstOrNull { it.name == name }

    /** 新建或覆盖同名手套的默认单价。改名时同步更新已有记录里的手套名。 */
    fun upsertGlove(previousName: String?, name: String, price: Double) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || price < 0) return
        gloves = (gloves.filterNot { it.name == trimmed || it.name == previousName } + GloveType(trimmed, price))
            .sortedBy { it.name }
        if (previousName != null && previousName != trimmed) {
            homeWork = homeWork.map {
                if (it.gloveName == previousName) it.copy(gloveName = trimmed) else it
            }
        }
        persistAndBackup()
    }

    /** 删除手套种类。历史记录保留（收入按当时单价快照计算），只是不再出现在选择列表里。 */
    fun deleteGlove(name: String) {
        gloves = gloves.filterNot { it.name == name }
        persistAndBackup()
    }

    fun addHome(date: LocalDate, glove: GloveType, quantity: Int) {
        if (quantity <= 0) return
        homeWork = homeWork + HomeWork(date.toString(), glove.name, glove.unitPrice, quantity)
        persistAndBackup()
    }

    fun updateHome(target: HomeWork, gloveName: String, unitPrice: Double, quantity: Int) {
        if (quantity <= 0) return
        val index = homeWork.indexOf(target)
        if (index < 0) return
        homeWork = homeWork.toMutableList().also {
            it[index] = it[index].copy(gloveName = gloveName, unitPrice = unitPrice, quantity = quantity)
        }
        persistAndBackup()
    }

    fun deleteHome(target: HomeWork) {
        val index = homeWork.indexOf(target)
        if (index < 0) return
        homeWork = homeWork.toMutableList().also { it.removeAt(index) }
        persistAndBackup()
    }

    /** 覆盖式恢复：用备份文件里的全部数据替换当前数据。 */
    fun replaceAll(snapshot: DataSnapshot) {
        gloves = snapshot.gloves.sortedBy { it.name }
        homeWork = snapshot.home.sortedBy { it.date }
        persistAndBackup()
    }

    /** 按月份汇总，记录已按天合并。 */
    fun summaryOf(month: YearMonth): MonthSummary = summarize(month, homeWork)

    /** 某一天的汇总（已合并），用于记工页与月历点选后展开当天明细。 */
    fun summaryOfDate(date: LocalDate): DaySummary = summarizeDay(date, homeWork)

    /** 当前的完整数据快照，供导出 / 自动备份使用。 */
    fun snapshot(): DataSnapshot = DataSnapshot(gloves, homeWork)

    // ------------------------------------------------------------ 数据安全

    /** 上次自动备份时间。 */
    fun lastBackupAt(): java.time.LocalDateTime? = AutoBackup.lastBackupAt(getApplication())

    /** 立即备份一份。 */
    fun backupNow(): Boolean = AutoBackup.backupNow(getApplication(), snapshot())

    fun acknowledgeUpgrade() {
        upgradeReport = null
    }

    /**
     * 读一次「上次运行时的版本号」。
     *
     * 数据本来就原样躺在 SharedPreferences 里，这里不做任何搬迁，
     * 只是把「读到了多少条」明确告诉用户，免得升级之后自己提心吊胆。
     */
    private fun readUpgradeReport(): UpgradeReport? {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val previous = prefs.getInt(KEY_LAST_RUN_VERSION, 0)
        val current = BuildConfig.VERSION_CODE
        if (previous >= current) return null

        prefs.edit().putInt(KEY_LAST_RUN_VERSION, current).apply()
        if (previous == 0) return null // 全新安装，没有什么可继承的

        val count = snapshot().recordCount
        if (count == 0) return null
        return UpgradeReport(previousVersion = previous, inheritedRecords = count)
    }

    private companion object {
        const val PREFS_NAME = LocalStore.PREFS_NAME
        const val KEY_LAST_RUN_VERSION = "lastRunVersionCode"
    }
}
