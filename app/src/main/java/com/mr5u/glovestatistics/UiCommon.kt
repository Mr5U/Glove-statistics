package com.mr5u.glovestatistics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** 手套账的识别色。 */
val HomeGreen = Color(0xFF087B51)

/** 次要信息色。 */
val MutedInk = Color(0xFF6B7280)

/** 只允许整数输入（数量）。 */
val QuantityKeys = KeyboardOptions(keyboardType = KeyboardType.Number)

/** 允许小数输入（金额、单价）。 */
val MoneyKeys = KeyboardOptions(keyboardType = KeyboardType.Decimal)

/** 段落标题：左侧主标题，右侧一句说明。 */
@Composable
fun SectionTitle(
    title: String,
    caption: String,
    color: Color,
    modifier: Modifier = Modifier,
) = Row(
    modifier = modifier.fillMaxWidth().padding(top = 4.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    Text(caption, color = color, style = MaterialTheme.typography.labelMedium)
}

/** 一张小结卡片：标题 + 金额 + 副说明。 */
@Composable
fun StatCard(
    title: String,
    amount: Double,
    detail: String,
    color: Color,
    modifier: Modifier = Modifier,
) = Card(
    modifier = modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = color),
) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = Color.White.copy(alpha = 0.92f), style = MaterialTheme.typography.labelLarge)
        Text(
            Fmt.yuan(amount),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(detail, color = Color.White.copy(alpha = 0.86f), style = MaterialTheme.typography.bodySmall)
    }
}

/** 日历格子上表示“当天有记录”的小圆点。 */
@Composable
fun Dot(color: Color) = Box(Modifier.size(7.dp).background(color, CircleShape))

/** 提示当前没有记录时的一行浅色文字。 */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) =
    Text(text, color = MutedInk, style = MaterialTheme.typography.bodyMedium, modifier = modifier)

/** 已保存记录的一行：左侧内容，右侧操作按钮。 */
@Composable
fun RecordRow(
    title: String,
    subtitle: String,
    amount: Double,
    color: Color,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) = Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, color = MutedInk, style = MaterialTheme.typography.bodySmall)
        }
        Text(Fmt.yuan(amount), color = color, fontWeight = FontWeight.Bold)
        TextButton(onClick = onEdit) { Text("改") }
        TextButton(onClick = onDelete) { Text("删") }
    }
}

/** 需要用户确认的破坏性操作。 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = { Text(message) },
    confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
)

/** 月份切换条，记录页和本月页共用。 */
@Composable
fun MonthSwitcher(
    month: java.time.YearMonth,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCurrent: () -> Unit,
    modifier: Modifier = Modifier,
) = Card(modifier.fillMaxWidth()) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onPrevious) { Text("◀ 上月") }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Fmt.month(month), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            if (month != java.time.YearMonth.now()) {
                TextButton(onClick = onCurrent) {
                    Text("回到本月", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        TextButton(onClick = onNext) { Text("下月 ▶") }
    }
}

/**
 * 手套种类选择器：点卡片弹出下拉，底部固定一条「新建手套种类」入口。
 *
 * 颜色、标题由调用方给，方便在记工页与手套库页复用。
 */
@Composable
fun GlovePicker(
    gloves: List<GloveType>,
    selected: GloveType?,
    color: Color,
    emptyLabel: String,
    onSelect: (GloveType) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    // 刻意不用 ExposedDropdownMenuBox：
    // 它在 material3 里是 @ExperimentalMaterial3Api，且 Modifier.menuAnchor() 这类 API
    // 随版本反复改签名（1.4.0 起 menuAnchor 已要求显式传 ExposedDropdownMenuAnchorType，
    // 旧的 ExposedDropdownMenu 顶层函数也已被改成 scope 成员函数）。
    // 这里改成最简单的「点卡片 → 弹 DropdownMenu」，用到的都是稳定 API，不受这些变动影响。
    Box(modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true },
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("选择手套种类", color = MutedInk, style = MaterialTheme.typography.labelMedium)
                    if (selected == null) {
                        Text(emptyLabel, color = MutedInk)
                    } else {
                        Text(selected.name, fontWeight = FontWeight.Medium)
                        Text(
                            "${Fmt.yuan(selected.unitPrice)} / 双",
                            color = color,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                // 用文字符号而不是 Icons.Default.ArrowDropDown：
                // androidx.compose.material:material-icons-core 不再随 material3 传递进来，
                // 用图标就得额外加依赖，这里没必要。
                Text("▾", color = MutedInk)
            }
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            gloves.forEach { glove ->
                DropdownMenuItem(
                    text = {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(glove.name)
                            Text(Fmt.yuan(glove.unitPrice), color = color)
                        }
                    },
                    onClick = {
                        onSelect(glove)
                        expanded = false
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("＋ 新建手套种类", color = color, fontWeight = FontWeight.Medium) },
                onClick = {
                    expanded = false
                    onCreate()
                },
            )
        }
    }
}

