package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadRangePlanningTest {

    @Test
    fun `four segments cover every byte exactly once`() {
        val ranges = planDownloadSegments(totalBytes = 10L, requestedSegmentCount = 4)

        assertEquals(
            listOf(
                DownloadByteRange(0L, 2L),
                DownloadByteRange(3L, 5L),
                DownloadByteRange(6L, 7L),
                DownloadByteRange(8L, 9L),
            ),
            ranges,
        )
        assertEquals(10L, ranges.sumOf { it.length })
    }

    @Test
    fun `segment count cannot create empty ranges`() {
        val ranges = planDownloadSegments(totalBytes = 2L, requestedSegmentCount = 4)

        assertEquals(
            listOf(
                DownloadByteRange(0L, 0L),
                DownloadByteRange(1L, 1L),
            ),
            ranges,
        )
    }

    @Test
    fun `large segment plan remains contiguous`() {
        val totalBytes = 50_000_000_003L
        val ranges = planDownloadSegments(totalBytes, requestedSegmentCount = 4)

        assertEquals(0L, ranges.first().start)
        assertEquals(totalBytes - 1L, ranges.last().endInclusive)
        assertEquals(totalBytes, ranges.sumOf { it.length })
        assertTrue(ranges.zipWithNext().all { (left, right) -> left.endInclusive + 1L == right.start })
    }

    @Test
    fun `content range parser validates exact bounds and total`() {
        assertEquals(
            DownloadContentRange(start = 100L, endInclusive = 199L, totalBytes = 1_000L),
            parseContentRange("bytes 100-199/1000"),
        )
        assertEquals(
            DownloadContentRange(start = 0L, endInclusive = 0L, totalBytes = 1L),
            parseContentRange("Bytes 0-0/1"),
        )
    }

    @Test
    fun `content range parser rejects incomplete or impossible values`() {
        assertNull(parseContentRange(null))
        assertNull(parseContentRange("bytes */1000"))
        assertNull(parseContentRange("bytes 200-100/1000"))
        assertNull(parseContentRange("bytes 0-1000/1000"))
        assertNull(parseContentRange("items 0-1/2"))
    }

    @Test
    fun `segment metadata round-trips per-connection progress`() {
        val ranges = planDownloadSegments(totalBytes = 10L, requestedSegmentCount = 4)
        val text = formatSegmentMetadata("10:42", longArrayOf(3L, 1L, 0L, 2L))

        assertEquals(
            listOf(3L, 1L, 0L, 2L),
            parseSegmentMetadata(text, header = "10:42", ranges = ranges)?.toList(),
        )
    }

    @Test
    fun `segment metadata from another file or an older build is ignored`() {
        val ranges = planDownloadSegments(totalBytes = 10L, requestedSegmentCount = 4)

        assertNull(parseSegmentMetadata("v2 10:41 0,0,0,0", header = "10:42", ranges = ranges))
        assertNull(parseSegmentMetadata("10:42", header = "10:42", ranges = ranges))
        assertNull(parseSegmentMetadata("v2 10:42 0,0,0", header = "10:42", ranges = ranges))
        assertNull(parseSegmentMetadata("v2 10:42 0,x,0,0", header = "10:42", ranges = ranges))
        assertNull(parseSegmentMetadata(null, header = "10:42", ranges = ranges))
    }

    @Test
    fun `segment metadata never claims more than a range holds`() {
        val ranges = planDownloadSegments(totalBytes = 10L, requestedSegmentCount = 4)

        assertEquals(
            listOf(3L, 0L, 2L, 2L),
            parseSegmentMetadata("v2 10:42 99,-5,2,2", header = "10:42", ranges = ranges)?.toList(),
        )
    }
}
