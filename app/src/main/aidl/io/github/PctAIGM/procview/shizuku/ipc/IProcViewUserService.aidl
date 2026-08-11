package io.github.PctAIGM.procview.shizuku.ipc;

import io.github.PctAIGM.procview.shizuku.ipc.CapabilityProbeParcel;
import io.github.PctAIGM.procview.shizuku.ipc.ProcessCatalogChunkParcel;
import io.github.PctAIGM.procview.shizuku.ipc.ProcessKeyParcel;
import io.github.PctAIGM.procview.shizuku.ipc.PssResultParcel;
import io.github.PctAIGM.procview.shizuku.ipc.RawMetricFrameParcel;

interface IProcViewUserService {
    void destroy() = 16777114;
    int getProtocolVersion() = 1;
    CapabilityProbeParcel runCapabilityProbe() = 2;
    RawMetricFrameParcel collectMetricFrame() = 3;
    ProcessCatalogChunkParcel getCatalogChunk(long expectedRevision, int offset, int limit) = 4;
    PssResultParcel readPss(in ProcessKeyParcel[] keys) = 5;
}
