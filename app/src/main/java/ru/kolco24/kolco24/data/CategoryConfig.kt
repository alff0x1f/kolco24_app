package ru.kolco24.kolco24.data

data class CategoryOption(val code: Int, val name: String)

object CategoryConfig {
    private val categoriesInternal = listOf(
        CategoryOption(8, "6ч"),
        CategoryOption(9, "12"),
        CategoryOption(10, "МЖ"),
        CategoryOption(11, "ММ"),
        CategoryOption(12, "ЖЖ"),
        CategoryOption(13, "24"),
        CategoryOption(14, "8ч"),
        CategoryOption(15, "8ч"))

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
