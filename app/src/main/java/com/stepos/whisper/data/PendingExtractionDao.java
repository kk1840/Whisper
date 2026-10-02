package com.stepos.whisper.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface PendingExtractionDao {
    @Insert
    long insert(PendingExtraction item);

    @Query("SELECT * FROM pending_extractions ORDER BY createdAt LIMIT :limit")
    List<PendingExtraction> getOldest(int limit);

    @Query("UPDATE pending_extractions SET attempts = attempts + 1 WHERE id = :id")
    void incrementAttempts(long id);

    @Query("DELETE FROM pending_extractions WHERE id = :id")
    void delete(long id);

    @Query("SELECT COUNT(*) FROM pending_extractions")
    int count();

    @Query("DELETE FROM pending_extractions")
    void deleteAll();

    @Query("UPDATE pending_extractions SET userId = :to WHERE userId = :from")
    void reassignUser(long from, long to);
}
