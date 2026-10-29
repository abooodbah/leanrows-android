package io.github.abooodbah.leanrows

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun launcherStartsTheTable() {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        assertNotNull("the app has a launcher entry", launch)
        ActivityScenario.launch<android.app.Activity>(launch!!).use { scenario ->
            scenario.onActivity { activity -> assertFalse(activity.isFinishing) }
        }
    }

    @Test
    fun rowFilesFromAnotherAppOpenHere() {
        val cases = listOf(
            "rows.csv" to "text/csv",
            "rows.csv" to "text/comma-separated-values",
            "rows.tsv" to "text/tab-separated-values",
            "rows.jsonl" to "application/x-ndjson",
            "rows.csv" to "application/octet-stream",
        )
        for ((name, type) in cases) {
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse("content://example.files/$name"), type)
            val handlers = context.packageManager
                .queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY)
                .map { it.activityInfo.packageName }
            assertTrue("$name as $type resolves to LeanRows: $handlers", context.packageName in handlers)
        }
    }

    @Test
    fun installedAppHoldsNoPermissions() {
        val info = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertEquals(emptyList<String>(), info.requestedPermissions?.toList().orEmpty())
    }
}
