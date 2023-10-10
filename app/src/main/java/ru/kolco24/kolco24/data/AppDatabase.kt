package ru.kolco24.kolco24.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import ru.kolco24.kolco24.data.daos.NfcCheckDao
import ru.kolco24.kolco24.data.daos.PhotoDao
import ru.kolco24.kolco24.data.daos.PointDao
import ru.kolco24.kolco24.data.daos.TeamDao
import ru.kolco24.kolco24.data.entities.NfcCheck
import ru.kolco24.kolco24.data.entities.Photo
import ru.kolco24.kolco24.data.entities.Point
import ru.kolco24.kolco24.data.entities.Team
import java.util.concurrent.Executors

@Database(
    entities = [Point::class, Photo::class, Team::class, NfcCheck::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pointDao(): PointDao
    abstract fun photoDao(): PhotoDao
    abstract fun teamDao(): TeamDao
    abstract fun nfcCheckDao(): NfcCheckDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        private const val NUMBER_OF_THREADS = 4
        @JvmField
        val databaseWriteExecutor = Executors.newFixedThreadPool(NUMBER_OF_THREADS)

        /**
         * Get the database, creating it if it does not exist.
         *
         * @param context the application context Singleton pattern, allows only one instance of the
         * database to be opened at a time.
         */
        @JvmStatic
        fun getDatabase(context: Context): AppDatabase? {
            if (INSTANCE == null) {
                synchronized(AppDatabase::class.java) {
                    if (INSTANCE == null) {
                        INSTANCE = Room.databaseBuilder(
                            context.applicationContext,
                            AppDatabase::class.java, "kolco24_database_3"
                        )
                            .addCallback(sRoomDatabaseCallback)
                            .build()
                    }
                }
            }
            return INSTANCE
        }

        private val sRoomDatabaseCallback: Callback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)

                //create empty database
                databaseWriteExecutor.execute {
                    val photo_dao = INSTANCE!!.pointDao()
                    photo_dao.deleteAll()

                    // Photo Point
                    val photoDao = INSTANCE!!.photoDao()
                    photoDao.deleteAll()

                    // Team
                    val teamDao = INSTANCE!!.teamDao()
                    teamDao.deleteAll()

                    // NfcCheck
                    val nfcCheckDao = INSTANCE!!.nfcCheckDao()
                    nfcCheckDao.deleteAllNfcChecks()
                }
            }
        }
    }
}
