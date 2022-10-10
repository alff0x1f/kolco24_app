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
    public String dist;
    public String name;
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

    // __init__
    public Team(String owner, float paid_people, String dist, String name, String city,
                 String organization, String year, String start_number, Long start_time,
                 Long finish_time, boolean dnf, int penalty) {
        this.owner = owner;
        this.paid_people = paid_people;
        this.dist = dist;
        this.name = name;
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


