package com.mr5u.glovestatistics

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * 这个文件刻意**不引用任何 Android / Compose 的类**。
 *
 * 数据模型、合并规则、统计口径全在这里，因此可以用本机的 Kotlin 编译器
 * 单独编译并跑单元测试（见 .tooling/domain-check.ps1）。
 * 持久化（SharedPreferences）在 DataStore.kt，界面状态在 Data.kt 的 ViewModel 里。
 *
 * v0.5.0 起厂房工作整块下线，这里只剩「家里手套」一本账。
 */

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

/**
 * 一天的记录。
 *
 * [homeRecords] 里的记录**已经按「同一天 + 同种类 + 同单价」合并过**，
 * 所以数量、收入、笔数与报表全部天然是合并后的结果。
 */
data class DaySummary(
    val date: String,
    val homeRecords: List<HomeWork>,
) {
    val homeIncome: Double get() = homeRecords.sumOf { it.income }

    /** 当天手套双数。 */
    val quantity: Int get() = homeRecords.sumOf { it.quantity }

    /** 当天是否有记录；没有就是「未干活」。 */
    val worked: Boolean get() = homeRecords.isNotEmpty()
}

/** 单个手套种类在一个期间内的合计。 */
data class GloveTally(
    val name: String,
    val quantity: Int,
    val income: Double,
)

/** 一个月的汇总结果。 */
data class MonthSummary(
    val month: YearMonth,
    val days: List<DaySummary>,
) {
    val homeIncome: Double get() = days.sumOf { it.homeIncome }
    val quantity: Int get() = days.sumOf { it.quantity }

    /** 本月有记录的天数，也就是干活天数。 */
    val workedDays: Int get() = days.count { it.worked }

    /** 本月各手套种类的数量与收入明细，按收入从高到低。 */
    fun byGlove(): List<GloveTally> =
        days.flatMap { it.homeRecords }
            .groupBy { it.gloveName }
            .map { (name, group) -> GloveTally(name, group.sumOf { it.quantity }, group.sumOf { it.income }) }
            .sortedByDescending { it.income }
}

// ------------------------------------------------------------------ 合并规则

/**
 * 同一天里重复的家里手套记录合并成一条：同种类 + 同单价 → 数量相加、收入相加。
 *
 * 单价也参与分组，是为了避免「同种类当天记了两个不同单价」时被硬合并成一条、
 * 结果算错钱。这种情况下两条都会保留。
 */
fun mergeHomeWork(records: List<HomeWork>): List<HomeWork> =
    records.groupBy { Triple(it.date, it.gloveName, it.unitPrice) }
        .map { (_, group) ->
            val first = group.first()
            first.copy(quantity = group.sumOf { it.quantity })
        }
        .sortedWith(compareBy({ it.date }, { it.gloveName }, { it.unitPrice }))

/** 按月份汇总（已按天合并）。日期字符串是 ISO 格式，可以直接前缀比较。 */
fun summarize(month: YearMonth, homeWork: List<HomeWork>): MonthSummary {
    val prefix = month.toString()
    val home = mergeHomeWork(homeWork.filter { it.date.startsWith(prefix) }).groupBy { it.date }
    val days = home.keys.sorted().map { date -> DaySummary(date, home[date].orEmpty()) }
    return MonthSummary(month, days)
}

/** 某一天的汇总（已合并）。 */
fun summarizeDay(date: LocalDate, homeWork: List<HomeWork>): DaySummary {
    val key = date.toString()
    return DaySummary(key, mergeHomeWork(homeWork.filter { it.date == key }))
}

/** 全量数据的只读快照，导出与备份统一走它。 */
data class DataSnapshot(
    val gloves: List<GloveType>,
    val home: List<HomeWork>,
) {
    val recordCount: Int get() = gloves.size + home.size
}

/** 一次覆盖升级的继承结果。 */
data class UpgradeReport(val previousVersion: Int, val inheritedRecords: Int)

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
