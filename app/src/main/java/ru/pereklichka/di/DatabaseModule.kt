package ru.pereklichka.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ru.pereklichka.data.db.AppDatabase
import ru.pereklichka.data.db.LocationDao
import javax.inject.Singleton

/** База Room и её DAO. Остальные DAO берутся из [AppDatabase] там, где нужны транзакции. */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME).build()

    @Provides
    fun locationDao(db: AppDatabase): LocationDao = db.locationDao()
}
