package com.travel2chicago.gemmapipeline.gate

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Carried in the coroutine context of a translation when the speaker gate confirmed the chunk is
 * Abraham's voice, so [com.travel2chicago.gemmapipeline.mt.ServerVoiceTranslator] can ask the relay for
 * the speaker-verified prompt (no "stay silent when it sounds odd" rules) without changing TextTranslator.
 */
class SpeakerVerified : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SpeakerVerified>
}
