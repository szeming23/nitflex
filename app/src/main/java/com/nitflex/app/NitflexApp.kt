package com.nitflex.app

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import java.security.Security
import org.conscrypt.Conscrypt
import com.nitflex.app.database.AppDatabase
import com.nitflex.app.providers.AniWorldProvider
import com.nitflex.app.providers.SerienStreamProvider
import com.nitflex.app.utils.AppLanguageManager
import com.nitflex.app.utils.ArtworkRepairScheduler
import com.nitflex.app.utils.CacheUtils
import com.nitflex.app.utils.DnsResolver
import com.nitflex.app.utils.IsrgRootTrustProvider
import com.nitflex.app.utils.ApiKeysPersistence
import com.nitflex.app.utils.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NitflexApp : Application() {
    companion object {
        lateinit var instance: NitflexApp
            private set
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguageManager.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 0. Initialize Conscrypt for modern SSL on old Android
        Security.insertProviderAt(Conscrypt.newProvider(), 1)

        // 1. Install ISRG Root X1 globally for Let's Encrypt. On Android < 7 (API 24)
        // network_security_config.xml is not supported so the certificate must be injected manually.
        IsrgRootTrustProvider.install()

        // 2. Inizializzazione preferenze (con applicationContext)
        UserPreferences.setup(this)
        ApiKeysPersistence.load(this)
        DnsResolver.setDnsUrl(UserPreferences.dohProviderUrl)

        val appContext = applicationContext
        val isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val threshold = if (isTv) 10L else 50L

        applicationScope.launch(Dispatchers.IO) {
            AppDatabase.setup(appContext)
            SerienStreamProvider.initialize(appContext)
            AniWorldProvider.initialize(appContext)
            ArtworkRepairScheduler.schedule(appContext, UserPreferences.currentProvider)
            CacheUtils.autoClearIfNeeded(appContext, thresholdMb = threshold)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            CacheUtils.clearAppCache(this)
        }
    }
}

