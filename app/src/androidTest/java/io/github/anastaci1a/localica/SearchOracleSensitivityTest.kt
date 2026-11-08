package io.github.anastaci1a.localica

import io.github.anastaci1a.localica.ui.engine.SearchReturnOracleSensitivityTest
import org.junit.Test

/** The deliberate delayed return is also checked on both Android runtime APIs. */
class SearchOracleSensitivityTest {
    @Test fun oneFrameLateReturnFailsOldAndNewOracles() =
        SearchReturnOracleSensitivityTest().bothOraclesRejectAOneFrameLateReturn()
}
