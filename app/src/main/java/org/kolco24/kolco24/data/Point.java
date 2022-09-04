package org.kolco24.kolco24.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "points")
public class Point {
    @PrimaryKey
    public int id;

    public String name;
    public String description;
    public int cost;
}
