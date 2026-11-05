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

    @Test fun `doubled short sentence collapses to one`() {
        assertEquals(
            "Hola, buenos días.",
            CascadeAstEngine.collapseDuplicatedSentence("Hola, buenos días. Hola, buenos días.", 1_856),
        )
        assertEquals(
            "Hola, buenos días.",
            CascadeAstEngine.collapseDuplicatedSentence("Hola, buenos días. hola buenos días", 1_856),
        )
    }

    @Test fun `real repetition and different sentences are kept`() {
        val spaced = "Hola, buenos días. Hola, buenos días."
        assertEquals(spaced, CascadeAstEngine.collapseDuplicatedSentence(spaced, 4_000))
        val short = "¿Qué pasa? ¿Qué pasa?"
        assertEquals(short, CascadeAstEngine.collapseDuplicatedSentence(short, 1_888))
        val different = "Hola, buenos días. ¿Cómo están todos?"
        assertEquals(different, CascadeAstEngine.collapseDuplicatedSentence(different, 2_000))
    }

    @Test fun `blank stays blank`() {
        assertEquals("", CascadeAstEngine.finishSentence("   "))
    }
}
