package app.kinjar.data.rates

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import app.kinjar.data.Clock
import java.time.Duration
import java.time.Instant

interface RateAttempts {
    var lastAttempt: Instant?
}

data class RateRefreshState(val busy: Boolean = false, val failed: Boolean = false)

class RateRefresh(private val repo: RateRepository, private val lookup: RateLookup, private val attempts: RateAttempts, private val clock: Clock) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(RateRefreshState())
    val state = mutableState.asStateFlow()

    suspend fun refresh(manual: Boolean = false) = withContext(Dispatchers.IO) {
        val now = clock.now()
        if (!mutex.tryLock()) return@withContext
        try {
            if (!manual) {
                val lastSuccess = repo.snapshots.first().cached.values.minOfOrNull { it.fetchedAt }
                if (!due(now, lastSuccess, attempts.lastAttempt)) return@withContext
            }
            mutableState.value = RateRefreshState(busy = true)
            val list = lookup.fetch()
            repo.cache(list.rates, list.date)
            mutableState.value = RateRefreshState()
        } catch (e: CancellationException) {
            mutableState.value = RateRefreshState()
            throw e
        } catch (_: Exception) {
            attempts.lastAttempt = now
            mutableState.value = RateRefreshState(failed = true)
        } finally {
            mutex.unlock()
        }
    }

    companion object {
        fun due(now: Instant, success: Instant?, attempt: Instant?): Boolean {
            val local = now.atZone(RateRepository.BELGRADE)
            var boundary = local.toLocalDate().atTime(8, 0).atZone(RateRepository.BELGRADE).toInstant()
            if (boundary > now) boundary = local.toLocalDate().minusDays(1).atTime(8, 0).atZone(RateRepository.BELGRADE).toInstant()
            if (success != null && success >= boundary && success <= now) return false
            return attempt == null || attempt > now || Duration.between(attempt, now) >= Duration.ofHours(1)
        }
    }
}
