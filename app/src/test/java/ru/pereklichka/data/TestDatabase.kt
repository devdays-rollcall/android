package ru.pereklichka.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ru.pereklichka.data.db.AppDatabase

/** База в памяти для Robolectric-тестов: каждый тест получает пустую. */
fun inMemoryDatabase(): AppDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
