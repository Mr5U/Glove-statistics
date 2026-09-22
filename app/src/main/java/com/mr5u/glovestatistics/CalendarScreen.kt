@file:OptIn(ExperimentalMaterial3Api::class)

package com.mr5u.glovestatistics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth

private val WEEK_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 记录页：一整月的月历，把「干了什么活」直接标在日期上。
 *
 * 绿色圆点＝当天在家做手套，橙色圆点＝当天去了厂房，两个都有＝那天两头都干了；
 * 一个点都没有的日期就是「未干活」。点某一天可以在下方展开当天明细，并修改或删除。
 */
@Composable
fun CalendarScreen(
    vm: WorkViewModel,
    modifier: Modifier = Modifier,
) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selected by remember { mutableStateOf(LocalDate.now()) }
    var editingHome by remember { mutableStateOf<HomeWork?>(null) }
    var editingFactory by remember { mutableStateOf<FactoryWork?>(null) }
    var deletingHome by remember { mutableStateOf<HomeWork?>(null) }
    var deletingFactory by remember { mutableStateOf<FactoryWork?>(null) }

    val summary = vm.summaryOf(month)
    val byDate = summary.days.associateBy { it.date }
    val day = vm.summaryOfDate(selected)

    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            MonthSwitcher(
                month = month,
                // 换月时把选中日期一起挪过去，否则下方明细和日历高亮会对不上。
                onPrevious = {
                    month = month.minusMonths(1)
                    selected = month.atDay(1)
                },
                onNext = {
                    month = month.plusMonths(1)
                    selected = month.atDay(1)
                },
                onCurrent = {
                    month = YearMonth.now()
                    selected = LocalDate.now()
                },
            )
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        WEEK_LABELS.forEach { label ->
                            Text(
                                label,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center,
                                color = MutedInk,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    CalendarGrid(
                        month = month,
                        today = LocalDate.now(),
                        selected = selected,
                        byDate = byDate,
                        onSelect = { selected = it },
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Legend(HomeGreen, "家里手套")
                        Legend(FactoryOrange, "厂房工作")
                        Text("无圆点＝未干活", color = MutedInk, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(Fmt.date(selected), fontWeight = FontWeight.Bold)
                    if (!day.worked) {
                        Hint("这天没有记录，也就是「未干活」。")
                    } else {
                        Text(
                            "手套 ${Fmt.yuan(day.homeIncome)}　·　厂房 ${Fmt.yuan(day.factoryIncome)}　·　合计 ${Fmt.yuan(day.totalIncome)}",
                            color = MutedInk,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        item {
            SectionTitle("⌂ 当天手套记录", "${day.homeRecords.size} 笔", HomeGreen)
        }
        if (day.homeRecords.isEmpty()) {
            item { Hint("这天没有手套记录。回到「记工」页可以补录。") }
        } else {
            items(day.homeRecords) { record ->
                RecordRow(
                    title = record.gloveName,
                    subtitle = "${record.quantity} 双 × ${Fmt.yuan(record.unitPrice)}",
                    amount = record.income,
                    color = HomeGreen,
                    onEdit = { editingHome = record },
                    onDelete = { deletingHome = record },
                )
            }
        }

        item { HorizontalDivider(Modifier.padding(top = 4.dp)) }

        item {
            SectionTitle("▦ 当天厂房记录", "${day.factoryRecords.size} 笔", FactoryOrange)
        }
        if (day.factoryRecords.isEmpty()) {
            item { Hint("这天没有厂房记录。") }
        } else {
            items(day.factoryRecords) { record ->
                RecordRow(
                    title = record.note.ifBlank { "厂房工作" },
                    subtitle = "独立收入，不计入手套账",
                    amount = record.amount,
                    color = FactoryOrange,
                    onEdit = { editingFactory = record },
                    onDelete = { deletingFactory = record },
                )
            }
        }

        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("本月合计", fontWeight = FontWeight.Medium)
                    Text(
                        "${summary.workedDays} 天有记录　·　${Fmt.yuan(summary.totalIncome)}",
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }

    editingHome?.let { target ->
        EditHomeDialog(
            record = target,
            gloveNames = vm.gloves.map { it.name },
            onDismiss = { editingHome = null },
            onSave = { name, price, quantity ->
                vm.updateHome(target, name, price, quantity)
                editingHome = null
            },
        )
    }

    editingFactory?.let { target ->
        EditFactoryDialog(
            record = target,
            onDismiss = { editingFactory = null },
            onSave = { amount, note ->
                vm.updateFactory(target, amount, note)
                editingFactory = null
            },
        )
    }

    deletingHome?.let { target ->
        ConfirmDialog(
            title = "删除这条手套记录？",
            message = "${target.gloveName} × ${target.quantity} 双，收入 ${Fmt.yuan(target.income)}。删除后本月合计会同步变化。",
            confirmLabel = "删除",
            onDismiss = { deletingHome = null },
            onConfirm = {
                vm.deleteHome(target)
                deletingHome = null
            },
        )
    }

    deletingFactory?.let { target ->
        ConfirmDialog(
            title = "删除这条厂房记录？",
            message = "收入 ${Fmt.yuan(target.amount)}。删除后厂房收入合计会同步变化。",
            confirmLabel = "删除",
            onDismiss = { deletingFactory = null },
            onConfirm = {
                vm.deleteFactory(target)
                deletingFactory = null
            },
        )
    }
}

/**
 * 月历网格。第一行前面补空格，让 1 号落在正确的星期几；
 * 一行的最后一天之后也补空格，保证格子宽度均匀。
 */
@Composable
private fun CalendarGrid(
    month: YearMonth,
    today: LocalDate,
    selected: LocalDate,
    byDate: Map<String, DaySummary>,
    onSelect: (LocalDate) -> Unit,
) {
    val firstDay = month.atDay(1)
    // DayOfWeek 的 Monday 序号是 1，减 1 就得到「前面要空几格」。
    val leading = firstDay.dayOfWeek.value - 1
    val length = month.lengthOfMonth()
    val totalCells = ((leading + length + 6) / 7) * 7

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (rowStart in 0 until totalCells step 7) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (column in 0 until 7) {
                    val cellIndex = rowStart + column
                    val dayNumber = cellIndex - leading + 1
                    Box(Modifier.weight(1f).aspectRatio(1f)) {
                        if (dayNumber in 1..length) {
                            val date = month.atDay(dayNumber)
                            DayCell(
                                dayNumber = dayNumber,
                                isToday = date == today,
                                isSelected = date == selected,
                                summary = byDate[date.toString()],
                                onClick = { onSelect(date) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    dayNumber: Int,
    isToday: Boolean,
    isSelected: Boolean,
    summary: DaySummary?,
    onClick: () -> Unit,
) {
    val background = when {
        isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        isToday -> MaterialTheme.colorScheme.surfaceVariant
        else -> Color.Transparent
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            dayNumber.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (summary?.workedAtHome == true) Dot(HomeGreen)
            if (summary?.workedAtFactory == true) Dot(FactoryOrange)
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) = Row(
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Dot(color)
    Text(label, color = MutedInk, style = MaterialTheme.typography.labelMedium)
}
