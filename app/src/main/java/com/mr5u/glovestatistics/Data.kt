package com.mr5u.glovestatistics

import android.content.Context
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 手套种类：名称 + 默认单价（元 / 双）。 */
data class GloveType(val name: String, val unitPrice: Double)

/**
 * 家里做手套的一条计件记录。
 *
 * [unitPrice] 保存的是**当天实际单价快照**，而不是手套库里的现价。
 * 这样以后再调整手套库单价，也不会把过去已经算好的收入改掉。
 */
data class HomeWork(
    val date: String,
    val gloveName: String,
    val unitPrice: Double,
    val quantity: Int,
) {
    val income: Double get() = unitPrice * quantity
}

/** 厂房工作的一条记录：当天收入 + 可选备注。独立于手套账。 */
data class FactoryWork(val date: String, val amount: Double, val note: String)

/** 一天同时可能有家里手套和厂房工作，两类分开统计。 */
data class DaySummary(
    val date: String,
    val homeRecords: List<HomeWork>,
    val factoryRecords: List<FactoryWork>,
) {
    val homeIncome: Double get() = homeRecords.sumOf { it.income }
    val factoryIncome: Double get() = factoryRecords.sumOf { it.amount }
    val quantity: Int get() = homeRecords.sumOf { it.quantity }
    val totalIncome: Double get() = homeIncome + factoryIncome
    val workedAtHome: Boolean get() = homeRecords.isNotEmpty()
    val workedAtFactory: Boolean get() = factoryRecords.isNotEmpty()
    val worked: Boolean get() = workedAtHome || workedAtFactory
}

/** 一个月的汇总结果。 */
data class MonthSummary(
    val month: YearMonth,
    val days: List<DaySummary>,
) {
    val homeIncome: Double get() = days.sumOf { it.homeIncome }
    val factoryIncome: Double get() = days.sumOf { it.factoryIncome }
    val totalIncome: Double get() = homeIncome + factoryIncome
    val quantity: Int get() = days.sumOf { it.quantity }
    val homeDays: Int get() = days.count { it.workedAtHome }
    val factoryDays: Int get() = days.count { it.workedAtFactory }
    val workedDays: Int get() = days.count { it.worked }

    /** 本月各手套种类的数量与收入明细，按收入从高到低。 */
    fun byGlove(): List<GloveTally> = days
        .flatMap { it.homeRecords }
        .groupBy { it.gloveName }
        .map { (name, records) ->
            GloveTally(name, records.sumOf { it.quantity }, records.sumOf { it.income })
        }
        .sortedByDescending { it.income }
}

/** 单个手套种类在一个月内的合计。 */
data class GloveTally(val name: String, val quantity: Int, val income: Double)

/**
 * 本地离线存储。
 *
 * 全部数据放在 SharedPreferences 的纯文本里，一行一条记录，字段用 `|` 分隔；
 * 可能含有分隔符或换行的文本字段先做 Base64 再存。
 * 存储格式必须与 [Backup] 的 XML 导出保持一致，方便导出 / 恢复。
 */
class LocalStore(context: Context) {

    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun gloves(): List<GloveType> = decodeLines(preferences.getString(KEY_GLOVES, null))
        .mapNotNull { line ->
            val parts = line.split(SEPARATOR)
            if (parts.size != 2) return@mapNotNull null
            val name = decodeText(parts[0]) ?: return@mapNotNull null
            val price = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            GloveType(name, price)
        }
        .sortedBy { it.name }

    fun homeWork(): List<HomeWork> = decodeLines(preferences.getString(KEY_HOME, null))
        .mapNotNull { line ->
            val parts = line.split(SEPARATOR)
            if (parts.size != 4) return@mapNotNull null
            val gloveName = decodeText(parts[1]) ?: return@mapNotNull null
            val price = parts[2].toDoubleOrNull() ?: return@mapNotNull null
            val quantity = parts[3].toIntOrNull() ?: return@mapNotNull null
            HomeWork(parts[0], gloveName, price, quantity)
        }
        .sortedBy { it.date }

    fun factoryWork(): List<FactoryWork> = decodeLines(preferences.getString(KEY_FACTORY, null))
        .mapNotNull { line ->
            val parts = line.split(SEPARATOR)
            if (parts.size != 3) return@mapNotNull null
            val amount = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            val note = decodeText(parts[2]) ?: return@mapNotNull null
            FactoryWork(parts[0], amount, note)
        }
        .sortedBy { it.date }

    fun save(gloves: List<GloveType>, home: List<HomeWork>, factory: List<FactoryWork>) {
        preferences.edit()
            .putString(
                KEY_GLOVES,
                gloves.joinToString("\n") { "${encodeText(it.name)}$SEPARATOR${it.unitPrice}" },
            )
            .putString(
                KEY_HOME,
                home.joinToString("\n") {
                    "${it.date}$SEPARATOR${encodeText(it.gloveName)}$SEPARATOR${it.unitPrice}$SEPARATOR${it.quantity}"
                },
            )
            .putString(
                KEY_FACTORY,
                factory.joinToString("\n") {
                    "${it.date}$SEPARATOR${it.amount}$SEPARATOR${encodeText(it.note)}"
                },
            )
            .apply()
    }

