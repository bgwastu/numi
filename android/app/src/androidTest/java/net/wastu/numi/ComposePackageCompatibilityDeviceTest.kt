package net.wastu.numi

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.commons.compose.extensions.fakeVersionCheck
import org.fossify.commons.extensions.baseConfig
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposePackageCompatibilityDeviceTest {
    @Test fun composeScreensRecognizeTheInstalledApplicationPackage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previous = context.baseConfig.appRunCount
        try {
            // Upstream shows its warning on every 100th launch, independent of randomness.
            context.baseConfig.appRunCount = 100
            var warned = false
            repeat(100) { context.fakeVersionCheck { warned = true } }
            assertFalse("Compose screens must not label the Numi package as a fake app", warned)
        } finally {
            context.baseConfig.appRunCount = previous
        }
    }
}
