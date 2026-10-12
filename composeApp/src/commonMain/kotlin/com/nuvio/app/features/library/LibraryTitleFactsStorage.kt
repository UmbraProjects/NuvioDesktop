package com.nuvio.app.features.library

/** TMDB facts about library titles (genres, studios, status…) for the Library filters. */
internal expect object LibraryTitleFactsStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}
