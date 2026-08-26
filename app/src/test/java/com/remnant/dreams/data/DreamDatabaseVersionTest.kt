package com.remnant.dreams.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tripwire, not a unit test. `fallbackToDestructiveMigration` is gone, so a version bump
 * that ships without a hand-written Migration no longer wipes journals silently -- it
 * crashes on every upgrading user's device instead. Fresh-install testing never sees it.
 *
 * This test makes the schema version impossible to change quietly: bump it and the suite
 * fails with the procedure to follow.
 */
class DreamDatabaseVersionTest {

    @Test
    fun `the room schema version is pinned`() {
        assertEquals(PROCEDURE, PINNED_VERSION, DREAM_DB_VERSION)
    }

    @Test
    fun `the exported schema for the pinned version is committed`() {
        // Room can only generate a Migration's before-and-after from these files, so a
        // version whose JSON was never committed cannot be migrated from later.
        val dir = schemaDir()
        assertNotNull(
            "Could not find app/schemas/$SCHEMA_DIR_NAME from ${File("").absolutePath}. " +
                "Schema export is configured in app/build.gradle.kts (room.schemaLocation).",
            dir
        )
        val schema = File(dir, "$DREAM_DB_VERSION.json")
        assertTrue(
            "Missing exported schema ${schema.absolutePath}. $PROCEDURE",
            schema.isFile && schema.length() > 0
        )
    }

    /**
     * Walks up from the test's working directory to find the exported schema folder, so
     * this passes whether tests run from the module directory or the repository root.
     */
    private fun schemaDir(): File? {
        var dir: File? = File("").absoluteFile
        repeat(4) {
            val here = dir ?: return null
            File(here, "schemas/$SCHEMA_DIR_NAME").let { if (it.isDirectory) return it }
            File(here, "app/schemas/$SCHEMA_DIR_NAME").let { if (it.isDirectory) return it }
            dir = here.parentFile
        }
        return null
    }

    private companion object {
        /**
         * Hard-coded on purpose. This is the whole point of the test: the number below is
         * updated by hand, last, after the migration work is done.
         */
        const val PINNED_VERSION = 2

        const val SCHEMA_DIR_NAME = "com.remnant.dreams.data.DreamDatabase"

        const val PROCEDURE =
            "DreamDatabase version changed: you MUST add a Room Migration for the old->new " +
                "step, keep the exported schema JSON for the new version, add a migration " +
                "test, and only then update this pinned version."
    }
}
