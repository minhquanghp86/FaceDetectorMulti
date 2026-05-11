package com.facedetectormulti.detection;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;

@Database(entities = {RegisteredFace.class}, version = 1, exportSchema = false)
@TypeConverters(FloatArrayConverter.class)
public abstract class FaceDatabase extends RoomDatabase {
    
    public abstract FaceDao faceDao();
    
    private static volatile FaceDatabase INSTANCE;
    
    public static FaceDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (FaceDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                        context.getApplicationContext(),
                        FaceDatabase.class,
                        "face_database"
                    ).build();
                }
            }
        }
        return INSTANCE;
    }
}