package com.mr5u.glovestatistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.YearMonth

/**
 * 手套库：保存「名称 + 默认单价」。
 *
 * 这是整套记账能省事的关键——建过一次的手套，以后记工直接在下拉里选，
 * 单价自动带出。改名会同步更新已有记录里的手套名；删掉种类只会让它不再出现在
 * 选择列表里，历史收入按当天的单价快照保留，不会被改掉。
 */
@Composable
fun LibraryScreen(
    vm: WorkViewModel,
    modifier: Modifier = Modifier,
) {
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<GloveType?>(null) }
    var deleting by remember { mutableStateOf<GloveType?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Hint("保存名称与默认单价，记工时直接选，不用重复输入。改价只影响以后的新记录。")
        }

        if (vm.gloves.isEmpty()) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("还没有手套种类", fontWeight = FontWeight.Medium)
                        Hint("例如「加绒劳保手套」1.20 元 / 双。")
                        Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("＋ 新建手套种类")
                        }
                    }
                }
            }
        } else {
            items(vm.gloves) { glove ->
                val used = vm.homeWork.count { it.gloveName == glove.name }
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(glove.name, fontWeight = FontWeight.Medium)
                            Text(
                                "默认 ${Fmt.yuan(glove.unitPrice)} / 双　·　已用 $used 笔",
                                color = MutedInk,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { editing = glove }) { Text("改") }
                        TextButton(onClick = { deleting = glove }) { Text("删") }
                    }
                }
            }
            item {
                Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("＋ 新建手套种类")
                }
            }
        }
    }

    if (creating) {
        GloveDialog(
            initial = null,
            onDismiss = { creating = false },
            onSave = { name, price ->
                vm.upsertGlove(previousName = null, name = name, price = price)
                creating = false
            },
        )
    }

    editing?.let { target ->
        GloveDialog(
            initial = target,
            onDismiss = { editing = null },
            onSave = { name, price ->
                vm.upsertGlove(previousName = target.name, name = name, price = price)
                editing = null
            },
        )
    }

    deleting?.let { target ->
        val used = vm.homeWork.count { it.gloveName == target.name }
        ConfirmDialog(
            title = "删除「${target.name}」？",
            message = if (used > 0) {
                "已经有 $used 条记录用了这个种类。删除后它不再出现在选择列表里，但这 $used 条历史记录会保留，收入也按当时的单价照常统计。"
            } else {
                "还没有记录用过这个种类，可以放心删除。"
            },
            confirmLabel = "删除",
            onDismiss = { deleting = null },
            onConfirm = {
                vm.deleteGlove(target.name)
                deleting = null
            },
        )
    }
}

/**
 * 本月页：收入汇总 + 导出入口。
 *
 * 手套收入和厂房收入分开列，最后给出两者相加的「全部劳动收入」。
 * 导出 PNG 时可以只导手套账、只导厂房账，或者两张一起导。
 */
