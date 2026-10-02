package com.stepos.whisper.data;

import androidx.room.Embedded;

/** 事项连同所属用户的名字和颜色，用于卡片展示。 */
public class FactCard {
    @Embedded
    public UserFact fact;

    public String userName;

    public int userColor;
}
