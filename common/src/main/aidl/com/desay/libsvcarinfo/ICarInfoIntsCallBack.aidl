package com.desay.libsvcarinfo;

interface ICarInfoIntsCallBack {
    void onChangeEvent(int moduleId, int funcId, in int[] values);
}
