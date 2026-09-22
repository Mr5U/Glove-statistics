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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
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

/** 家里手套账的识别色。 */
val HomeGreen = Color(0xFF087B51)

/** 厂房账的识别色，和绿色刻意拉开，方便日历上一眼区分。 */
val FactoryOrange = Color(0xFF985700)

/** 未干活 / 次要信息色。 */
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

/** 白底中性卡片，用于“全部劳动收入”这类汇总行。 */
@Composable
fun TotalRow(label: String, amount: Double, modifier: Modifier = Modifier) = Card(modifier.fillMaxWidth()) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontWeight = FontWeight.Bold)
        Text(Fmt.yuan(amount), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    }
}

/** 日历格子上表示“当天有哪类活”的小圆点。 */
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

/** 新建 / 修改手套种类。名称与单价都必填，单价允许 0（白做也算记录）。 */
@Composable
fun GloveDialog(
    initial: GloveType?,
    onDismiss: () -> Unit,
    onSave: (name: String, price: Double) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var price by remember { mutableStateOf(initial?.let { Fmt.money(it.unitPrice) }.orEmpty()) }

    val parsed = price.toDoubleOrNull()
    val valid = name.isNotBlank() && parsed != null && parsed >= 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建手套种类" else "修改手套种类") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("手套名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("默认单价（元 / 双）") },
                    singleLine = true,
                    keyboardOptions = MoneyKeys,
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint("这里存的是默认单价；以后改价不会影响已经记好的旧账。")
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { parsed?.let { onSave(name.trim(), it) } },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
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
        title = { Text("修改手套记录") },
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

/** 修改一条厂房记录。 */
@Composable
fun EditFactoryDialog(
    record: FactoryWork,
    onDismiss: () -> Unit,
    onSave: (amount: Double, note: String) -> Unit,
) {
    var amount by remember { mutableStateOf(Fmt.money(record.amount)) }
    var note by remember { mutableStateOf(record.note) }

    val parsed = amount.toDoubleOrNull()
    val valid = parsed != null && parsed > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改厂房收入") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("当天收入（元）") },
                    singleLine = true,
                    keyboardOptions = MoneyKeys,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("工作备注（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { parsed?.let { onSave(it, note) } }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
