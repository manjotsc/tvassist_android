package com.tvassist.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for release-version comparison.
 *
 * The failure this guards against is silent: a string compare puts "1.1.10" *before* "1.1.9", so
 * the app would simply stop offering updates once a component reaches double digits, with no error
 * anywhere. Nothing on screen would look wrong — the row would just say "Up to date" forever.
 */
class UpdateCheckerTest {

    @Test fun `a higher patch is newer`() {
        assertTrue(UpdateChecker.isNewer("1.1.4", "1.1.3"))
    }

    @Test fun `the same version is not newer`() {
        assertFalse(UpdateChecker.isNewer("1.1.3", "1.1.3"))
    }

    @Test fun `an older version is not newer`() {
        assertFalse(UpdateChecker.isNewer("1.1.2", "1.1.3"))
    }

    @Test fun `double-digit patch beats single digit`() {
        // The whole reason this comparison is numeric rather than lexicographic.
        assertTrue(UpdateChecker.isNewer("1.1.10", "1.1.9"))
        assertFalse(UpdateChecker.isNewer("1.1.9", "1.1.10"))
    }

    @Test fun `double-digit minor beats single digit`() {
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.0"))
        assertFalse(UpdateChecker.isNewer("1.9.0", "1.10.0"))
    }

    @Test fun `major version dominates`() {
        assertTrue(UpdateChecker.isNewer("2.0.0", "1.99.99"))
        assertFalse(UpdateChecker.isNewer("1.99.99", "2.0.0"))
    }

    @Test fun `a v prefix is ignored`() {
        assertTrue(UpdateChecker.isNewer("v1.1.4", "1.1.3"))
        assertFalse(UpdateChecker.isNewer("v1.1.3", "1.1.3"))
    }

    @Test fun `missing components count as zero`() {
        assertTrue(UpdateChecker.isNewer("1.2", "1.1.9"))
        assertFalse(UpdateChecker.isNewer("1.1", "1.1.0"))
    }

    @Test fun `a longer version is newer only if the extra part is non-zero`() {
        assertTrue(UpdateChecker.isNewer("1.1.3.1", "1.1.3"))
        assertFalse(UpdateChecker.isNewer("1.1.3.0", "1.1.3"))
    }

    @Test fun `non-numeric suffixes degrade instead of throwing`() {
        // A tag like "1.1.4-beta" must still compare, not crash the check.
        assertTrue(UpdateChecker.isNewer("1.1.4-beta", "1.1.3"))
        assertFalse(UpdateChecker.isNewer("1.1.3-beta", "1.1.3"))
    }

    @Test fun `entirely non-numeric tags read as zero and never offer an update`() {
        // "nightly" parses to 0.0.0 — safer to stay quiet than to prompt on garbage.
        assertFalse(UpdateChecker.isNewer("nightly", "1.1.3"))
    }
}
