package xyz.libravault.core.billing

import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.PendingPurchasesParams
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import xyz.libravault.core.storage.SupporterRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BillingModule {

    @Provides
    @Singleton
    fun provideSupportBillingClient(
        @ApplicationContext context: Context,
        supporterRepository: SupporterRepository,
    ): SupportBillingClient = PlayBillingClientImpl(
        supporterRepository = supporterRepository,
        // Deliberately not exposed as its own Hilt binding (unlike e.g.
        // core:tts's TtsModule.provideTtsCoroutineScope) — an unqualified
        // CoroutineScope binding here would collide with that one in the
        // shared SingletonComponent. This scope is private to this one client.
        externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        billingClientFactory = { listener ->
            BillingClient.newBuilder(context)
                .setListener(listener)
                .enablePendingPurchases(
                    PendingPurchasesParams.newBuilder()
                        .enableOneTimeProducts()
                        .build()
                )
                .build()
        },
    )
}
