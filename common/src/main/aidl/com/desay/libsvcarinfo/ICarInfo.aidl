package com.desay.libsvcarinfo;

import com.desay.libsvcarinfo.ICarInfoCallBack;
import com.desay.libsvcarinfo.ICarInfoIntsCallBack;

// Method order must match the vendor interface exactly: AIDL transaction codes are
// assigned in declaration order.
interface ICarInfo {
    void registerCarInfoCallBack(int moduleId, ICarInfoCallBack callback, in int[] funcIds);
    void unRegisterCarInfoCallBack(int moduleId, ICarInfoCallBack callback);
    void registerCarInfoInsCallBack(int moduleId, ICarInfoIntsCallBack callback, in int[] funcIds);
    void unRegisterCarInfoIntsCallBack(int moduleId, ICarInfoIntsCallBack callback);
    int[] getItemValues(int moduleId, int funcId);
    void sendItemValues(int moduleId, int funcId, in int[] values);
    void sendValues(int id, in int[] values);
}
