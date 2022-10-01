package org.kolco24.kolco24.data;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "points")
public class Point {
    @PrimaryKey(autoGenerate = true)
    public int id;

    @ColumnInfo(name = "number")
    public int mNumber;

    @NonNull
    @ColumnInfo(name = "description")
    public String mDescription;

    @ColumnInfo(name = "image")
    public int mCost;

    /*__init__*/
    public Point(int number, @NonNull String description, int cost) {
        this.mNumber = number;
        this.mDescription = description;
        this.mCost = cost;
    }
}
