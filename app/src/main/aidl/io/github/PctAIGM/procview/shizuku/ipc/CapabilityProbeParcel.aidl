package io.github.PctAIGM.procview.shizuku.ipc;

parcelable CapabilityProbeParcel {
    int protocolVersion = 2;
    int servicePid = -1;
    int serviceUid = -1;
    long probeStartedWallTimeMs = 0L;
    long totalDurationMs = 0L;
    long procScanDurationMs = 0L;
    boolean procStatReadable = false;
    boolean procMeminfoReadable = false;
    boolean bootIdReadable = false;
    String bootId = "";
    int procPidCount = 0;
    int psPidCount = 0;
    int statReadableCount = 0;
    int statusReadableCount = 0;
    int cmdlineReadableCount = 0;
    int rssReadableCount = 0;
    int cpuAndRssReadableCount = 0;
    boolean pid1StatReadable = false;
    boolean psCommandAvailable = false;
    boolean pssCommandAvailable = false;
    boolean pssValueParsed = false;
    long pssProbeKb = -1L;
    long pssProbeDurationMs = 0L;
    int thermalZoneCount = 0;
    int thermalReadableCount = 0;
    int errorFlags = 0;
    boolean processListTruncated = false;
    int[] sampledUids;
    String[] thermalSensorNames;
}
