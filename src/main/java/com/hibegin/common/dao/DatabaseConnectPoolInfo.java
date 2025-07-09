package com.hibegin.common.dao;

public class DatabaseConnectPoolInfo implements GetConnectPoolInfo {

    private final Integer connectActiveSize;
    private final Integer connectTotalSize;


    public DatabaseConnectPoolInfo(Integer connectActiveSize, Integer connectTotalSize) {
        this.connectActiveSize = connectActiveSize;
        this.connectTotalSize = connectTotalSize;
    }

    @Override
    public Integer getConnectActiveSize() {
        return connectActiveSize;
    }

    @Override
    public Integer getConnectTotalSize() {
        return connectTotalSize;
    }
}
