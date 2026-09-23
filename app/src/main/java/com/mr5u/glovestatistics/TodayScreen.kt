@file:OptIn(ExperimentalMaterial3Api::class)

package com.mr5u.glovestatistics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 记工首页：先选日期，再分别录入「家里手套」和「厂房工作」。
 *
 * 两类账互不影响，同一天可以只记一类，也可以两类都记。
 * 厂房有两种记法，可以随手切换：
 * - **按件计酬**：选厂房手套种类 → 自动带出单价 → 填完成数量，当天收入自动算；
 * - **当天总收入**：直接填一笔金额，用于打包价、临时工。
 *
 * 下方列出的「当天已记」都是**按天合并后**的结果：同一天同一种手套只显示一行，
 * 数量与收入是当天合计。改和删在「记录」页。
 */
@Composable
fun TodayScreen(
    vm: WorkViewModel,
    onOpenCalendar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var date by remember { mutableStateOf(LocalDate.now()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showAddGlove by remember { mutableStateOf(false) }
    var showAddFactoryGlove by remember { mutableStateOf(false) }

    var selectedGlove by remember { mutableStateOf<GloveType?>(null) }
    var quantity by remember { mutableStateOf("") }

    var factoryMode by remember { mutableStateOf(FactoryMode.PIECE) }
    var selectedFactoryGlove by remember { mutableStateOf<GloveType?>(null) }
    var factoryQuantity by remember { mutableStateOf("") }
    var factoryAmount by remember { mutableStateOf("") }
    var factoryNote by remember { mutableStateOf("") }

    // 手套库被改动（新增/改名/删除）后，保持当前选择指向最新的一条。
    val currentGlove = selectedGlove?.let { vm.gloveByName(it.name) }
    val currentFactoryGlove = selectedFactoryGlove?.let { vm.factoryGloveByName(it.name) }

    val day = vm.summaryOfDate(date)

    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            DateHeader(
                date = date,
                isToday = date == LocalDate.now(),
                onPick = { showDatePicker = true },
                onToday = { date = LocalDate.now() },
            )
        }

        item {
            SectionTitle(
                title = "⌂ 家里手套",
                caption = "计入手套收入",
                color = HomeGreen,
            )
        }

        if (vm.gloves.isEmpty()) {
            item { EmptyGloveCard(onCreate = { showAddGlove = true }) }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlovePicker(
                        gloves = vm.gloves,
                        selected = currentGlove,
                        color = HomeGreen,
                        emptyLabel = "点这里展开手套库",
                        onSelect = { selectedGlove = it },
                        onCreate = { showAddGlove = true },
                    )

                    val picked = currentGlove
                    if (picked != null) {
                        val count = quantity.toIntOrNull() ?: 0
                        Text(
                            "单价 ${Fmt.yuan(picked.unitPrice)} / 双　·　预计收入 ${Fmt.yuan(picked.unitPrice * count)}",
                            color = HomeGreen,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    OutlinedTextField(
                        value = quantity,
                        onValueChange = { quantity = it.filter(Char::isDigit) },
                        label = { Text("完成数量（双）") },
                        singleLine = true,
                        keyboardOptions = QuantityKeys,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Button(
                        onClick = {
                            val picked = currentGlove ?: return@Button
                            val count = quantity.toIntOrNull() ?: return@Button
                            if (count > 0) {
                                vm.addHome(date, picked, count)
                                quantity = ""
                            }
                        },
                        enabled = currentGlove != null && (quantity.toIntOrNull() ?: 0) > 0,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = HomeGreen),
                    ) { Text("保存手套记录") }
                }
            }
        }

        if (day.homeRecords.isNotEmpty()) {
            item { SavedHeader("当天已记的手套（已按种类合并）", day.homeRecords.size) }
            items(day.homeRecords) { record ->
                MiniRow(
                    left = "${record.gloveName} × ${record.quantity} 双 × ${Fmt.yuan(record.unitPrice)}",
                    right = Fmt.yuan(record.income),
                    color = HomeGreen,
                )
            }
        }

        item { HorizontalDivider(Modifier.padding(top = 6.dp)) }

        item {
            SectionTitle(
                title = "▦ 厂房工作",
                caption = "单独成账，不进手套收入",
                color = FactoryOrange,
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeToggle(
                    options = FactoryMode.entries.map { it.key to it.label },
                    selectedKey = factoryMode.key,
                    onSelect = { factoryMode = FactoryMode.fromKey(it) },
                )

                when (factoryMode) {
                    FactoryMode.PIECE -> {
                        if (vm.factoryGloves.isEmpty()) {
                            Card(
                                Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("厂房手套库还是空的", fontWeight = FontWeight.Medium)
                                    Hint("先建一个厂房用的手套种类（名称 + 单价），以后记工直接选。")
                                    Button(
                                        onClick = { showAddFactoryGlove = true },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(containerColor = FactoryOrange),
                                    ) { Text("＋ 新建厂房手套种类") }
                                }
                            }
                        } else {
                            GlovePicker(
                                gloves = vm.factoryGloves,
                                selected = currentFactoryGlove,
                                color = FactoryOrange,
                                emptyLabel = "点这里展开厂房手套库",
                                onSelect = { selectedFactoryGlove = it },
                                onCreate = { showAddFactoryGlove = true },
                            )
                        }

                        val picked = currentFactoryGlove
                        if (picked != null) {
                            val count = factoryQuantity.toIntOrNull() ?: 0
                            Text(
                                "单价 ${Fmt.yuan(picked.unitPrice)} / 双　·　当天收入自动算 ${Fmt.yuan(picked.unitPrice * count)}",
                                color = FactoryOrange,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }

                        OutlinedTextField(
                            value = factoryQuantity,
                            onValueChange = { factoryQuantity = it.filter(Char::isDigit) },
                            label = { Text("完成数量（双）") },
                            singleLine = true,
                            keyboardOptions = QuantityKeys,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    FactoryMode.FLAT -> {
                        OutlinedTextField(
                            value = factoryAmount,
                            onValueChange = { input -> factoryAmount = input.filter { it.isDigit() || it == '.' } },
                            label = { Text("厂房当天收入（元）") },
                            singleLine = true,
                            keyboardOptions = MoneyKeys,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                OutlinedTextField(
                    value = factoryNote,
                    onValueChange = { factoryNote = it },
                    label = { Text("工作备注（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Button(
                    onClick = {
                        when (factoryMode) {
                            FactoryMode.PIECE -> {
                                val picked = currentFactoryGlove ?: return@Button
                                val count = factoryQuantity.toIntOrNull() ?: return@Button
                                if (count > 0) {
                                    vm.addFactoryPiece(date, picked, count, factoryNote)
                                    factoryQuantity = ""
                                    factoryNote = ""
                                }
                            }

                            FactoryMode.FLAT -> {
                                val amount = factoryAmount.toDoubleOrNull() ?: return@Button
                                if (amount > 0) {
                                    vm.addFactoryFlat(date, amount, factoryNote)
                                    factoryAmount = ""
                                    factoryNote = ""
                                }
                            }
                        }
                    },
                    enabled = when (factoryMode) {
                        FactoryMode.PIECE -> currentFactoryGlove != null && (factoryQuantity.toIntOrNull() ?: 0) > 0
                        FactoryMode.FLAT -> (factoryAmount.toDoubleOrNull() ?: 0.0) > 0
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = FactoryOrange),
                ) {
                    Text(if (factoryMode == FactoryMode.PIECE) "保存厂房计件记录" else "保存厂房收入")
                }
            }
        }

        if (day.factoryRecords.isNotEmpty()) {
            item { SavedHeader("当天已记的厂房（已合并）", day.factoryRecords.size) }
            items(day.factoryRecords) { record ->
                MiniRow(
                    left = factoryLine(record),
                    right = Fmt.yuan(record.amount),
                    color = FactoryOrange,
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val state = when {
                    day.workedAtHome && day.workedAtFactory -> "当天：家里手套 + 厂房都干了"
                    day.workedAtHome -> "当天：只在家里做手套"
                    day.workedAtFactory -> "当天：只去了厂房"
                    else -> "当天还没有记录（未干活）"
                }
                val totals = if (day.worked) {
                    "手套 ${Fmt.yuan(day.homeIncome)}　·　厂房 ${Fmt.yuan(day.factoryIncome)}　·　合计 ${Fmt.yuan(day.totalIncome)}"
                } else {
                    "选好上面任意一项，点保存即可开始记录。"
                }
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(state, fontWeight = FontWeight.Medium)
                        Text(totals, color = MutedInk, style = MaterialTheme.typography.bodySmall)
                        if (day.worked) {
                            TextButton(onClick = onOpenCalendar) { Text("去记录页修改或删除 →") }
                        }
                    }
                }
            }
        }
    }

    if (showAddGlove) {
        GloveDialog(
            initial = null,
            existing = vm.gloves,
            title = "手套种类",
            onDismiss = { showAddGlove = false },
            onSave = { name, price ->
                vm.upsertGlove(previousName = null, name = name, price = price)
                selectedGlove = vm.gloveByName(name)
                showAddGlove = false
            },
        )
    }

    if (showAddFactoryGlove) {
        GloveDialog(
            initial = null,
            existing = vm.factoryGloves,
            title = "厂房手套种类",
            onDismiss = { showAddFactoryGlove = false },
            onSave = { name, price ->
                vm.upsertFactoryGlove(previousName = null, name = name, price = price)
                selectedFactoryGlove = vm.factoryGloveByName(name)
                showAddFactoryGlove = false
            },
        )
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        date = Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } },
        ) { DatePicker(state = pickerState) }
    }
}

/** 厂房记录在列表里的一行说明。计件显示种类与数量，整笔显示备注。 */
fun factoryLine(record: FactoryWork): String = when {
    record.isPiece && record.note.isNotBlank() ->
        "${record.gloveName} × ${record.quantity} 双 × ${Fmt.yuan(record.unitPrice)}　·　${record.note}"

    record.isPiece -> "${record.gloveName} × ${record.quantity} 双 × ${Fmt.yuan(record.unitPrice)}"
    record.note.isNotBlank() -> "整笔收入　·　${record.note}"
    else -> "整笔收入"
}

/** 日期栏：显示当前记录的日期，可切换、可一键回到今天。 */
@Composable
private fun DateHeader(
    date: LocalDate,
    isToday: Boolean,
    onPick: () -> Unit,
    onToday: () -> Unit,
) = Card(Modifier.fillMaxWidth()) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("记录日期", color = MutedInk, style = MaterialTheme.typography.labelMedium)
            Text(
                Fmt.date(date) + if (isToday) "（今天）" else "",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
            if (!isToday) {
                Text(
                    "正在补录过去的日期，保存后会出现在记录页",
                    color = FactoryOrange,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        TextButton(onClick = onPick) { Text("换日期") }
        if (!isToday) TextButton(onClick = onToday) { Text("今天") }
    }
}

@Composable
private fun EmptyGloveCard(onCreate: () -> Unit) = Card(
    Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("手套库还是空的", fontWeight = FontWeight.Medium)
        Hint("先建一个手套种类，填上名称和单价；以后每次记工直接选，不用再输一遍。")
        Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text("＋ 新建手套种类") }
    }
}

@Composable
private fun SavedHeader(title: String, count: Int) = Row(
    Modifier.fillMaxWidth().padding(top = 4.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
) {
    Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
    Text("$count 笔", color = MutedInk, style = MaterialTheme.typography.labelMedium)
}

/** 当天记录的一行紧凑展示（只读，改删去记录页）。 */
@Composable
private fun MiniRow(left: String, right: String, color: Color) = Row(
    Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
        .padding(horizontal = 12.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(left, modifier = Modifier.weight(1f))
    Text(right, color = color, fontWeight = FontWeight.Bold)
}
