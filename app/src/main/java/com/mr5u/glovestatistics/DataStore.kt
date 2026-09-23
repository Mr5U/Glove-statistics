package com.mr5u.glovestatistics

import android.content.Context

/**
 * 本地离线存储。
 *
 * 全部数据放在 SharedPreferences 的纯文本里，一行一条记录，字段用 [SEPARATOR] 分隔；
 * 可能含有分隔符或换行的文本字段先做 Base64 再存。
 *
 * ## 向后兼容（保证覆盖升级不丢数据）
 *
 * 「厂房记录」的行格式在 v0.4.0 从 3 个字段扩到 7 个字段：
 *
 * ```
 * v0.3.0  日期|金额|备注
 * v0.4.0  日期|金额|备注|种类|数量|单价|模式
 * ```
 *
 * 解析时**按字段个数判断版本**，而不是按数据版本号整体搬迁：
 * 老数据一行都不用改就能读出来，新写入的行老版本也能读到（前三个字段含义不变，
 * 老版本最多看不到计件的种类和数量，金额不会算错）。
 * 这样升级过程里不存在「整表重写」这一步，也就没有迁移到一半失败的风险。
 *
 * 家里的手套记录格式本来就是 4 个字段，v0.4.0 未改动。
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

    /** 厂房专用手套库。v0.4.0 新增，独立于家里的手套库，单价互不影响。 */
    fun factoryGloves(): List<GloveType> = decodeLines(preferences.getString(KEY_FACTORY_GLOVES, null))
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

    fun factoryWork(): List<FactoryWork> = decodeLines(preferences.getString(KEY_FACTORY, null))
        .mapNotNull { line -> ReportLayout.parseFactoryLine(line, SEPARATOR, ::decodeText) }
        .sortedBy { it.date }

    fun save(
        gloves: List<GloveType>,
        factoryGloves: List<GloveType>,
        home: List<HomeWork>,
        factory: List<FactoryWork>,
    ) {
        preferences.edit()
            .putString(KEY_GLOVES, encodeGloves(gloves))
            .putString(KEY_FACTORY_GLOVES, encodeGloves(factoryGloves))
            .putString(
                KEY_HOME,
                home.joinToString("\n") {
                    "${it.date}$SEPARATOR${encodeText(it.gloveName)}$SEPARATOR${it.unitPrice}$SEPARATOR${it.quantity}"
                },
            )
            .putString(
                KEY_FACTORY,
                factory.joinToString("\n") {
                    listOf(
                        it.date,
                        it.amount.toString(),
                        encodeText(it.note),
                        encodeText(it.gloveName),
                        it.quantity.toString(),
                        it.unitPrice.toString(),
                        it.mode.key,
                    ).joinToString(SEPARATOR)
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
        const val KEY_FACTORY = "factory"

        /** v0.4.0 新增键：厂房手套库。老版本读到会直接忽略，不影响它读取其他数据。 */
        const val KEY_FACTORY_GLOVES = "factoryGloves"
    }
}
