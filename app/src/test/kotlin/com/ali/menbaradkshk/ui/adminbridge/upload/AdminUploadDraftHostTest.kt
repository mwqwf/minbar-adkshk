package com.ali.menbaradkshk.ui.adminbridge.upload

import com.ali.menbaradkshk.adminsurface.LiveLessonDestination
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminUploadDraftHostTest {
    @Test fun onlyExactLivePairCanResumeFrozenIntent() {
        val destination = LiveLessonDestination("cat", "القسم", "child", "الفرع")
        val frozen = UploadStageIntent("عنوان", "", "", "cat", "child", "القسم", "الفرع", "audio/opus")
        assertTrue(destination.matchesIntent(frozen))
        assertFalse(destination.copy(categoryId = "other").matchesIntent(frozen))
        assertFalse(destination.copy(subcategoryId = "other").matchesIntent(frozen))
        assertFalse(destination.copy(categoryName = "القسم ").matchesIntent(frozen))
        assertFalse(destination.copy(subcategoryName = "الفرع الجديد").matchesIntent(frozen))
    }
}
