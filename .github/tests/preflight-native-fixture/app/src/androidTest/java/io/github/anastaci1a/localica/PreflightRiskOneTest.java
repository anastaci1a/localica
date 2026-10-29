package io.github.anastaci1a.localica;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import androidx.test.platform.app.InstrumentationRegistry;

public class PreflightRiskOneTest {
    @Test public void riskOneExecutes() {
        assertEquals("io.github.anastaci1a.localica.debug",
            InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName());
    }
}
