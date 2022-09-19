package org.kolco24.kolco24.data;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "points")
public class Point {
    @PrimaryKey(autoGenerate = true)
    public int id;

    @NonNull
    @ColumnInfo(name = "name")
    public String mName;

    @NonNull
    @ColumnInfo(name = "description")
    public String mDescription;

    @ColumnInfo(name = "image")
    public int mCost;

    /*__init__*/
    public Point(@NonNull String name, @NonNull String description, int cost) {
        this.mName = name;
        this.mDescription = description;
        this.mCost = cost;
    }

    public PointInfo getPoint() {
        PointInfo pointView = new PointInfo(this.mName, this.mDescription, this.mCost);
        return pointView;
    }
}
