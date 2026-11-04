package app.kinjar.di

import android.content.Context
import androidx.core.content.edit
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import app.kinjar.data.BudgetRepository
import app.kinjar.data.Clock
import app.kinjar.data.EventStore
import app.kinjar.data.Projector
import app.kinjar.data.SystemClock
import app.kinjar.data.ThemeStore
import app.kinjar.data.TransactionAccountStore
import app.kinjar.data.db.KinjarDb
import app.kinjar.data.deviceId
import app.kinjar.data.security.AppLock
import app.kinjar.data.receipt.FiscalReceiptClient
import app.kinjar.data.receipt.ReceiptLookup
import app.kinjar.data.rates.RateRepository
import app.kinjar.data.rates.RateRefresh
import app.kinjar.data.rates.RateAttempts
import app.kinjar.data.rates.NbsClient
import java.time.Instant
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides @Singleton
    fun loans(repo: BudgetRepository): app.kinjar.data.LoanRepository = repo.loans
    @Provides @Singleton
    fun rates(repo: BudgetRepository): RateRepository = repo.exchangeRates

    @Provides @Singleton
    fun rateRefresh(@ApplicationContext context: Context, rates: RateRepository, clock: Clock): RateRefresh {
        val preferences = context.getSharedPreferences("exchange_rates", Context.MODE_PRIVATE)
        val attempts = object : RateAttempts {
            override var lastAttempt: Instant?
                get() = preferences.getLong("last_attempt", 0).takeIf { it != 0L }?.let(Instant::ofEpochMilli)
                set(value) { preferences.edit { putLong("last_attempt", value?.toEpochMilli() ?: 0) } }
        }
        return RateRefresh(rates, NbsClient(), attempts, clock)
    }

    @Provides @Singleton
    fun receiptLookup(): ReceiptLookup = FiscalReceiptClient()

    @Provides @Singleton
    fun db(@ApplicationContext context: Context): KinjarDb =
        KinjarDb(AndroidSqliteDriver(KinjarDb.Schema, context, "kinjar.db"))

    @Provides @Singleton
    fun clock(): Clock = SystemClock()

    @Provides @Singleton
    fun themeStore(@ApplicationContext context: Context): ThemeStore = ThemeStore.fromPreferences(context)

    @Provides @Singleton
    fun appLock(@ApplicationContext context: Context): AppLock = AppLock.fromPreferences(context)

    @Provides @Singleton
    fun transactionAccountStore(@ApplicationContext context: Context): TransactionAccountStore = TransactionAccountStore.fromPreferences(context)

    @Provides @Singleton
    fun eventStore(@ApplicationContext context: Context, db: KinjarDb, clock: Clock): EventStore {
        val store = EventStore(db, deviceId(context), clock, Projector(db))
        val prefs = context.getSharedPreferences("projection", Context.MODE_PRIVATE)
        if (prefs.getInt("version", 0) != Projector.VERSION) {
            store.rebuild()
            prefs.edit { putInt("version", Projector.VERSION) }
        }
        return store
    }

    @Provides @Singleton
    fun repository(@ApplicationContext context: Context, db: KinjarDb, clock: Clock, store: EventStore): BudgetRepository =
        BudgetRepository(db, store, clock, deviceId(context), CoroutineScope(SupervisorJob() + Dispatchers.Default))
}
