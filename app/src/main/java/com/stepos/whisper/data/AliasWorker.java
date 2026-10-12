package com.stepos.whisper.data;

/**
 * 会向机主询问“这个昵称是谁”的消息来源（聊天截图、微信通知），指定窗口（ChatAliasActivity）按来源取对应实现。
 */
public interface AliasWorker {
    /** 查下一个等待指定的昵称；都已指定（或没有任务）时返回 null。只读数据库，必须在后台线程调用。 */
    PendingAlias findPendingAlias(long jobId);

    /** 机主指定昵称对应的用户；userId 为 null 表示忽略这个昵称。任意线程。 */
    void setAlias(String name, Long userId);

    /** 机主为昵称新建一个用户（还没有声纹，之后可以补录）。任意线程。 */
    void createUserForAlias(String name, String userName);
}