    private fun decodeLines(raw: String?): List<String> =
        raw?.lines()?.filter { it.isNotBlank() } ?: emptyList()

    private fun encodeText(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun decodeText(value: String): String? = runCatching {
        String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        const val PREFS_NAME = "glove_statistics"
        const val SEPARATOR = "|"
        const val KEY_GLOVES = "gloves"
        const val KEY_HOME = "home"
        const val KEY_FACTORY = "factory"
    }
}

/** 记工与统计数据。所有写操作立即落盘。 */
class WorkViewModel(application: android.app.Application) : AndroidViewModel(application) {

    private val store = LocalStore(application)

    var gloves by mutableStateOf(store.gloves())
        private set
    var homeWork by mutableStateOf(store.homeWork())
        private set
    var factoryWork by mutableStateOf(store.factoryWork())
        private set

    private fun persist() = store.save(gloves, homeWork, factoryWork)

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
        persist()
    }

    /** 删除手套种类。历史记录保留（收入按当时单价快照计算），只是不再出现在选择列表里。 */
    fun deleteGlove(name: String) {
        gloves = gloves.filterNot { it.name == name }
        persist()
    }

    fun addHome(date: LocalDate, glove: GloveType, quantity: Int) {
        if (quantity <= 0) return
        homeWork = homeWork + HomeWork(date.toString(), glove.name, glove.unitPrice, quantity)
        persist()
    }

    fun updateHome(target: HomeWork, gloveName: String, unitPrice: Double, quantity: Int) {
        if (quantity <= 0) return
        homeWork = homeWork.map {
            if (it == target) it.copy(gloveName = gloveName, unitPrice = unitPrice, quantity = quantity) else it
        }
        persist()
    }

    fun deleteHome(target: HomeWork) {
        homeWork = homeWork.filterNot { it == target }
        persist()
    }

    fun addFactory(date: LocalDate, amount: Double, note: String) {
        if (amount <= 0) return
        factoryWork = factoryWork + FactoryWork(date.toString(), amount, note.trim())
        persist()
    }

    fun updateFactory(target: FactoryWork, amount: Double, note: String) {
        if (amount <= 0) return
        factoryWork = factoryWork.map {
            if (it == target) it.copy(amount = amount, note = note.trim()) else it
        }
        persist()
    }

    fun deleteFactory(target: FactoryWork) {
        factoryWork = factoryWork.filterNot { it == target }
        persist()
    }

    /** 覆盖式恢复：用备份文件里的全部数据替换当前数据。 */
    fun replaceAll(gloves: List<GloveType>, home: List<HomeWork>, factory: List<FactoryWork>) {
        this.gloves = gloves.sortedBy { it.name }
        this.homeWork = home.sortedBy { it.date }
        this.factoryWork = factory.sortedBy { it.date }
        persist()
    }

    /** 按月份汇总。以传入月份的 [YearMonth] 为准，日期字符串是 ISO 格式，可直接前缀比较。 */
    fun summaryOf(month: YearMonth): MonthSummary {
        val prefix = month.toString()
        val home = homeWork.filter { it.date.startsWith(prefix) }.groupBy { it.date }
        val factory = factoryWork.filter { it.date.startsWith(prefix) }.groupBy { it.date }
        val days = (home.keys + factory.keys).sorted().map { date ->
            DaySummary(date, home[date].orEmpty(), factory[date].orEmpty())
        }
        return MonthSummary(month, days)
    }

    /** 某一天的汇总，用于月历点选后展开当天明细。 */
    fun summaryOfDate(date: LocalDate): DaySummary {
        val key = date.toString()
        return DaySummary(
            key,
            homeWork.filter { it.date == key },
            factoryWork.filter { it.date == key },
        )
    }
}

/** 日期与金额格式化工具，全应用共用。 */
object Fmt {
    private val DATE_PATTERN = DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日", Locale.CHINA)
    private val MONTH_PATTERN = DateTimeFormatter.ofPattern("yyyy 年 M 月", Locale.CHINA)
    private val MONTH_DAY_PATTERN = DateTimeFormatter.ofPattern("M 月 d 日", Locale.CHINA)

    fun date(value: LocalDate): String = value.format(DATE_PATTERN)
    fun month(value: YearMonth): String = value.format(MONTH_PATTERN)
    fun monthDay(isoDate: String): String = runCatching {
        LocalDate.parse(isoDate).format(MONTH_DAY_PATTERN)
    }.getOrDefault(isoDate)

    fun money(value: Double): String = String.format(Locale.CHINA, "%.2f", value)
    fun yuan(value: Double): String = "¥${money(value)}"
}
