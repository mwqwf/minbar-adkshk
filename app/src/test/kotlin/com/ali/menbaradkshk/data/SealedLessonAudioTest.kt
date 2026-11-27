package com.ali.menbaradkshk.data

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class SealedLessonAudioTest {
    private val sha = "ab".repeat(32)
    private val sealed = "https://minbar-api.mushafak.workers.dev/v1/sealed-lesson-audio/$sha.ogg"
    private val legacy = "https://media.menbar.app/serving/$sha.ogg"
    private val mirror = "https://minbar-adkassahk.vercel.app/audio/$sha.ogg"

    @Test fun canonicalSealedAudioNeverGetsALegacyMirrorEvenWithStaleCacheIdentity() {
        assertNull(MinbarApi.legacyAudioFallbackUrl(sealed, sha))
        assertNull(MinbarApi.legacyAudioFallbackUrl(sealed, "de".repeat(32)))
        assertNull(MinbarApi.legacyAudioFallbackUrl(sealed, ""))
    }

    @Test fun oldLessonsAndOnlyExactWorkerRouteRetainExistingFallbackRules() {
        assertEquals(mirror, MinbarApi.legacyAudioFallbackUrl(legacy, sha))
        assertNull(MinbarApi.legacyAudioFallbackUrl(legacy, ""))
        assertNull(MinbarApi.legacyAudioFallbackUrl(mirror, sha))
        assertEquals(mirror, MinbarApi.legacyAudioFallbackUrl(
            sealed.replace("minbar-api.mushafak.workers.dev", "minbar-api.mushafak.workers.dev.invalid"), sha,
        ))
        assertEquals(mirror, MinbarApi.legacyAudioFallbackUrl(sealed.replace("/v1/", "/v2/"), sha))
    }

}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SealedLessonAudioCacheTest {
    private val sha = "ab".repeat(32)
    private val sealed = "https://minbar-api.mushafak.workers.dev/v1/sealed-lesson-audio/$sha.ogg"

    @Test fun catalogParseAndResumeCacheRoundTripPreserveCanonicalUrlAndSha() {
        val raw = mapOf("_id" to "lesson", "audioUrl" to sealed, "sha256" to sha, "audioMirrorUrl" to "")
        val lesson = Lesson.fromMap("lesson", raw)
        val persisted = Lesson.fromJson(JSONObject(lesson.toJson().toString()))
        assertEquals(sealed, persisted.audioUrl)
        assertEquals(sha, persisted.sha256)
        assertNull(MinbarApi.legacyAudioFallbackUrl(persisted.audioUrl, persisted.sha256))
    }
}
