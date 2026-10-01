package com.geospatial.processing.di

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ViewModel tests replace Dispatchers.Main with a test dispatcher, so they cannot notice when the real
 * one is missing. Without kotlinx-coroutines-swing every viewModelScope.launch crashes at runtime
 * ("Module with the Main dispatcher is missing"). This test runs code on the real Main dispatcher.
 */
class MainDispatcherTest {
    @Test
    fun `Dispatchers Main runs on the Swing event thread`() = runBlocking {
        val onEventThread = withContext(Dispatchers.Main) { SwingUtilities.isEventDispatchThread() }
        assertTrue(onEventThread)
    }
}
