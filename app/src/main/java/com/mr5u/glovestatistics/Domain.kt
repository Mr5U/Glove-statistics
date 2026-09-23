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
 */

/** 手套种类：名称 + 默认单价（元 / 双）。家里和厂房各有一份独立的手套库。 */
data class GloveType(val name: String, val unitPrice: Double)

/** PNG 报表要包含哪一部分账。 */
enum class ReportScope(val label: String) {
    GLOVE("只导出手套账单"),
    FACTORY("只导出厂房收入"),
    ALL("导出全部劳动收入"),
}

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

/** 厂房一天的记法：按件计酬，或直接记一笔当天总收入。 */
enum class FactoryMode(val key: String, val label: String) {
    /** 计件：选手套种类 + 完成数量，收入 = 单价 × 数量，自动计算。 */
    PIECE("piece", "按件计酬"),

    /** 整笔：直接填当天总收入，用于打包价、临时工等没法按件算的情况。 */
    FLAT("flat", "当天总收入"),
    ;

    companion object {
        /** 未知 / 缺失的模式一律退回整笔，保证老数据读出来金额是对的。 */
        fun fromKey(value: String): FactoryMode = entries.firstOrNull { it.key == value } ?: FLAT
    }
}

/**
 * 厂房工作的一条记录。
 *
 * v0.4.0 起支持两种模式，用 [mode] 区分：
 * - [FactoryMode.PIECE]：填 [gloveName] + [quantity]，[amount] 由「单价 × 数量」自动算出并存下来。
 * - [FactoryMode.FLAT]：直接填 [amount]（[gloveName] 为空、[quantity] 为 0）。
 *
 * 两种模式都保存当天的单价快照，理由是以后调价不能改动已经记好的旧账。
 * 厂房账仍然独立于手套账，不计入「手套收入」。
 */
data class FactoryWork(
    val date: String,
    val amount: Double,
    val note: String,
    val gloveName: String = "",
    val quantity: Int = 0,
    val unitPrice: Double = 0.0,
    val mode: FactoryMode = FactoryMode.FLAT,
) {
    val isPiece: Boolean get() = mode == FactoryMode.PIECE
}

/**
 * 一天同时可能有家里手套和厂房工作，两类分开统计。
 *
 * [homeRecords] / [factoryRecords] 里的记录**已经按「同一天 + 同种类（+ 同单价）」合并过**，
 * 所以数量、收入、笔数、报表全部天然是合并后的结果。
 */
data class DaySummary(
    val date: String,
    val homeRecords: List<HomeWork>,
    val factoryRecords: List<FactoryWork>,
) {
    val homeIncome: Double get() = homeRecords.sumOf { it.income }
    val factoryIncome: Double get() = factoryRecords.sumOf { it.amount }

    /** 家里手套双数。 */
    val quantity: Int get() = homeRecords.sumOf { it.quantity }

    /** 厂房计件的双数；整笔记录的金额没有数量，不计入。 */
    val factoryQuantity: Int get() = factoryRecords.sumOf { it.quantity }

    val totalIncome: Double get() = homeIncome + factoryIncome
    val workedAtHome: Boolean get() = homeRecords.isNotEmpty()
    val workedAtFactory: Boolean get() = factoryRecords.isNotEmpty()
    val worked: Boolean get() = workedAtHome || workedAtFactory

    /** 当天是否在厂房做过计件的活（用于报表里判断要不要显示数量）。 */
    val hasFactoryPiece: Boolean get() = factoryRecords.any { it.isPiece }
}

