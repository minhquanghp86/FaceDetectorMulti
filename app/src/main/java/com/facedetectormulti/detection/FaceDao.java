package com.facedetectormulti.detection;

import androidx.room.*;
import java.util.List;

@Dao
public interface FaceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(RegisteredFace face);
    
    @Update
    void update(RegisteredFace face);
    
    @Delete
    void delete(RegisteredFace face);
    
    @Query("SELECT * FROM registered_faces ORDER BY name ASC")
    List<RegisteredFace> getAllFaces();
    
    @Query("SELECT * FROM registered_faces WHERE name LIKE :query")
    List<RegisteredFace> searchByName(String query);
    
    @Query("UPDATE registered_faces SET detectionCount = detectionCount + 1 WHERE id = :id")
    void incrementDetectionCount(int id);
    
    @Query("SELECT COUNT(*) FROM registered_faces")
    int getCount();
}