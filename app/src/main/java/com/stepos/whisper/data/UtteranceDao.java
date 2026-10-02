package com.stepos.whisper.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface UtteranceDao {
    @Insert
    long insert(Utterance utterance);

    @Query("UPDATE utterances SET userId = :userId WHERE id IN (:ids)")
    void assignUser(List<Long> ids, long userId);

    @Query("UPDATE utterances SET userId = :to WHERE userId = :from")
    void reassignUser(long from, long to);

    /**
     * 转写里的一句话（界面上只有起止时间）归给某人：没认出是谁的灰色句子，或认错了人要改归属的句子都走这里。
     */
    @Query("UPDATE utterances SET userId = :userId WHERE startTime = :start AND endTime = :end")
    void assignByTime(long start, long end, long userId);

    /** 还没交给 AI 的句子数，用于“让 AI 立即识别”的反馈。 */
    @Query("SELECT COUNT(*) FROM utterances WHERE aiState = 0")
    int countNotExtracted();

    /** 某时间段内所有人说的话，用于还原一段对话的完整上下文。 */
    @Query("SELECT * FROM utterances WHERE endTime >= :from AND startTime <= :to ORDER BY startTime")
    List<Utterance> getRange(long from, long to);

    @Query("SELECT * FROM utterances WHERE userId = :userId ORDER BY startTime DESC LIMIT :limit")
    LiveData<List<Utterance>> observeRecent(long userId, int limit);

    @Query("SELECT text FROM utterances WHERE userId = :userId ORDER BY startTime DESC LIMIT :limit")
    List<String> getRecentTexts(long userId, int limit);

    /** 最近的若干句（按时间正序），用于服务重启后恢复界面上的转写。 */
    @Query("SELECT * FROM (SELECT * FROM utterances ORDER BY startTime DESC LIMIT :limit) ORDER BY startTime")
    List<Utterance> getLatest(int limit);

    @Query("SELECT * FROM utterances WHERE aiState = 0 ORDER BY startTime")
    List<Utterance> getNotExtracted();

    /** 只升级状态：已识别的不会被改回排队中。 */
    @Query("UPDATE utterances SET aiState = :state "
            + "WHERE endTime >= :from AND startTime <= :to AND aiState < :state")
    void raiseAiState(long from, long to, int state);

    @Query("UPDATE utterances SET aiState = 0 WHERE endTime >= :from AND startTime <= :to AND aiState = 1")
    void resetQueued(long from, long to);

    @Query("DELETE FROM utterances WHERE startTime < :before")
    void deleteBefore(long before);

    @Query("DELETE FROM utterances")
    void deleteAll();
}
