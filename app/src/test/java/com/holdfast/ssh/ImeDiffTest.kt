package com.holdfast.ssh

import com.holdfast.ssh.util.ImeDiff
import org.junit.Assert.assertEquals
import org.junit.Test

class ImeDiffTest {
    @Test fun typingAppends() = assertEquals(0 to "o", ImeDiff.diff("hell", "hello"))
    @Test fun autocorrectRewritesTail() = assertEquals(3 to "he ", ImeDiff.diff("teh ", "the "))
    @Test fun autocorrectMidSentence() = assertEquals(3 to "he ", ImeDiff.diff("i teh ", "i the "))
    @Test fun swipeWordReplaced() = assertEquals(5 to "world", ImeDiff.diff("hi there", "hi world"))
    @Test fun backspace() = assertEquals(1 to "", ImeDiff.diff("abc", "ab"))
    @Test fun noChange() = assertEquals(0 to "", ImeDiff.diff("abc", "abc"))
    @Test fun emojiCountsAsOneBackspace() = assertEquals(1 to "", ImeDiff.diff("a😀", "a"))
    @Test fun swapEmojiKeepsPairIntact() = assertEquals(1 to "😃", ImeDiff.diff("😀", "😃"))
    @Test fun wordAt() {
        val t = "run ~/HoldFast-uploads now"
        assertEquals(t.indexOf("HoldFast") to t.indexOf("-"), ImeDiff.wordAt(t, t.indexOf("Fast")))
        assertEquals(0 to 3, ImeDiff.wordAt(t, 1))
        assertEquals(3 to 4, ImeDiff.wordAt(t, 3))
    }
}
