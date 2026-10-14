package com.travel2chicago.gemmapipeline.ast

import org.junit.Assert.assertEquals
import org.junit.Test

class CascadeAstEngineTest {

    @Test fun `adds a period and capitalises unpunctuated text`() {
        assertEquals("We are looking for a place.", CascadeAstEngine.finishSentence("we are looking for a place"))
    }

    @Test fun `keeps existing terminators`() {
        assertEquals("What's up?", CascadeAstEngine.finishSentence("What's up?"))
        assertEquals("No way!", CascadeAstEngine.finishSentence("no way!"))
    }

    @Test fun `terminator before a closing quote counts`() {
        assertEquals("He said \"hello.\"", CascadeAstEngine.finishSentence("He said \"hello.\""))
    }

    @Test fun `blank stays blank`() {
        assertEquals("", CascadeAstEngine.finishSentence("   "))
    }
}
