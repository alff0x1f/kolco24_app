package org.kolco24.kolco24.data;

final public class PointInfo {
    private final String name;
    private final String description;
    private final int cost;

    public PointInfo(String name, String description, int cost) {
        this.name = name;
        this.description = description;
        this.cost = cost;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public int getCost() {
        return cost;
    }
}
