package io.github.PctAIGM.procview.shizuku.ipc;

parcelable ProcessMetricParcel {
    int pid = -1;
    long startTimeTicks = -1L;
    long cpuTicks = -1L;
    long rssKb = -1L;
    int stateCode = 0;
}
