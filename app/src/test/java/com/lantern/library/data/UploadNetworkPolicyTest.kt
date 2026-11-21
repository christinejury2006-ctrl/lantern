package com.lantern.library.data

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Test

class UploadNetworkPolicyTest {

    @Test
    fun `cellular uploads require explicit opt in`() {
        assertEquals(
            NetworkType.UNMETERED,
            UploadNetworkPolicy.requiredNetworkType(allowMobileData = false)
        )
        assertEquals(
            NetworkType.CONNECTED,
            UploadNetworkPolicy.requiredNetworkType(allowMobileData = true)
        )
    }
}
