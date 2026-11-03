package com.davefx.photowidget

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.davefx.photowidget.TestUtil.EXPECTED_FLAT
import com.davefx.photowidget.TestUtil.EXPECTED_RECURSIVE
import com.davefx.photowidget.TestUtil.ID
import com.davefx.photowidget.TestUtil.ctx
import com.davefx.photowidget.TestUtil.photo
import com.davefx.photowidget.TestUtil.tree
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhotoIndexTest {
    @After fun clean() = Store.delete(ctx, ID)

    private fun folderCfg(sub: Boolean = true) = WidgetConfig(folders = setOf(tree.toString()), includeSubfolders = sub)

    @Test fun scansSubfoldersUpToMaxDepth_skipsGifTextAndHidden() {
        val n = PhotoIndex.rebuild(ctx, ID, folderCfg())
        assertEquals(EXPECTED_RECURSIVE, PhotoIndex.readAll(ctx, ID).toSet())
        assertEquals(EXPECTED_RECURSIVE.size, n)
    }

    @Test fun withoutSubfoldersOnlyTopLevel() {
        PhotoIndex.rebuild(ctx, ID, folderCfg(sub = false))
        assertEquals(EXPECTED_FLAT, PhotoIndex.readAll(ctx, ID).toSet())
    }

    @Test fun photosAndFoldersAreMergedWithoutDuplicates() {
        val extra = "content://media/external/images/media/12345"
        val cfg = folderCfg(sub = false).copy(photos = setOf(photo("root/a_800x600.jpg").toString(), extra))
        PhotoIndex.rebuild(ctx, ID, cfg)
        val all = PhotoIndex.readAll(ctx, ID)
        assertEquals(all.size, all.toSet().size)
        assertEquals(EXPECTED_FLAT + extra, all.toSet())
    }

    @Test fun onlyPhotosNeedsNoFolderAccess() {
        val cfg = WidgetConfig(photos = setOf("content://a/1", "content://a/2", "content://a/3"))
        assertEquals(3, PhotoIndex.rebuild(ctx, ID, cfg))
    }

    @Test fun unreadableFolderDoesNotCrash() {
        val cfg = WidgetConfig(folders = setOf("content://com.android.externalstorage.documents/tree/primary%3ANope"), photos = setOf("content://a/1"))
        assertEquals(1, PhotoIndex.rebuild(ctx, ID, cfg))
    }

    @Test fun positionalAccessMatchesFile() {
        PhotoIndex.rebuild(ctx, ID, folderCfg())
        val all = PhotoIndex.readAll(ctx, ID)
        all.forEachIndexed { i, u -> assertEquals(u, PhotoIndex.at(ctx, ID, i)) }
        assertNull(PhotoIndex.at(ctx, ID, all.size))
        assertFalse("no debe quedar el temporal", File(ctx.filesDir, "index_$ID.tmp").exists())
    }

    @Test fun missingIndexIsEmpty() {
        assertEquals(emptyList<String>(), PhotoIndex.readAll(ctx, ID))
        assertNull(PhotoIndex.at(ctx, ID, 0))
    }

    @Test fun reshuffleAvoidsCurrentPhotoFirst() {
        val cfg = folderCfg()
        repeat(30) {
            val current = PhotoIndex.at(ctx, ID, 0)
            PhotoIndex.rebuild(ctx, ID, cfg, avoidFirst = current)
            if (current != null) assertNotEquals(current, PhotoIndex.at(ctx, ID, 0))
        }
    }

    @Test fun orderIsShuffled() {
        val orders = (1..5).map { PhotoIndex.rebuild(ctx, ID, folderCfg()); PhotoIndex.readAll(ctx, ID) }
        assert(orders.toSet().size > 1) { "cinco barajados idénticos" }
    }
}
