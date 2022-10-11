package ru.kolco24.kolco24.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.TypeConverters;


@Entity(tableName = "teams")
public class Team {
    @PrimaryKey(autoGenerate = true)
    public int id;

    public String owner;
    public float paid_people;
    public String dist;  // время 6h, 12h, 24h
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

    public void setPaid_people(float paid_people) {
        this.paid_people = paid_people;
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
    public Team(int id, String owner, float paid_people, String dist, String category, String teamname, String city,
                String organization, String year, String start_number, Long start_time,
                Long finish_time, boolean dnf, int penalty) {
        this.id = id;
        this.owner = owner;
        this.paid_people = paid_people;
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


