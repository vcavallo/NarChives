package com.narchives.reader.di

import android.content.Context
import androidx.room.Room
import com.narchives.reader.data.local.NarchivesDatabase
import com.narchives.reader.data.preferences.UserPreferences
import com.narchives.reader.data.remote.blossom.BlossomClient
import com.narchives.reader.data.remote.nostr.NostrClient
import com.narchives.reader.data.repository.ArchiveRepository
import com.narchives.reader.data.repository.ProfileRepository
import com.narchives.reader.data.repository.RelayRepository
import com.narchives.reader.replay.ReplayServer
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(private val context: Context) {

    // Database
    val database: NarchivesDatabase by lazy {
        Room.databaseBuilder(context, NarchivesDatabase::class.java, "narchives.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    // Networking
    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // Nostr
    val nostrClient: NostrClient by lazy { NostrClient() }

    // Blossom
    val blossomClient: BlossomClient by lazy { BlossomClient(okHttpClient) }

    // Preferences
    val userPreferences: UserPreferences by lazy { UserPreferences(context) }

    // Repositories
    val archiveRepository: ArchiveRepository by lazy {
        ArchiveRepository(
            nostrClient = nostrClient,
            blossomClient = blossomClient,
            archiveEventDao = database.archiveEventDao(),
            profileDao = database.profileDao(),
            userPreferences = userPreferences,
        )
    }

    val profileRepository: ProfileRepository by lazy {
        ProfileRepository(nostrClient, database.profileDao())
    }

    val relayRepository: RelayRepository by lazy {
        RelayRepository(database.relayDao(), userPreferences)
    }

    // Replay server
    val replayServer: ReplayServer by lazy {
        ReplayServer(context, blossomClient)
    }
}
