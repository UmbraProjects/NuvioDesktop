package com.nuvio.app.features.details

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImdbEpisodeRatingsParserTest {
    @Test
    fun parsesNumberedEpisodesAndSkipsMissingRatings() {
        val payload = """
            {
              "data": {
                "title": {
                  "episodes": {
                    "episodes": {
                      "edges": [
                        {
                          "node": {
                            "series": { "episodeNumber": { "seasonNumber": 1, "episodeNumber": 1 } },
                            "ratingsSummary": { "aggregateRating": 6.8 }
                          }
                        },
                        {
                          "node": {
                            "series": { "episodeNumber": { "seasonNumber": 1, "episodeNumber": 2 } },
                            "ratingsSummary": { "aggregateRating": null }
                          }
                        },
                        {
                          "node": {
                            "series": { "episodeNumber": { "seasonNumber": 2, "episodeNumber": 3 } },
                            "ratingsSummary": { "aggregateRating": 7.25 }
                          }
                        }
                      ]
                    }
                  }
                }
              },
              "extensions": { "ignored": true }
            }
        """.trimIndent()

        val parsed = parseImdbGraphQlEpisodeRatings(payload)

        assertEquals(2, parsed.size)
        assertEquals(1, parsed[0].seasonNumber)
        assertEquals(1, parsed[0].episodeNumber)
        assertEquals(6.8, parsed[0].voteAverage)
        assertEquals(2, parsed[1].seasonNumber)
        assertEquals(3, parsed[1].episodeNumber)
        assertEquals(7.25, parsed[1].voteAverage)
    }

    @Test
    fun readsCursorWhenMoreEpisodesRemain() {
        val parsed = parseImdbGraphQlEpisodeRatingsPage(pagePayload(hasNextPage = true, endCursor = "cursor-250"))

        assertEquals("cursor-250", parsed.nextCursor)
        assertEquals(1, parsed.ratings.size)
    }

    @Test
    fun stopsWalkingOnTheLastPage() {
        assertNull(parseImdbGraphQlEpisodeRatingsPage(pagePayload(hasNextPage = false, endCursor = "cursor-508")).nextCursor)
        assertNull(parseImdbGraphQlEpisodeRatingsPage(pagePayload(hasNextPage = true, endCursor = null)).nextCursor)
    }

    private fun pagePayload(hasNextPage: Boolean, endCursor: String?): String = """
        {
          "data": {
            "title": {
              "episodes": {
                "episodes": {
                  "pageInfo": {
                    "hasNextPage": $hasNextPage,
                    "endCursor": ${endCursor?.let { "\"$it\"" } ?: "null"}
                  },
                  "edges": [
                    {
                      "node": {
                        "series": { "episodeNumber": { "seasonNumber": 18, "episodeNumber": 4 } },
                        "ratingsSummary": { "aggregateRating": 7.1 }
                      }
                    }
                  ]
                }
              }
            }
          }
        }
    """.trimIndent()
}
