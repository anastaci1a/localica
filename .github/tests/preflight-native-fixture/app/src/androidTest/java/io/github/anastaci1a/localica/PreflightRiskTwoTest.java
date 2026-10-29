package io.github.anastaci1a.localica;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import androidx.test.platform.app.InstrumentationRegistry;

public class PreflightRiskTwoTest {
    @Test public void riskTwoExecutes() {
        assertEquals("io.github.anastaci1a.localica.debug",
            InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName());
    }
}
