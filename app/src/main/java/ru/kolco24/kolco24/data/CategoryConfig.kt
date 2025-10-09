package ru.kolco24.kolco24.data

data class CategoryOption(val code: Int, val name: String)

object CategoryConfig {
    private val categoriesInternal = listOf(
        CategoryOption(16, "6ч"),
        CategoryOption(17, "12ч, команды"),
        CategoryOption(18, "12ч, МЖ"),
        CategoryOption(19, "12ч, ММ"),
        CategoryOption(20, "12ч, ЖЖ"),
        CategoryOption(21, "24ч"),
        CategoryOption(22, "8ч вело ММ"),
        CategoryOption(23, "8ч вело МЖ/ЖЖ"))

    val categories: List<CategoryOption>
        @JvmStatic
        get() = categoriesInternal

    @JvmStatic
    fun getCount(): Int = categoriesInternal.size

    @JvmStatic
    fun getName(position: Int): String = categoriesInternal.getOrNull(position)?.name ?: ""

    @JvmStatic
    fun getCode(position: Int): Int = categoriesInternal.getOrNull(position)?.code ?: 0

    @JvmStatic
    fun findPositionByCode(code: Int): Int {
        val index = categoriesInternal.indexOfFirst { it.code == code }
        return if (index >= 0) index else 0
    }
}
