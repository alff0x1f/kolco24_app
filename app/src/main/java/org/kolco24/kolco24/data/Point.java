package org.kolco24.kolco24.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "points")
public class Point {
    @PrimaryKey
    public int id;

    public String name;
    public String description;
    public int cost;

    public Point(@NonNull String name, @NonNull String description, @NonNull int cost) {
        this.name = name;
        this.description = description;
        this.cost = cost;
    }

    public String getPoint() {
        return this.name;
    }
}
