package com.tom.cpm.shared.network;

import com.tom.cpl.nbt.NBTTagCompound;
import com.tom.cpm.shared.network.NetH;

/**
 * Client-side dispatch interface for CPM model server S2C packets.
 * Implemented by {@code CpmModelTransferClient} in the server.client package.
 * All methods are called from S2C packet handle() implementations.
 */
public interface IModelClientHandler {

    /** Handle model list response. Tag contains: {models: [...], total: N} */
    void handleModelList(NBTTagCompound tag);

    /** Handle upload init ACK. Tag contains: {uid, accepted, reason} */
    void handleUploadInitAck(NBTTagCompound tag);

    /** Handle chunk ACK. Tag contains: {uid, idx, ok, msg} */
    void handleDataChunkAck(NBTTagCompound tag);

    /** Handle upload result. Tag contains: {uid, ok, status, mid, msg} */
    void handleUploadResult(NBTTagCompound tag);

    /** Handle upload resume ACK. Tag contains: {uid, lastChunk, canResume} */
    void handleUploadResumeAck(NBTTagCompound tag);

    /** Handle model download chunk. Tag contains: {mid, idx, total, data} */
    void handleDownloadChunk(NBTTagCompound tag, NetH from);

    /** Handle model delete result. Tag contains: {mid, ok, status, msg} */
    void handleDeleteResult(NBTTagCompound tag);
}
