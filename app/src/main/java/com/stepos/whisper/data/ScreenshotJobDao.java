package com.stepos.whisper.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface ScreenshotJobDao {
    @Insert
    long insert(ScreenshotJob job);

    @Query("SELECT * FROM screenshot_jobs ORDER BY createdAt")
    List<ScreenshotJob> getAll();

    @Query("SELECT * FROM screenshot_jobs WHERE id = :id")
    ScreenshotJob get(long id);

    @Query("UPDATE screenshot_jobs SET layoutText = :text WHERE id = :id")
    void setLayoutText(long id, String text);

    @Query("UPDATE screenshot_jobs SET parsed = :json WHERE id = :id")
    void setParsed(long id, String json);

    @Query("UPDATE screenshot_jobs SET attempts = attempts + 1 WHERE id = :id")
    void incrementAttempts(long id);

    @Query("DELETE FROM screenshot_jobs WHERE id = :id")
    void delete(long id);

    @Query("DELETE FROM screenshot_jobs WHERE createdAt < :before")
    void deleteBefore(long before);
}
