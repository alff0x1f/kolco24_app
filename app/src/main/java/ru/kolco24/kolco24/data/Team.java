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
}


