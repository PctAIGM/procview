package io.github.PctAIGM.procview.shizuku.ipc;

import io.github.PctAIGM.procview.shizuku.ipc.ProcessCatalogEntryParcel;

parcelable ProcessCatalogChunkParcel {
    long revision = 0L;
    boolean restartRequired = false;
    int offset = 0;
    int totalEntries = 0;
    int nextOffset = -1;
    ProcessCatalogEntryParcel[] entries;
}
