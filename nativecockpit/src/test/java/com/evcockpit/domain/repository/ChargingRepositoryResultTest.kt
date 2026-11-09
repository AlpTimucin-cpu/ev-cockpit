package com.evcockpit.domain.repository

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import com.evcockpit.domain.model.ChargingCatalog

class ChargingRepositoryResultTest {
    private class FakeRepository(private val failure: Exception? = null) : ChargingRepository {
        override val catalog = MutableStateFlow(ChargingCatalog())
        override suspend fun refresh() {
            failure?.let { throw it }
        }
    }

    @Test fun networkFailureIsResultNotAnUncaughtCoroutineException() = runBlocking {
        val result = FakeRepository(IOException("offline")).refreshResult()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
    }

    @Test fun completedRefreshIsSuccess() = runBlocking {
        assertTrue(FakeRepository().refreshResult().isSuccess)
    }

    @Test fun cancellationIsNeverSwallowedAsNetworkError() = runBlocking {
        var thrown = false
        try {
            FakeRepository(CancellationException("cancelled")).refreshResult()
        } catch (_: CancellationException) {
            thrown = true
        }
        assertTrue(thrown)
    }
}
