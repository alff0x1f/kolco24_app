package ru.kolco24.kolco24.data;

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

    @ColumnInfo(name = "cost")
    public int mCost;

    /*__init__*/
    public Point(int number, @NonNull String description, int cost) {
        this.mNumber = number;
        this.mDescription = description;
        this.mCost = cost;
    }

    public static class PointExt{
        public int id;
        public int number;
        public String description;
        public int cost;
        public String photo_time;
        public String nfc_time;

        public PointExt(
                int id,
                int number,
                String description,
                int cost,
                String photo_time,
                String nfc_time
        ) {
            this.id = id;
            this.number = number;
            this.description = description;
            this.cost = cost;
            this.photo_time = photo_time;
            this.nfc_time = nfc_time;
        }
    }
}
