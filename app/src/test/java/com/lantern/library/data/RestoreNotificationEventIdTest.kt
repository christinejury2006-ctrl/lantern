package com.lantern.library.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RestoreNotificationEventIdTest {
    @Test
    fun `same work keeps event identity across retries`() {
        val firstAttempt = restoreNotificationEventId("retry", "profile-a", "book-1", "work-uuid")
        val laterAttempt = restoreNotificationEventId("retry", "profile-a", "book-1", "work-uuid")

        assertEquals(firstAttempt, laterAttempt)
    }

    @Test
    fun `new restore of same book gets a new event identity`() {
        val firstRestore = restoreNotificationEventId("complete", "profile-a", "book-1", "work-one")
        val secondRestore = restoreNotificationEventId("complete", "profile-a", "book-1", "work-two")

        assertNotEquals(firstRestore, secondRestore)
    }

    @Test
    fun `event identity separates notification kinds profiles and books`() {
        val baseline = restoreNotificationEventId("complete", "profile-a", "book-1", "work-one")

        assertNotEquals(baseline, restoreNotificationEventId("retry", "profile-a", "book-1", "work-one"))
        assertNotEquals(baseline, restoreNotificationEventId("complete", "profile-b", "book-1", "work-one"))
        assertNotEquals(baseline, restoreNotificationEventId("complete", "profile-a", "book-2", "work-one"))
    }

    @Test
    fun `attention events can distinguish individual retry attempts`() {
        val firstAttempt = restoreNotificationEventId("attention", "profile-a", "book-1", "work-one", 1)
        val secondAttempt = restoreNotificationEventId("attention", "profile-a", "book-1", "work-one", 2)

        assertNotEquals(firstAttempt, secondAttempt)
    }
}
