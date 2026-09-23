package com.mr5u.glovestatistics

import android.content.Context

/**
 * 本地离线存储。
 *
 * 全部数据放在 SharedPreferences 的纯文本里，一行一条记录，字段用 [SEPARATOR] 分隔；
 * 可能含有分隔符或换行的文本字段先做 Base64 再存。
 *
 * 行格式（v0.5.0）：
 *
 * ```
 * 手套库   名称|默认单价            （名称是 Base64）
 * 手套记录  日期|种类|单价|数量       （种类是 Base64，日期是 ISO 的 yyyy-MM-dd）
 * ```
 *
 * ## 厂房数据的去向（v0.5.0）
 *
 * 厂房工作整块下线后，[KEY_FACTORY] 与 [KEY_FACTORY_GLOVES] 两个键**不再读取、也不再写入**，
 * 但**也没有删除**：旧版本写下的原始行还原样躺在 SharedPreferences 里。
 *
 * 这样做有两个好处：
 * 1. 升级过程里不存在「整表重写」这一步，也就没有迁移到一半失败的风险；
 * 2. 万一以后还要翻这批老数据，回装 0.4.0 就能原样读回来。
 *
 * 家里手套记录本来就是 4 个字段，本次未改动，老数据一行都不用动。
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
        .mapNotNull { line -> ReportLayout.parseHomeLine(line, SEPARATOR, ::decodeText) }
        .sortedBy { it.date }

    fun save(gloves: List<GloveType>, home: List<HomeWork>) {
        preferences.edit()
            .putString(KEY_GLOVES, encodeGloves(gloves))
            .putString(
                KEY_HOME,
                home.joinToString("\n") {
                    "${it.date}$SEPARATOR${encodeText(it.gloveName)}$SEPARATOR${it.unitPrice}$SEPARATOR${it.quantity}"
                },
            )
            .apply()
    }

    private fun encodeGloves(gloves: List<GloveType>): String =
        gloves.joinToString("\n") { "${encodeText(it.name)}$SEPARATOR${it.unitPrice}" }

    private fun decodeLines(raw: String?): List<String> =
        raw?.lines()?.filter { it.isNotBlank() } ?: emptyList()

    private fun encodeText(value: String): String = ReportLayout.encodeField(value)

    private fun decodeText(value: String): String? = ReportLayout.decodeField(value)

    companion object {
        const val PREFS_NAME = "glove_statistics"
        const val SEPARATOR = "|"

        const val KEY_GLOVES = "gloves"
        const val KEY_HOME = "home"

        /** v0.4.0 的厂房键。v0.5.0 起只保留旧值、不再读写，理由见类注释。 */
        const val KEY_FACTORY = "factory"
        const val KEY_FACTORY_GLOVES = "factoryGloves"
    }
}
