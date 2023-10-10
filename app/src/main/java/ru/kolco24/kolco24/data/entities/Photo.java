package ru.kolco24.kolco24.data.entities;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "photo_points")
public class Photo {
    public static final String NEW = "new";

    @PrimaryKey(autoGenerate = true)
    private int id;
    @ColumnInfo(name = "team_id")
    private int teamId;

    @ColumnInfo(name = "point_number")
    private int pointNumber;

    @NonNull
    @ColumnInfo(name = "photo_url")
    private String photoUrl;

    @NonNull
    @ColumnInfo(name = "photo_thumb_url")
    private String photoThumbUrl;

    /* possible values are "new", "send_info", "send_photo", "send_photo_info" */
    @NonNull
    @ColumnInfo(name = "status")
    private String status;
    @ColumnInfo(name = "sync_local")
    private boolean syncLocal;
    @ColumnInfo(name = "sync_internet")
    private boolean sync;

    @ColumnInfo(name = "photo_time")
    private String photoTime;


    //__init__
    public Photo(int teamId, int pointNumber, @NonNull String photoUrl,
                 @NonNull String photoThumbUrl, @NonNull String photoTime) {
        this.teamId = teamId;
        this.pointNumber = pointNumber;
        this.photoUrl = photoUrl;
        this.photoThumbUrl = photoThumbUrl;
        this.status = "new";
        this.photoTime = photoTime;
        this.sync = false;
        this.syncLocal = false;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getTeamId() {
        return teamId;
    }

    public void setTeamId(int teamId) {
        this.teamId = teamId;
    }


    public int getPointNumber() {
        return pointNumber;
    }

    public void setPointNumber(int pointNumber) {
        this.pointNumber = pointNumber;
    }

    @NonNull
    public String getPhotoUrl() {
        return photoUrl;
    }

    public void setPhotoUrl(@NonNull String photoUrl) {
        this.photoUrl = photoUrl;
    }

    public String getPhotoThumbUrl() {
        return photoThumbUrl;
    }

    public void setPhotoThumbUrl(String photoThumbUrl) {
        this.photoThumbUrl = photoThumbUrl;
    }

    public String getStatus() {
        return this.status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isSync() {
        return this.sync;
    }

    public void setSync(boolean sync) {
        this.sync = sync;
    }

    public boolean isSyncLocal() {
        return this.syncLocal;
    }

    public void setSyncLocal(boolean syncLocal) {
        this.syncLocal = syncLocal;
    }

    public String getPhotoTime() {
        return this.photoTime;
    }

    public void setPhotoTime(String photoTime) {
        this.photoTime = photoTime;
    }
}
