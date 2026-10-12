package com.nuvio.app.features.details

/** Whether the hero's Starring row shows actors' photos or TVDB's character portraits. */
internal expect object CastViewStorage {
    fun loadShowCharacters(): Boolean
    fun saveShowCharacters(value: Boolean)
}
