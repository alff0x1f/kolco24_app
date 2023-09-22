package ru.kolco24.kolco24.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.TypeConverters;

@Entity(tableName = "nfc_check")
public class NfcCheck {
    @PrimaryKey(autoGenerate = true)
    private int id = 0;
    private String pointNfc = null;
    private int pointNumber = 0;
    private String memberNfcId = null;

    @TypeConverters(Converters.class)
    private Long createDt = null;

    public NfcCheck(String pointNfc, int pointNumber, String memberNfcId) {
        this.pointNfc = pointNfc;
        this.pointNumber = pointNumber;
        this.memberNfcId = memberNfcId;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getPointNfc() {
        return pointNfc;
    }

    public void setPointNfc(String pointNfc) {
        this.pointNfc = pointNfc;
    }

    public int getPointNumber() {
        return pointNumber;
    }

    public void setPointNumber(int pointNumber) {
        this.pointNumber = pointNumber;
    }

    public String getMemberNfcId() {
        return memberNfcId;
    }

    public void setMemberNfcId(String memberNfcId) {
        this.memberNfcId = memberNfcId;
    }

    public Long getCreateDt() {
        return createDt;
    }

    public void setCreateDt(Long createDt) {
        this.createDt = createDt;
    }
}
