package io.github.PctAIGM.procview.shizuku.ipc;

import io.github.PctAIGM.procview.shizuku.ipc.ProcessMetricParcel;

parcelable RawMetricFrameParcel {
    long sequence = 0L;
    long elapsedRealtimeNanos = 0L;
    long wallTimeMillis = 0L;
    long systemTotalCpuTicks = -1L;
    long systemIdleCpuTicks = -1L;
    long memoryTotalKb = -1L;
    long memoryAvailableKb = -1L;
    long collectionDurationMs = 0L;
    long catalogRevision = 0L;
    int sourceCode = 0;
    int frameFlags = 0;
    ProcessMetricParcel[] metrics;
}
