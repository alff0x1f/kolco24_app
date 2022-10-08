package ru.kolco24.kolco24.data;

import androidx.room.TypeConverter;

import java.util.Date;

public class Converters {
    @TypeConverter
    public Date fromTimestamp(Long value) {
        if (value == null) {
            return null;
        } else {
            return new Date(value);
        }
    }

    @TypeConverter
    public Long dateToTimestamp(Date date) {
        if (date == null) {
            return null;
        } else {
            return date.getTime();
        }
    }
}
