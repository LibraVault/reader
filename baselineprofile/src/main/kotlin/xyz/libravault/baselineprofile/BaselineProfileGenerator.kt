package xyz.libravault.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Generates `app/src/release/generated/baselineProfiles/` — profiles covering
 * only cold app startup for now (the library scan / open-vault / reader /
 * player journeys are follow-up work, same scope split as the macrobenchmarks
 * in :benchmark). See baselineprofile/build.gradle.kts for the fdroid-only
 * flavour choice and why this can't be run in this repo's CI yet.
 */
private const val PACKAGE_NAME = "xyz.libravault.app"

class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.pkg(PACKAGE_NAME).depth(0)), 5_000)
    }
}
