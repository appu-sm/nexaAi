package com.nexaai.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class BaselineTest {
    @Test fun packageIdentity() {
        assertEquals("com.nexaai.assistant", MainActivity::class.java.packageName)
    }
}