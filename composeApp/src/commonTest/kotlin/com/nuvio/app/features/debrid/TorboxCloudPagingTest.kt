package com.nuvio.app.features.debrid

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class TorboxCloudPagingTest {
    private fun item(n: Int) = TorboxCloudItemDto(name = "item $n")

    private fun page(items: List<TorboxCloudItemDto>) =
        DebridApiResponse(status = 200, body = TorboxEnvelopeDto(success = true, data = items), rawBody = "{}")

    @Test
    fun `reads every page until a short one`() = runBlocking {
        val all = (0 until 5).map(::item)
        val offsets = mutableListOf<Int>()
        val response = fetchTorboxCloudPages(pageSize = 2) { offset, limit ->
            offsets += offset
            page(all.drop(offset).take(limit))
        }
        assertEquals(listOf(0, 2, 4), offsets)
        assertEquals(all, response.body?.data)
    }

    @Test
    fun `a full last page is followed by an empty one`() = runBlocking {
        val all = (0 until 4).map(::item)
        val offsets = mutableListOf<Int>()
        val response = fetchTorboxCloudPages(pageSize = 2) { offset, limit ->
            offsets += offset
            page(all.drop(offset).take(limit))
        }
        assertEquals(listOf(0, 2, 4), offsets)
        assertEquals(all, response.body?.data)
    }

    @Test
    fun `a failed page is returned as is`() = runBlocking {
        val failure = DebridApiResponse<TorboxEnvelopeDto<List<TorboxCloudItemDto>>>(
            status = 429,
            body = null,
            rawBody = "rate limited",
        )
        val response = fetchTorboxCloudPages(pageSize = 2) { offset, _ ->
            if (offset == 0) page(listOf(item(0), item(1))) else failure
        }
        assertSame(failure, response)
    }
}