/**
 * 新建 / 修改手套种类。名称与单价都必填，单价允许 0（白做也算记录）。
 *
 * **重名会先提示再动手**：手套库按名称唯一，重名就等于「用新单价覆盖这一条，
 * 并把已有记录并到这个名称下」。老版本遇到重名是直接静默替换的，看着就像数据被顶掉了；
 * 现在会先把后果说清楚，要你点「仍然合并」才继续。
 */
@Composable
fun GloveDialog(
    initial: GloveType?,
    existing: List<GloveType>,
    title: String = "手套种类",
    onDismiss: () -> Unit,
    onSave: (name: String, price: Double) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var price by remember { mutableStateOf(initial?.let { Fmt.money(it.unitPrice) }.orEmpty()) }
    // 用户已经看过重名提示并确认要继续。改了名字或价格就重新问一次。
    var confirmed by remember { mutableStateOf(false) }

    val parsed = price.toDoubleOrNull()
    val valid = name.isNotBlank() && parsed != null && parsed >= 0

    // 重名判定：排除掉「自己原来那一条」（改价、只数字没改的情况不算重名）。
    val trimmed = name.trim()
    val duplicate = existing.firstOrNull { it.name == trimmed && it.name != initial?.name }
    val conflict = duplicate != null && !confirmed

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    duplicate != null -> "手套库里已经有「${duplicate.name}」"
                    initial == null -> "新建$title"
                    else -> "修改$title"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        confirmed = false
                    },
                    label = { Text("手套名称") },
                    singleLine = true,
                    isError = duplicate != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = price,
                    onValueChange = {
                        price = it.filter { ch -> ch.isDigit() || ch == '.' }
                        confirmed = false
                    },
                    label = { Text("默认单价（元 / 双）") },
                    singleLine = true,
                    keyboardOptions = MoneyKeys,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (duplicate != null) {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("名称重复", fontWeight = FontWeight.Bold)
                            Text(
                                "手套库里已有一条「${duplicate.name}」（默认 ${Fmt.yuan(duplicate.unitPrice)} / 双）。" +
                                    "同一本库里的手套种类按名称唯一，不能存在两条同名。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "继续保存会：把它的默认单价改成你填的价格（${parsed?.let { Fmt.yuan(it) } ?: "—"}），" +
                                    "并把已有记录里同名的手套都并到这一条下面。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "想保留两条不同单价的同名手套，请把名称改得能区分开（比如加个「大/小」或尺寸）。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                } else {
                    Hint("这里存的是默认单价；以后改价不会影响已经记好的旧账。")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    if (conflict) {
                        confirmed = true
                    } else {
                        parsed?.let { onSave(trimmed, it) }
                    }
                },
            ) {
                Text(
                    when {
                        conflict -> "仍然合并"
                        duplicate != null -> "确认合并并保存"
                        else -> "保存"
                    },
                    color = if (conflict) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { if (duplicate != null) confirmed = false else onDismiss() }) {
                Text(if (duplicate != null) "改名称" else "取消")
            }
        },
    )
}

/** 修改一条家里手套记录：换种类、改数量。 */
@Composable
fun EditHomeDialog(
    record: HomeWork,
    gloveNames: List<String>,
    onDismiss: () -> Unit,
    onSave: (gloveName: String, unitPrice: Double, quantity: Int) -> Unit,
) {
    var name by remember { mutableStateOf(record.gloveName) }
    var price by remember { mutableStateOf(Fmt.money(record.unitPrice)) }
    var quantity by remember { mutableStateOf(record.quantity.toString()) }
    val chipScroll = rememberScrollState()

    val parsedPrice = price.toDoubleOrNull()
    val parsedQuantity = quantity.toIntOrNull()
    val valid = name.isNotBlank() && parsedPrice != null && parsedPrice >= 0 && parsedQuantity != null && parsedQuantity > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改手套记录（当天合计）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("手套种类") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (gloveNames.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(chipScroll),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        gloveNames.forEach { candidate ->
                            Card(
                                modifier = Modifier.clickable { name = candidate },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (candidate == name) {
                                        HomeGreen.copy(alpha = 0.15f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                ),
                                shape = RoundedCornerShape(8.dp),
                            ) { Text(candidate, Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) }
                        }
                    }
                }
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("当天单价（元 / 双）") },
                    singleLine = true,
                    keyboardOptions = MoneyKeys,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = quantity,
                    onValueChange = { quantity = it.filter(Char::isDigit) },
                    label = { Text("数量（双）") },
                    singleLine = true,
                    keyboardOptions = QuantityKeys,
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint("这条记录代表当天这个手套种类的合计；改数量就是改当天合计。")
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onSave(name.trim(), parsedPrice ?: 0.0, parsedQuantity ?: 0) },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
