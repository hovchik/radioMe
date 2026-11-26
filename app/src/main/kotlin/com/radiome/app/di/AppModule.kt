package com.radiome.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.radiome.data.PositionRepository
import com.radiome.data.TransferRepository
import com.radiome.feature.transceiver.link.PositionBeacon
import com.radiome.feature.transceiver.link.PositionHistory
import com.radiome.feature.transceiver.link.RoomPositionHistory
import com.radiome.feature.transceiver.location.AndroidLocationSource
import com.radiome.feature.transceiver.location.LocationSource
import com.radiome.feature.transceiver.settings.GroupKeyProvider
import com.radiome.feature.transceiver.settings.KeystoreGroupKeyProvider
import com.radiome.data.db.RadioDatabase
import com.radiome.data.store.RoomSessionStore
import com.radiome.feature.transceiver.image.AndroidImageCodec
import com.radiome.feature.transceiver.image.ImageCodec
import com.radiome.feature.transceiver.link.LinkRecording
import com.radiome.feature.transceiver.link.RoomTransferHistory
import com.radiome.feature.transceiver.link.ServiceTransceiverClient
import com.radiome.feature.transceiver.link.TransceiverClient
import com.radiome.feature.transceiver.link.TransferHistory
import com.radiome.feature.transceiver.settings.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** Process-wide scope for state that outlives screens (settings cache, service binding). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): RadioDatabase = RadioDatabase.create(context)

    @Provides @Singleton
    fun transferRepository(db: RadioDatabase): TransferRepository = TransferRepository(db)

    @Provides @Singleton
    fun sessionStore(db: RadioDatabase): RoomSessionStore = RoomSessionStore(db)

    @Provides @Singleton
    fun positionRepository(db: RadioDatabase): PositionRepository = PositionRepository(db)

    @Provides @Singleton
    fun positionHistory(repository: PositionRepository): PositionHistory = RoomPositionHistory(repository)

    @Provides @Singleton
    fun linkRecording(repository: TransferRepository, positions: PositionRepository): LinkRecording = LinkRecording(repository, positions)

    @Provides @Singleton
    fun groupKeys(store: DataStore<Preferences>, @AppScope scope: CoroutineScope): GroupKeyProvider =
        KeystoreGroupKeyProvider(store, scope)

    @Provides @Singleton
    fun location(@ApplicationContext context: Context): LocationSource = AndroidLocationSource(context)

    @Provides @Singleton
    fun beacon(
        client: TransceiverClient,
        settings: SettingsRepository,
        keys: GroupKeyProvider,
        location: LocationSource,
        @AppScope scope: CoroutineScope,
    ): PositionBeacon = PositionBeacon(client, settings, keys, location, scope)

    @Provides @Singleton
    fun history(repository: TransferRepository): TransferHistory = RoomTransferHistory(repository)

    @Provides @Singleton
    fun preferences(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("radio_settings") })

    @Provides @Singleton
    fun settings(store: DataStore<Preferences>, @AppScope scope: CoroutineScope): SettingsRepository =
        SettingsRepository(store, scope)

    @Provides @Singleton
    fun transceiverClient(
        @ApplicationContext context: Context,
        recording: LinkRecording,
        @AppScope scope: CoroutineScope,
    ): TransceiverClient = ServiceTransceiverClient(context, recording, scope)

    @Provides @Singleton
    fun imageCodec(@ApplicationContext context: Context): ImageCodec = AndroidImageCodec(context)
}
