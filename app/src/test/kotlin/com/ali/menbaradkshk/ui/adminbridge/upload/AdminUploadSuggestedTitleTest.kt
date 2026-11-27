package com.ali.menbaradkshk.ui.adminbridge.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdminUploadSuggestedTitleTest {
    @Test fun cleansNumberedArabicAndRetainsUsefulQualifier() {
        assertEquals("شرح باب الصلاة (الدرس الثاني)", suggestAdminLessonTitle(
            "٠٢_شرح_باب_الصلاة (الدرس الثاني) [96 kbps].OPUS"))
        assertEquals("كتاب الحج", suggestAdminLessonTitle("كتاب%20الحج.ogg"))
        assertEquals("Study + Practice", suggestAdminLessonTitle("Study_+_Practice.flac"))
    }

    @Test fun doesNotSuggestGeneratedOrGenericNames() {
        for (name in listOf("PTT-20261123-WA0004.opus", "WhatsApp Audio 2026.ogg",
            "تسجيل صوتي", "تسجيل_٠٣.m4a", "003.mp3", "٠٣_صوت.wav", "recording005.webm")) {
            assertNull(name, suggestAdminLessonTitle(name))
        }
    }

    @Test fun refusesAmbiguousOrHiddenProviderMetadata() {
        for (name in listOf("a".repeat(121) + ".mp3", "باب/الحج.wav", "باب%2Fالصلاة.opus",
            "باب%00الصلاة.opus", "باب%0Cالصلاة.mp3", "باب%E2%80%AEالصلاة.wav",
            "باب%ZZالصلاة.opus", "شرح الصلاة.mp3.exe", "باب\u2028الصلاة.wav")) {
            assertNull(name, suggestAdminLessonTitle(name))
        }
    }
}
