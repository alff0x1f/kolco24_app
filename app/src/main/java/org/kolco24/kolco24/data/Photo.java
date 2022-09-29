package org.kolco24.kolco24.data;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "photo_points")
public class Photo {
    @PrimaryKey(autoGenerate = true)
    public int id;

    @ColumnInfo(name = "team")
    public int team;

    @NonNull
    @ColumnInfo(name = "photo_url")
    public String photo_url;

    @NonNull
    @ColumnInfo(name = "point_number")
    public String point_number;

    /* possible values are "new", "send_info", "send_photo", "send_photo_info" */
    @NonNull
    @ColumnInfo(name = "status")
    public String status;


    //__init__
    public Photo(int team, @NonNull String photo_url, @NonNull String point_number) {
        this.team = team;
        this.photo_url = photo_url;
        this.point_number = point_number;
        this.status = "new";
    }
}
