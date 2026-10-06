package app.bililisten.shared

import kotlin.test.*
import kotlin.random.Random

class ResumeTest {
    private fun snapshot(): ResumeSnapshot {
        val entries = listOf(
            QueueEntry("a", "BV1xx411c7mD", 1, 1, "长内容 P1", 42),
            QueueEntry("b", "BV1xx411c7mD", 2, 2, "长内容 P2", 42),
            QueueEntry("c", "BV1xx411c7mD", 1, 1, "重复入队", 42),
        )
        return ResumeSnapshot(account = "7", entries = entries, order = listOf("a", "b", "c"), currentId = "b", positionMs = 91234)
    }
    @Test fun restartRetainsActualShuffleOrderAndSource() {
        val original = snapshot().withMode(PlayMode.SHUFFLE, Random(11))
        val restored = SnapshotCodec.decode(SnapshotCodec.encode(original))
        assertEquals(original, restored)
        assertEquals("b", restored.order.first())
        assertEquals(listOf(42L, 42L, 42L), restored.playbackEntries().map { it.sourceFolder })
        assertEquals(3, restored.order.toSet().size)
    }
    @Test fun selectingShuffleAgainDoesNotReshuffleAndSequentialRestoresSourceOrder() {
        val source = snapshot()
        val shuffled = source.withMode(PlayMode.SHUFFLE, Random(11))
        assertEquals(shuffled, shuffled.withMode(PlayMode.SHUFFLE, Random(999)))
        assertEquals(source.order, shuffled.withMode(PlayMode.SEQUENTIAL).order)
        assertEquals(source.currentId, shuffled.withMode(PlayMode.SEQUENTIAL).currentId)
        assertEquals(source.positionMs, shuffled.withMode(PlayMode.SEQUENTIAL).positionMs)
    }
    @Test fun malformedPermutationAndFutureSchemaAreRejected() {
        assertFailsWith<IllegalArgumentException> { snapshot().copy(order = listOf("a", "a", "c")).checked() }
        assertFailsWith<IllegalArgumentException> { snapshot().copy(schema = 2).checked() }
        assertFailsWith<IllegalArgumentException> { snapshot().copy(currentId = "missing").checked() }
        assertFailsWith<IllegalArgumentException> { snapshot().copy(positionMs = -1).checked() }
    }
    @Test fun duplicateContentKeepsIndependentQueueIdentity() {
        assertEquals(3, snapshot().checked().entries.size)
        assertFailsWith<IllegalArgumentException> { snapshot().copy(entries = listOf(snapshot().entries.first(), snapshot().entries.first())).checked() }
    }
    @Test fun acceptsOnlyExplicitBilibiliVideoInput() {
        assertEquals("BV1xx411c7mD", Bvid.parse("https://www.bilibili.com/video/BV1xx411c7mD?p=2"))
        assertNull(Bvid.parse("https://bilibili.com.evil.example/video/BV1xx411c7mD"))
        assertNull(Bvid.parse("https://example.org/BV1xx411c7mD"))
        assertNull(Bvid.parse("BV1xx411c7mDmore"))
    }
}
