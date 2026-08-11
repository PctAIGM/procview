package io.github.PctAIGM.procview.shizuku.ipc;

parcelable ProcessCatalogEntryParcel {
    int pid = -1;
    long startTimeTicks = -1L;
    int parentPid = -1;
    int uid = -1;
    String processName = "";
    String commandLine = "";
}
