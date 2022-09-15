package com.biosenix.banddebug.models;

public class Acceleration {
    public String deviceID;
    public int x, y, z;
    public long timestamp;

    public Acceleration() {

    }

    public Acceleration(String deviceID, int x,
                        int y, int z, int timestamp) {
        this.deviceID = deviceID;
        this.x = x;
        this.y = y;
        this.z = z;
        this.timestamp = timestamp;
    }
}