/** 单个手套种类在一个期间内的合计。[unitPrice] 只在厂房均价展示时使用。 */
data class GloveTally(
    val name: String,
    val quantity: Int,
    val income: Double,
    val unitPrice: Double = 0.0,
) {
    /** 数量 > 0 时反推出的实际单价，用于报表展示。 */
    val averagePrice: Double get() = if (quantity > 0) income / quantity else unitPrice
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
    val factoryQuantity: Int get() = days.sumOf { it.factoryQuantity }
    val homeDays: Int get() = days.count { it.workedAtHome }
    val factoryDays: Int get() = days.count { it.workedAtFactory }
    val workedDays: Int get() = days.count { it.worked }

    /** 本月各手套种类的数量与收入明细，按收入从高到低。 */
    fun byGlove(): List<GloveTally> =
        days.flatMap { it.homeRecords }
            .groupBy { it.gloveName }
            .map { (name, group) -> GloveTally(name, group.sumOf { it.quantity }, group.sumOf { it.income }) }
            .sortedByDescending { it.income }

    /** 本月厂房各手套种类的合计，按收入从高到低；均价取该种类当月的加权均价。 */
    fun byFactoryGlove(): List<GloveTally> =
        days.flatMap { it.factoryRecords }
            .filter { it.isPiece }
            .groupBy { it.gloveName }
            .map { (name, group) ->
                val quantity = group.sumOf { it.quantity }
                val income = group.sumOf { it.amount }
                GloveTally(name, quantity, income, if (quantity > 0) income / quantity else 0.0)
            }
            .sortedByDescending { it.income }

    /** 厂房整笔收入的合计，用于把两类厂房账分开看。 */
    val factoryFlatIncome: Double
        get() = days.flatMap { it.factoryRecords }.filterNot { it.isPiece }.sumOf { it.amount }
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

/**
 * 同一天里重复的厂房计件记录合并成一条（同种类 + 同单价）。
 * 整笔记录按「金额 + 备注」合并，备注不同就各自保留（那通常是两件不同的活）。
 */
fun mergeFactoryWork(records: List<FactoryWork>): List<FactoryWork> =
    records.groupBy { FactoryGroupKey.of(it) }
        .map { (_, group) ->
            val first = group.first()
            when (first.mode) {
                FactoryMode.PIECE -> first.copy(quantity = group.sumOf { it.quantity })
                FactoryMode.FLAT -> first.copy(amount = group.sumOf { it.amount })
            }
        }
        .sortedWith(compareBy({ it.date }, { it.mode.key }, { it.gloveName }, { it.note }))

private data class FactoryGroupKey(
    val date: String,
    val mode: FactoryMode,
    val gloveName: String,
    val unitPrice: Double,
    val note: String,
    val amount: Double,
) {
    companion object {
        fun of(work: FactoryWork): FactoryGroupKey = when (work.mode) {
            // 计件：金额是算出来的，不参与分组，避免舍入差异把同一条拆开。
            FactoryMode.PIECE -> FactoryGroupKey(work.date, work.mode, work.gloveName, work.unitPrice, "", 0.0)
            FactoryMode.FLAT -> FactoryGroupKey(work.date, work.mode, "", 0.0, work.note, work.amount)
        }
    }
}

/** 按月份汇总（已按天合并）。日期字符串是 ISO 格式，可以直接前缀比较。 */
fun summarize(month: YearMonth, homeWork: List<HomeWork>, factoryWork: List<FactoryWork>): MonthSummary {
    val prefix = month.toString()
    val home = mergeHomeWork(homeWork.filter { it.date.startsWith(prefix) }).groupBy { it.date }
    val factory = mergeFactoryWork(factoryWork.filter { it.date.startsWith(prefix) }).groupBy { it.date }
    val days = (home.keys + factory.keys).sorted().map { date ->
        DaySummary(date, home[date].orEmpty(), factory[date].orEmpty())
    }
    return MonthSummary(month, days)
}

/** 某一天的汇总（已合并）。 */
fun summarizeDay(date: LocalDate, homeWork: List<HomeWork>, factoryWork: List<FactoryWork>): DaySummary {
    val key = date.toString()
    return DaySummary(
        key,
        mergeHomeWork(homeWork.filter { it.date == key }),
        mergeFactoryWork(factoryWork.filter { it.date == key }),
    )
}

/** 全量数据的只读快照，导出与备份统一走它。 */
data class DataSnapshot(
    val gloves: List<GloveType>,
    val factoryGloves: List<GloveType>,
    val home: List<HomeWork>,
    val factory: List<FactoryWork>,
) {
    val recordCount: Int get() = gloves.size + factoryGloves.size + home.size + factory.size
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
