package io.github.PctAIGM.procview.shizuku.ipc;

import io.github.PctAIGM.procview.shizuku.ipc.CapabilityProbeParcel;

interface IProcViewUserService {
    void destroy() = 16777114;
    int getProtocolVersion() = 1;
    CapabilityProbeParcel runCapabilityProbe() = 2;
}