@Composable
fun SummaryScreen(
    vm: WorkViewModel,
    fileActions: FileActions,
    onMessage: (String) -> Unit,
    onOpenLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    var showExport by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }

    val summary = vm.summaryOf(month)
    val tallies = summary.byGlove()

    val snapshot = ExportSnapshot(summary, vm.gloves, vm.homeWork, vm.factoryWork)

    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            MonthSwitcher(
                month = month,
                onPrevious = { month = month.minusMonths(1) },
                onNext = { month = month.plusMonths(1) },
                onCurrent = { month = YearMonth.now() },
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard(
                    title = "家里手套收入",
                    amount = summary.homeIncome,
                    detail = "${summary.homeDays} 天 · 共 ${summary.quantity} 双 · ${summary.days.count { it.workedAtHome }} 个记工日",
                    color = HomeGreen,
                )
                StatCard(
                    title = "厂房工作收入",
                    amount = summary.factoryIncome,
                    detail = "${summary.factoryDays} 天 · 独立成账，不计入手套收入",
                    color = FactoryOrange,
                )
                TotalRow("本月全部劳动收入", summary.totalIncome)
            }
        }

        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("本月干活天数", fontWeight = FontWeight.Medium)
                    Text(
                        "${summary.workedDays} / ${month.lengthOfMonth()} 天",
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        item {
            SectionTitle("⌂ 各手套种类", "按收入排序", HomeGreen)
        }
        if (tallies.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("本月还没有手套记录", fontWeight = FontWeight.Medium)
                        Hint("先去「记工」页录一笔，或者先把手套种类建好。")
                        Button(onClick = onOpenLibrary, modifier = Modifier.fillMaxWidth()) {
                            Text("去手套库新建种类")
                        }
                    }
                }
            }
        } else {
            items(tallies) { tally ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(tally.name, fontWeight = FontWeight.Medium)
                            Text(
                                "${tally.quantity} 双",
                                color = MutedInk,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(Fmt.yuan(tally.income), color = HomeGreen, fontWeight = FontWeight.Bold)
                            val share = if (summary.homeIncome > 0) tally.income / summary.homeIncome * 100 else 0.0
                            Text(
                                "占手套收入 ${String.format(java.util.Locale.CHINA, "%.1f", share)}%",
                                color = MutedInk,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        item { HorizontalDivider(Modifier.padding(top = 6.dp)) }

        item {
            SectionTitle("导出与备份", "PNG / XML", MaterialTheme.colorScheme.primary)
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Hint("导出会打开系统文件选择器，你可以把文件存到手机本地、SD 卡或网盘，不需要额外授权。")
                    Button(onClick = { showExport = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("导出 / 备份")
                    }
                    TextButton(onClick = { confirmRestore = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("从 XML 备份恢复数据")
                    }
                }
            }
        }
    }

    if (showExport) {
        ExportDialog(
            month = month,
            onDismiss = { showExport = false },
            onExportPng = { scope ->
                fileActions.exportPng(scope, snapshot)
                onMessage("正在生成图片，请选择保存位置…")
                showExport = false
            },
            onExportXml = {
                fileActions.exportXml(snapshot)
                onMessage("正在生成备份文件，请选择保存位置…")
                showExport = false
            },
            onRestore = {
                showExport = false
                confirmRestore = true
            },
        )
    }

    if (confirmRestore) {
        ConfirmDialog(
            title = "从备份恢复？",
            message = "恢复会用备份文件里的内容替换当前手机上的全部数据（手套种类、手套记录、厂房记录）。如果现在有还没备份的新记录，建议先导出一份 XML 再恢复。",
            confirmLabel = "选择备份文件",
            onDismiss = { confirmRestore = false },
            onConfirm = {
                confirmRestore = false
                fileActions.pickRestoreFile()
            },
        )
    }
}

/** 导出方式选择：先选 PNG 还是 XML，选了 PNG 再选范围。 */
@Composable
private fun ExportDialog(
    month: YearMonth,
    onDismiss: () -> Unit,
    onExportPng: (ReportScope) -> Unit,
    onExportXml: () -> Unit,
    onRestore: () -> Unit,
) {
    var scope by remember { mutableStateOf(ReportScope.ALL) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出 ${Fmt.month(month)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("PNG 统计图 —— 适合发微信或存相册", fontWeight = FontWeight.Medium)
                Hint("包含汇总、逐笔明细和合计。选择要导出哪部分：")
                ReportScope.entries.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = scope == option, onClick = { scope = option })
                        Text(option.label)
                    }
                }
                Button(onClick = { onExportPng(scope) }, modifier = Modifier.fillMaxWidth()) {
                    Text("导出 PNG 图片")
                }

                HorizontalDivider()

                Text("XML 完整备份 —— 适合换手机或长期存档", fontWeight = FontWeight.Medium)
                Hint("包含全部手套种类、所有月份的手套记录与厂房记录，恢复时会原样还原。")
                Button(onClick = onExportXml, modifier = Modifier.fillMaxWidth()) {
                    Text("导出 XML 备份")
                }

                HorizontalDivider()

                TextButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) {
                    Text("从 XML 备份恢复数据")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
