package io.github.PctAIGM.procview.shizuku.ipc;

import io.github.PctAIGM.procview.shizuku.ipc.PssValueParcel;

parcelable PssResultParcel {
    long sampledAtElapsedRealtimeNanos = 0L;
    long durationMs = 0L;
    boolean commandAvailable = false;
    boolean timedOut = false;
    boolean outputTruncated = false;
    int errorFlags = 0;
    PssValueParcel[] values;
}
