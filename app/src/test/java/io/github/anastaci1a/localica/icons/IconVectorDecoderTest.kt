package io.github.anastaci1a.localica.icons

import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class IconVectorDecoderTest {
    @Test fun equalGeometryHasOnePublishedVectorAcrossConcurrentDecodes() {
        val decoder = IconVectorDecoder()
        val svg = """<svg viewBox="0 0 24 24"><path d="M12 4 L20 20 L4 20 Z"/></svg>""".toByteArray()
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (0 until 8).map { pool.submit(Callable {
                check(gate.await(5, TimeUnit.SECONDS))
                decoder.decode(svg.copyOf())
            }) }
            gate.countDown()
            val first = results.first().get(10, TimeUnit.SECONDS)
            results.forEach { assertSame(first, it.get(10, TimeUnit.SECONDS)) }
            assertSame(first, decoder.decode(svg))
        } finally { pool.shutdownNow() }
    }
}
