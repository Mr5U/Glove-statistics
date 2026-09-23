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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * 手套库：保存「名称 + 默认单价」。
 *
 * 分两本独立的库：
 * - **家里手套**：家里的计件账用，单价进「手套收入」；
 * - **厂房手套**：厂房按件计酬用，单价只影响厂房账。
 *
 * 分成两本是为了避免「厂房的手套和家里的不是同一批、单价也不一样」时互相串价。
 * 两边的规则一样：改价只影响以后的新记录；删掉种类只让它不再出现在选择列表里，
 * 历史收入按当天的单价快照照常保留。
 */
@Composable
fun LibraryScreen(
    vm: WorkViewModel,
    modifier: Modifier = Modifier,
) {
    var showFactoryLibrary by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<GloveType?>(null) }
    var deleting by remember { mutableStateOf<GloveType?>(null) }

    val gloves = if (showFactoryLibrary) vm.factoryGloves else vm.gloves
    val libraryTitle = if (showFactoryLibrary) "厂房手套库" else "家里手套库"
    val color = if (showFactoryLibrary) FactoryOrange else HomeGreen

    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            ModeToggle(
                options = listOf("home" to "⌂ 家里手套", "factory" to "▦ 厂房手套"),
                selectedKey = if (showFactoryLibrary) "factory" else "home",
                onSelect = { showFactoryLibrary = it == "factory" },
            )
        }

        item {
            Hint(
                if (showFactoryLibrary) {
                    "厂房按件计酬时用的手套种类与单价。厂房记工的「按件计酬」模式会用到这份列表。"
                } else {
                    "家里做手套的种类与默认单价。记工时直接选，不用重复输入。"
                },
            )
        }

        item {
            Hint("同一本库里名称不能重复：新建或改名撞到已有的名称时会先弹提示，确认后才会把两条并成一条。")
        }

        if (gloves.isEmpty()) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("$libraryTitle 还是空的", fontWeight = FontWeight.Medium)
                        Hint(
                            if (showFactoryLibrary) "例如「22 公分绿牛」0.26 元 / 双。" else "例如「加绒劳保手套」1.20 元 / 双。",
                        )
                        Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("＋ 新建手套种类")
                        }
                    }
                }
            }
        } else {
            items(gloves, key = { it.name }) { glove ->
                val used = if (showFactoryLibrary) {
                    vm.factoryWork.count { it.gloveName == glove.name }
                } else {
                    vm.homeWork.count { it.gloveName == glove.name }
                }
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
            existing = gloves,
            title = if (showFactoryLibrary) "厂房手套种类" else "手套种类",
            onDismiss = { creating = false },
            onSave = { name, price ->
                if (showFactoryLibrary) {
                    vm.upsertFactoryGlove(previousName = null, name = name, price = price)
                } else {
                    vm.upsertGlove(previousName = null, name = name, price = price)
                }
                creating = false
            },
        )
    }

    editing?.let { target ->
        GloveDialog(
            initial = target,
            existing = gloves,
            title = if (showFactoryLibrary) "厂房手套种类" else "手套种类",
            onDismiss = { editing = null },
            onSave = { name, price ->
                if (showFactoryLibrary) {
                    vm.upsertFactoryGlove(previousName = target.name, name = name, price = price)
                } else {
                    vm.upsertGlove(previousName = target.name, name = name, price = price)
                }
                editing = null
            },
        )
    }

    deleting?.let { target ->
        val used = if (showFactoryLibrary) {
            vm.factoryWork.count { it.gloveName == target.name }
        } else {
            vm.homeWork.count { it.gloveName == target.name }
        }
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
                if (showFactoryLibrary) vm.deleteFactoryGlove(target.name) else vm.deleteGlove(target.name)
                deleting = null
            },
        )
    }
}

