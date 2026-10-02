package com.stepos.whisper.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface ChatAliasDao {
    /** 同一个昵称重新指定时覆盖旧的对应关系。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void put(ChatAlias alias);

    @Query("SELECT * FROM chat_aliases WHERE name = :name")
    ChatAlias get(String name);

    @Query("SELECT * FROM chat_aliases ORDER BY createdAt")
    List<ChatAlias> getAll();

    @Query("SELECT name FROM chat_aliases WHERE userId = :userId ORDER BY createdAt")
    LiveData<List<String>> observeNames(long userId);

    @Query("DELETE FROM chat_aliases WHERE name = :name")
    void delete(String name);

    @Query("DELETE FROM chat_aliases WHERE userId = :userId")
    void deleteForUser(long userId);

    @Query("UPDATE chat_aliases SET userId = :to WHERE userId = :from")
    void reassignUser(long from, long to);
}
