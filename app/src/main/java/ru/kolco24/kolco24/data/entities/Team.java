package ru.kolco24.kolco24.data.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.TypeConverters;

import org.json.JSONException;
import org.json.JSONObject;

import ru.kolco24.kolco24.data.Converters;


@Entity(tableName = "teams")
public class Team {
    @PrimaryKey(autoGenerate = true)
    private int id;
    private String owner;
    @ColumnInfo(name = "paid_people")
    private float paidPeople;
    private String dist;  // время 6h, 12h, 24h
    public String category; // категория 6h, 12h_mm, 12h_mw, 12h_team, 24h etc
    public String teamname;
    public String city;
    public String organization;
    public String year;
    public String start_number;

    @TypeConverters(Converters.class)
    public Long start_time;

    @TypeConverters(Converters.class)
    public Long finish_time;
    public boolean dnf;
    public int penalty;

    public static Team fromJson(JSONObject jsonObject) throws JSONException {
        int id = jsonObject.getInt("id");
        float paidPeople = (float) jsonObject.optDouble("paid_people", 0.0);
        String dist = jsonObject.optString("dist", "");
        String category = jsonObject.optString("category", "");
        String teamname = jsonObject.optString("teamname", "");
        String city = jsonObject.optString("city", "");
        String organization = jsonObject.optString("organization", "");
        String year = jsonObject.optString("year", "");
        String startNumber = jsonObject.optString("start_number", "");

        return new Team(id, "", paidPeople, dist, category, teamname, city, organization,
                year, startNumber, 0L, 0L, false, 0);
    }

    /**
     * Сравнивает две команды
     *
     * @param obj - объект для сравнения
     * @return true, если команды одинаковые, иначе false
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        Team other = (Team) obj;

        return (this.getId() == other.getId()
                && Float.compare(this.paidPeople, other.paidPeople) == 0
                && this.dist.equals(other.dist)
                && this.category.equals(other.category)
                && this.teamname.equals(other.teamname)
                && this.city.equals(other.city)
                && this.organization.equals(other.organization)
                && this.year.equals(other.year)
                && this.start_number.equals(other.start_number)
        );
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public float getPaidPeople() {
        return paidPeople;
    }

    public void setPaidPeople(float paid_people) {
        this.paidPeople = paid_people;
    }

    public String getDist() {
        return dist;
    }

    public void setDist(String dist) {
        this.dist = dist;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public void setTeamname(String teamname) {
        this.teamname = teamname;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public void setOrganization(String organization) {
        this.organization = organization;
    }

    public void setYear(String year) {
        this.year = year;
    }

    public void setStart_number(String start_number) {
        this.start_number = start_number;
    }

    // __init__
    public Team(int id, String owner, float paidPeople, String dist, String category, String teamname, String city,
                String organization, String year, String start_number, Long start_time,
                Long finish_time, boolean dnf, int penalty) {
        this.id = id;
        this.owner = owner;
        this.paidPeople = paidPeople;
        this.dist = dist;
        this.category = category;
        this.teamname = teamname;
        this.city = city;
        this.organization = organization;
        this.year = year;
        this.start_number = start_number;
        this.start_time = start_time;
        this.finish_time = finish_time;
        this.dnf = dnf;
        this.penalty = penalty;
    }
}


