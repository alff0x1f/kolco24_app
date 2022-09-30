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
    public String team;

    @ColumnInfo(name = "point_number")
    public int point_number;

    @NonNull
    @ColumnInfo(name = "photo_url")
    public String photo_url;

    @NonNull
    @ColumnInfo(name = "photo_thumb_url")
    public String photo_thumb_url;

    /* possible values are "new", "send_info", "send_photo", "send_photo_info" */
    @NonNull
    @ColumnInfo(name = "status")
    public String status;


    //__init__
    public Photo(@NonNull String team, int point_number, @NonNull String photo_url,
                 @NonNull String photo_thumb_url) {
        this.team = team;
        this.point_number = point_number;
        this.photo_url = photo_url;
        this.photo_thumb_url = photo_thumb_url;
        this.status = "new";
    }
}
