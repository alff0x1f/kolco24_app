package ru.kolco24.kolco24.data.entities;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "nfc_check")
public class NfcCheck {
    @PrimaryKey(autoGenerate = true)
    private int id = 0;
    private String pointNfc;
    private int pointNumber;
    private String memberNfcId;

    private String createDt;

    public NfcCheck(String pointNfc, int pointNumber, String memberNfcId, String createDt) {
        this.pointNfc = pointNfc;
        this.pointNumber = pointNumber;
        this.memberNfcId = memberNfcId;
        this.createDt = createDt;
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

    public String getCreateDt() {
        return createDt;
    }

    public void setCreateDt(String createDt) {
        this.createDt = createDt;
    }
}
