package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceSearchCommandTest {

    @Test
    fun aSpokenSearchOrConfirmRunsTheWordsBeforeIt() {
        assertEquals(VoiceSearchCommand.Submit("The Dark Knight"), parseVoiceSearchCommand("The Dark Knight search."))
        assertEquals(VoiceSearchCommand.Submit("The Dark Knight"), parseVoiceSearchCommand("The Dark Knight, confirm"))
        assertEquals(VoiceSearchCommand.Submit("inception"), parseVoiceSearchCommand("inception Search"))
    }

    @Test
    fun deleteThatOrClearEmptiesTheBox() {
        assertEquals(VoiceSearchCommand.Clear, parseVoiceSearchCommand("The dork night delete that."))
        assertEquals(VoiceSearchCommand.Clear, parseVoiceSearchCommand("Clear"))
        assertEquals(VoiceSearchCommand.Clear, parseVoiceSearchCommand("batman clear search"))
    }

    @Test
    fun otherWordsStayAsTextWithoutTheTrailingFullStop() {
        assertEquals(VoiceSearchCommand.Text("The Dark Knight"), parseVoiceSearchCommand("The Dark Knight."))
        // Part of a word is not the command.
        assertEquals(VoiceSearchCommand.Text("cancer research"), parseVoiceSearchCommand("cancer research"))
        assertEquals(VoiceSearchCommand.Text("nuclear"), parseVoiceSearchCommand("nuclear"))
        // A command with nothing before it has nothing to search.
        assertEquals(VoiceSearchCommand.Text("search"), parseVoiceSearchCommand("search"))
    }
}
