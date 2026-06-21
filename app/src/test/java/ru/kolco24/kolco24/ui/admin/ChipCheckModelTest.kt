package ru.kolco24.kolco24.ui.admin

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kolco24.kolco24.data.db.CheckpointEntity
import ru.kolco24.kolco24.data.db.TagEntity
import ru.kolco24.kolco24.ui.legend.CheckpointColor

class ChipCheckModelTest {

    private fun tag(bid: String, point: Int, checkMethod: String = "nfc"): TagEntity =
        TagEntity(raceId = 1, bid = bid, point = point, checkMethod = checkMethod, iv = null, ct = null)

    private fun cp(id: Int, number: Int, cost: Int? = 5, color: String = ""): CheckpointEntity =
        CheckpointEntity(
            id = id,
            raceId = 1,
            number = number,
            cost = cost,
            type = "kp",
            description = null,
            color = color,
        )

    @Test
    fun classify_tagAndCheckpointPresent_isOk() {
        val result = classifyChipCheck(
            uid = "0411223344AABB",
            bid = "abc123",
            tag = tag("abc123", point = 10, checkMethod = "photo"),
            checkpoint = cp(id = 10, number = 7, cost = 8, color = "red"),
            chipsOnKp = 3,
        )
        assertEquals(
            ChipCheckResult.Ok(
                uid = "0411223344AABB",
                number = 7,
                cost = 8,
                color = CheckpointColor.RED,
                bid = "abc123",
                checkMethod = "photo",
                chipsOnKp = 3,
            ),
            result,
        )
    }

    @Test
    fun classify_nullBid_isNoCode() {
        val result = classifyChipCheck(
            uid = "DEADBEEF",
            bid = null,
            tag = null,
            checkpoint = null,
            chipsOnKp = 0,
        )
        assertEquals(ChipCheckResult.NoCode("DEADBEEF"), result)
    }

    @Test
    fun classify_noMatchingTag_isUnknownChip() {
        val result = classifyChipCheck(
            uid = "0411223344AABB",
            bid = "deadbid",
            tag = null,
            checkpoint = null,
            chipsOnKp = 0,
        )
        assertEquals(ChipCheckResult.UnknownChip("0411223344AABB", "deadbid"), result)
    }

    @Test
    fun classify_tagButNoCheckpoint_isInconsistent() {
        val result = classifyChipCheck(
            uid = "0411223344AABB",
            bid = "abc123",
            tag = tag("abc123", point = 99),
            checkpoint = null,
            chipsOnKp = 1,
        )
        assertEquals(ChipCheckResult.Inconsistent("0411223344AABB", "abc123", 99), result)
    }

    @Test
    fun classify_ok_unknownColorToken_nullColor() {
        val result = classifyChipCheck(
            uid = "UID",
            bid = "abc123",
            tag = tag("abc123", point = 10),
            checkpoint = cp(id = 10, number = 1, color = "teal"),
            chipsOnKp = 1,
        ) as ChipCheckResult.Ok
        assertEquals(null, result.color)
    }

    @Test
    fun classify_lockedCheckpoint_isOkWithNullCost() {
        val result = classifyChipCheck(
            uid = "0411223344AABB",
            bid = "abc123",
            tag = tag("abc123", point = 10),
            checkpoint = cp(id = 10, number = 5, cost = null),
            chipsOnKp = 1,
        )
        assertEquals(
            ChipCheckResult.Ok(
                uid = "0411223344AABB",
                number = 5,
                cost = null,
                color = null,
                bid = "abc123",
                checkMethod = "nfc",
                chipsOnKp = 1,
            ),
            result,
        )
    }

    @Test
    fun classify_nullBid_withNonNullArgs_isNoCode() {
        val result = classifyChipCheck(
            uid = "DEADBEEF",
            bid = null,
            tag = tag("abc", point = 1),
            checkpoint = cp(id = 1, number = 1),
            chipsOnKp = 2,
        )
        assertEquals(ChipCheckResult.NoCode("DEADBEEF"), result)
    }
}