/**
 * 本月页：收入汇总 + 每日清单 + 导出入口。
 *
 * 手套收入和厂房收入分开列，最后给出两者相加的「全部劳动收入」；
 * 明细里的记录都按天合并过，所以看到的就是每天的真实合计。
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
    var confirmAutoBackup by remember { mutableStateOf(false) }

    val summary = vm.summaryOf(month)
    val tallies = summary.byGlove()
    val factoryTallies = summary.byFactoryGlove()

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
                    detail = if (summary.factoryQuantity > 0) {
                        "${summary.factoryDays} 天 · 计件 ${summary.factoryQuantity} 双 · 独立成账"
                    } else {
                        "${summary.factoryDays} 天 · 独立成账，不计入手套收入"
                    },
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

        // ------------------------------------------------ 每日清单
        item { SectionTitle("▤ 每日清单", "按天合并", MaterialTheme.colorScheme.primary) }
        if (summary.days.isEmpty()) {
            item { Hint("这个月还没有记录。") }
        } else {
            items(summary.days, key = { it.date }) { day ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(Fmt.monthDay(day.date), fontWeight = FontWeight.Medium)
                            Text(
                                buildString {
                                    if (day.workedAtHome) append("手套 ${day.quantity} 双")
                                    if (day.workedAtHome && day.workedAtFactory) append("　·　")
                                    if (day.workedAtFactory) append("厂房 ${Fmt.yuan(day.factoryIncome)}")
                                },
                                color = MutedInk,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(Fmt.yuan(day.totalIncome), fontWeight = FontWeight.Bold)
                            if (day.homeIncome > 0 && day.factoryIncome > 0) {
                                Text(
                                    "手套 ${Fmt.money(day.homeIncome)}",
                                    color = MutedInk,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }

        item { HorizontalDivider(Modifier.padding(top = 6.dp)) }

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
            items(tallies, key = { it.name }) { tally ->
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

        if (factoryTallies.isNotEmpty()) {
            item {
                SectionTitle("▦ 厂房计件种类", "按收入排序", FactoryOrange)
            }
            items(factoryTallies, key = { it.name }) { tally ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(tally.name, fontWeight = FontWeight.Medium)
                            Text(
                                "${tally.quantity} 双　·　均价 ${Fmt.yuan(tally.averagePrice)} / 双",
                                color = MutedInk,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(Fmt.yuan(tally.income), color = FactoryOrange, fontWeight = FontWeight.Bold)
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

        // ------------------------------------------------ 数据安全
        item { StoragePanel(vm = vm, onMessage = onMessage, onRestoreAuto = { confirmAutoBackup = true }) }
    }

    if (showExport) {
        ExportDialog(
            month = month,
            onDismiss = { showExport = false },
            onExportPng = { scope ->
                fileActions.exportPng(scope, summary)
                onMessage("正在生成图片，请选择保存位置…")
                showExport = false
            },
            onExportXml = {
                fileActions.exportXml(vm.snapshot())
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
            message = "恢复会用备份文件里的内容替换当前手机上的全部数据（家里手套库、厂房手套库、手套记录、厂房记录）。如果现在有还没备份的新记录，建议先导出一份 XML 再恢复。",
            confirmLabel = "选择备份文件",
            onDismiss = { confirmRestore = false },
            onConfirm = {
                confirmRestore = false
                fileActions.pickRestoreFile()
            },
        )
    }

    if (confirmAutoBackup) {
        val context = LocalContext.current
        val backups = AutoBackup.available(context)
        AlertDialog(
            onDismissRequest = { confirmAutoBackup = false },
            title = { Text("从自动备份恢复") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (backups.isEmpty()) {
                        Text("这台设备上还没有自动备份。")
                    } else {
                        Hint("自动备份是 App 在你每次记工后自己存的，最多保留 5 份。选择一份恢复会替换当前全部数据。")
                        backups.forEach { file ->
                            val time = file.name.removePrefix("glove-backup-").removeSuffix(".xml")
                            OutlinedButton(
                                onClick = {
                                    confirmAutoBackup = false
                                    applyRestore(
                                        AutoBackup.restore(context, file),
                                        onMessage,
                                        { snapshot -> vm.replaceAll(snapshot) },
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(time) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { confirmAutoBackup = false }) { Text("关闭") } },
        )
    }
}

/**
 * 「数据安全」区块。
 *
 * 它存在的意义是让「升级会不会丢数据」这件事变成看得见的东西：
 * 显示上次自动备份时间，可以一键立即备份，也可以直接用自动备份恢复。
 */
@Composable
private fun StoragePanel(
    vm: WorkViewModel,
    onMessage: (String) -> Unit,
    onRestoreAuto: () -> Unit,
) {
    var refresh by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val lastBackup = remember(refresh) { vm.lastBackupAt() }
    val backupCount = remember(refresh) { AutoBackup.available(context).size }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("数据安全", fontWeight = FontWeight.Bold)
            Hint(
                "数据只存在这台手机上，覆盖安装新版本不会清除。App 还会在每次记工后自动另存一份 XML 备份，最多保留 5 份。",
            )
            Text(
                "上次自动备份：" + (
                    lastBackup?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) ?: "还没有备份过"
                    ) + if (backupCount > 0) "（共 $backupCount 份）" else "",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val ok = vm.backupNow()
                        refresh++
                        onMessage(if (ok) "已生成备份" else "备份失败，请检查存储空间")
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("立即备份") }
                OutlinedButton(
                    onClick = onRestoreAuto,
                    modifier = Modifier.weight(1f),
                ) { Text("自动备份恢复") }
            }
        }
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
                Hint("同一天同一种手套已经合并成一行，并额外给出「每日汇总」表。选择要导出哪部分：")
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
