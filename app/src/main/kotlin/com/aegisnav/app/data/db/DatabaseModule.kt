package com.aegisnav.app.data.db

import android.content.Context
import com.aegisnav.app.util.AppLog
import androidx.room.Room
import com.aegisnav.app.security.DatabaseKeyManager
import com.aegisnav.app.security.KeystoreAuthRequiredException
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the encrypted Room database only.
 * All DAO, Repository, Detection, and Infrastructure providers are in their own modules:
 * - DaoModule.kt
 * - RepositoryModule.kt
 * - DetectionModule.kt
 * - InfraModule.kt
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * True when the app started but device auth was required to unlock the Keystore key.
     * MainActivity observes this and prompts for credentials, then restarts the process.
     */
    @Volatile var authRequired: Boolean = false
        private set

    @Provides @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        val passphrase = try {
            DatabaseKeyManager.getOrCreatePassphrase(context)
        } catch (e: KeystoreAuthRequiredException) {
            AppLog.w("DatabaseModule", "Keystore auth required - using in-memory DB until authenticated")
            authRequired = true
            return Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .fallbackToDestructiveMigration()
                .build()
        }
        val factory = SupportOpenHelperFactory(passphrase)
        passphrase.fill(0)
        val dbFile = context.getDatabasePath("aegisnav_database")

        if (dbFile.exists()) {
            try {
                val header = ByteArray(16)
                dbFile.inputStream().use { it.read(header) }
                val magic = "SQLite format 3\u0000"
                if (String(header) == magic) {
                    AppLog.w("DatabaseModule", "Plaintext DB detected - deleting for SQLCipher migration")
                    dbFile.delete()
                    context.deleteDatabase("aegisnav_database")
                }
            } catch (e: Exception) {
                AppLog.w("DatabaseModule", "Can't read DB header, deleting for safety: ${e.message}")
                context.deleteDatabase("aegisnav_database")
            }
        }

        return Room.databaseBuilder(context, AppDatabase::class.java, "aegisnav_database")
            .openHelperFactory(factory)
            .addMigrations(
                AppDatabase.MIGRATION_6_7,  AppDatabase.MIGRATION_7_8,  AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14, AppDatabase.MIGRATION_14_15,
                AppDatabase.MIGRATION_15_16, AppDatabase.MIGRATION_16_17,
                AppDatabase.MIGRATION_17_18, AppDatabase.MIGRATION_18_19,
                AppDatabase.MIGRATION_19_20,
                AppDatabase.MIGRATION_20_21, AppDatabase.MIGRATION_21_22,
                AppDatabase.MIGRATION_22_23,
                AppDatabase.MIGRATION_23_24,
                AppDatabase.MIGRATION_24_25,
                AppDatabase.MIGRATION_25_26,
                AppDatabase.MIGRATION_26_27,
                AppDatabase.MIGRATION_27_28,
                AppDatabase.MIGRATION_28_29,
                AppDatabase.MIGRATION_29_30,
                AppDatabase.MIGRATION_30_31
            )
            .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5)
            .build()
    }
}
