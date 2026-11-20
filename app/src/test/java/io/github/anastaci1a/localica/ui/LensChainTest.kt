package io.github.anastaci1a.localica.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LensChainTest {
    private fun chain(blur: String?) = lensChain(blur, "lens") { outer, inner -> "$outer($inner)" }

    @Test fun blurFeedsTheLensInTheSharedFilterOrder() = assertEquals("lens(blur)", chain("blur"))

    @Test fun identityBlurIsElided() = assertEquals("lens", chain(null))
}
