package io.github.anastaci1a.localica.library

import io.github.anastaci1a.localica.navigation.MainTab
import io.github.anastaci1a.localica.navigation.MediaKind
import io.github.anastaci1a.localica.queue.QueueRepeat
import java.util.Locale
import kotlin.enums.enumEntries
import org.junit.Assert.*
import org.junit.Test

class EnumNameTest {
    @Test fun knownNamesDecodeExactlyAcrossCallerEnums() {
        assertKnown<MainTab>()
        assertKnown<MediaKind>()
        assertKnown<QueueRepeat>()
        assertKnown<TrackSort>()
    }

    @Test fun nullUnknownBlankCaseAndWhitespaceDoNotDecode() {
        assertRejected<MainTab>()
        assertRejected<MediaKind>()
        assertRejected<QueueRepeat>()
        assertRejected<TrackSort>()
    }

    private inline fun <reified E : Enum<E>> assertKnown() {
        enumEntries<E>().forEach { assertEquals(it, enumNameOrNull<E>(it.name)) }
    }

    private inline fun <reified E : Enum<E>> assertRejected() {
        for (name in listOf(null, "", "FutureEnumName", "0")) assertNull(enumNameOrNull<E>(name))
        enumEntries<E>().forEach {
            assertNull(enumNameOrNull<E>(it.name.lowercase(Locale.ROOT)))
            assertNull(enumNameOrNull<E>(" " + it.name))
            assertNull(enumNameOrNull<E>(it.name + " "))
        }
    }
}
