package org.kolco24.kolco24.data;

public class PhotoInfo {
    private final int team;
    private final String photo_url;
    private final String point_number;
    private final String status;

    public PhotoInfo(int team, String photo_url, String point_number, String status) {
        this.team = team;
        this.photo_url = photo_url;
        this.point_number = point_number;
        this.status = status;
    }

    public int getTeam() {
        return team;
    }

    public String getPhoto_url() {
        return photo_url;
    }

    public String getPoint_number() {
        return point_number;
    }

    public String getStatus() {
        return status;
    }
}
